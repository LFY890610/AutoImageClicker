package com.lfy.autoimageclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

public class CaptureService extends Service {
    public static final String ACTION_START = "com.lfy.autoimageclicker.START";
    public static final String ACTION_STOP = "com.lfy.autoimageclicker.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "auto_click_capture";
    private static final int NOTIFICATION_ID = 88;

    private static final int STATE_WAIT_PACKET = 0;
    private static final int STATE_WAIT_OPEN = 1;
    private static final int STATE_WAIT_RESULT = 2;

    private static volatile boolean running = false;
    private static volatile WeakReference<CaptureService> instance = new WeakReference<>(null);

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private Handler mainHandler;

    private int screenWidth;
    private int screenHeight;
    private int densityDpi;
    private int captureWidth;
    private int captureHeight;
    private int captureDensityDpi;
    private float clickScaleX = 1f;
    private float clickScaleY = 1f;

    private long lastAnalyzeTime = 0;
    private long lastClickTime = 0;
    private long stateSince = 0;
    private long cooldownUntil = 0;
    private long lastFastPathTime = 0;
    private long visualFallbackUntil = 0;
    private long notificationFlowUntil = 0;
    private boolean fastPathPending = false;
    private volatile int state = STATE_WAIT_PACKET;

    private float pendingRedX = -1f;
    private float pendingRedY = -1f;
    private long pendingRedTime = 0;
    private int pendingRedCount = 0;

    public static boolean isRunning() {
        return running;
    }

    public static void requestAccessibilityFastPath() {
        CaptureService service = instance.get();
        if (service != null && running) service.scheduleAccessibilityFastPath();
    }

    /**
     * Called on real WeChat UI changes. Visual recognition is opened only for a short period,
     * instead of continuously scanning the screen while the phone is idle.
     */
    public static void noteWeChatUiChanged() {
        CaptureService service = instance.get();
        if (service == null || !running) return;
        long now = System.currentTimeMillis();
        if (service.state == STATE_WAIT_RESULT) return;
        long duration = service.state == STATE_WAIT_OPEN ? 1500L : 800L;
        service.visualFallbackUntil = Math.max(service.visualFallbackUntil, now + duration);
    }

    /** Arm a full red-packet cycle immediately before a matching WeChat notification is opened. */
    public static boolean prepareForNotificationRedPacket() {
        CaptureService service = instance.get();
        if (service == null || !running) return false;
        long now = System.currentTimeMillis();

        // Do not interrupt a packet that is already being opened or finalized.
        if (service.state != STATE_WAIT_PACKET && now - service.stateSince < 5000L) return false;

        service.state = STATE_WAIT_PACKET;
        service.stateSince = now;
        service.cooldownUntil = 0;
        service.notificationFlowUntil = now + 6000L;
        service.visualFallbackUntil = Math.max(service.visualFallbackUntil, now + 4200L);
        service.resetPendingRed();
        service.scheduleAccessibilityFastPath();
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = new WeakReference<>(this);
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action) && !running) {
            startAsForeground();
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData == null) {
                showToast("屏幕捕获授权无效，请重新启动");
                stopCapture();
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                startCapture(resultCode, resultData);
            } catch (Throwable t) {
                showToast("启动失败：" + t.getClass().getSimpleName());
                stopCapture();
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startCapture(int resultCode, Intent resultData) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        densityDpi = metrics.densityDpi;

        // Screen capture stays ready only as a fallback. Nodes are always tried first.
        float captureScale = Math.min(1f, 480f / Math.max(1, screenWidth));
        captureWidth = Math.max(1, Math.round(screenWidth * captureScale));
        captureHeight = Math.max(1, Math.round(screenHeight * captureScale));
        captureDensityDpi = Math.max(1, Math.round(densityDpi * captureScale));
        clickScaleX = screenWidth / (float) captureWidth;
        clickScaleY = screenHeight / (float) captureHeight;

        captureThread = new HandlerThread(
                "ScreenCaptureWorker", android.os.Process.THREAD_PRIORITY_DISPLAY);
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) throw new IllegalStateException("MediaProjection unavailable");
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                running = false;
                releaseCaptureObjects(false);
                stopSelf();
            }
        }, mainHandler);

        imageReader = ImageReader.newInstance(
                captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "AutoImageClickerDisplay",
                captureWidth,
                captureHeight,
                captureDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );

        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        state = STATE_WAIT_PACKET;
        stateSince = System.currentTimeMillis();
        cooldownUntil = 0;
        lastAnalyzeTime = 0;
        lastClickTime = 0;
        visualFallbackUntil = 0;
        notificationFlowUntil = 0;
        resetPendingRed();
        running = true;
        showToast("后台全自动已启动：节点优先，视觉仅兜底");
        scheduleAccessibilityFastPath();
    }

    private void scheduleAccessibilityFastPath() {
        Handler h = captureHandler;
        if (h == null || fastPathPending) return;
        fastPathPending = true;
        h.post(() -> {
            fastPathPending = false;
            if (!running) return;
            long now = System.currentTimeMillis();
            if (now - lastFastPathTime < 4L) return;
            lastFastPathTime = now;
            analyzeAccessibility(now);
        });
    }

    private void scheduleResultChecks() {
        Handler h = captureHandler;
        if (h == null) return;
        long[] delays = {120L, 220L, 360L, 520L, 760L, 1100L, 1600L, 2200L};
        for (long delay : delays) {
            h.postDelayed(this::scheduleAccessibilityFastPath, delay);
        }
    }

    private boolean analyzeAccessibility(long now) {
        if (!AutoClickAccessibilityService.isConnected()
                || !AutoClickAccessibilityService.isWeChatForeground()) return false;

        if (state == STATE_WAIT_RESULT) {
            long elapsed = now - stateSince;
            if (elapsed >= 120L && AutoClickAccessibilityService.isRedPacketResultVisible()) {
                AutoClickAccessibilityService.backOnce();
                finishCycle(now);
                return true;
            }

            // Some WeChat builds do not expose result text. If the open button has disappeared,
            // a delayed single BACK safely returns from the result/popup to the chat page.
            if (elapsed >= 1800L && !AutoClickAccessibilityService.hasWeChatOpenButton()) {
                AutoClickAccessibilityService.backOnce();
                finishCycle(now);
                return true;
            }

            // Never keep the state machine permanently stuck if a vendor build exposes neither.
            if (elapsed >= 5000L) {
                finishCycle(now);
            }
            return false;
        }

        if (state == STATE_WAIT_OPEN) {
            if (now - stateSince > 3400L) {
                finishCycle(now);
                return false;
            }
            if (now - lastClickTime >= 10L
                    && AutoClickAccessibilityService.clickWeChatOpenButtonTwice()) {
                lastClickTime = now;
                state = STATE_WAIT_RESULT;
                stateSince = now;
                visualFallbackUntil = 0;
                resetPendingRed();
                scheduleResultChecks();
                return true;
            }
            return false;
        }

        // Stage 1. Exact “微信红包” node is immediate: no image frame and no second-frame delay.
        if (now >= cooldownUntil && now - lastClickTime >= 70L
                && AutoClickAccessibilityService.clickWeChatRedPacketOnce()) {
            lastClickTime = now;
            state = STATE_WAIT_OPEN;
            stateSince = now;
            visualFallbackUntil = Math.max(visualFallbackUntil, now + 3400L);
            resetPendingRed();
            return true;
        }
        return false;
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || !running) return;
            long now = System.currentTimeMillis();

            if (!AutoClickAccessibilityService.isWeChatForeground()) return;

            // Always give accessibility nodes the first opportunity on every actual UI change.
            if (analyzeAccessibility(now)) return;

            // Result/exit is node-driven. Visual recognition is only a short-lived fallback for
            // the packet card and open button.
            if (state == STATE_WAIT_RESULT || now > visualFallbackUntil) return;

            long interval = state == STATE_WAIT_OPEN ? 8L : 12L;
            if (now - lastAnalyzeTime < interval) return;
            lastAnalyzeTime = now;

            Bitmap frame = imageToBitmap(image);
            if (frame == null) return;
            try {
                analyzeFrame(frame, now);
            } finally {
                if (!frame.isRecycled()) frame.recycle();
            }
        } catch (Throwable ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private void analyzeFrame(Bitmap frame, long now) {
        if (!AutoClickAccessibilityService.isConnected()
                || !AutoClickAccessibilityService.isWeChatForeground()) return;

        if (state == STATE_WAIT_OPEN) {
            resetPendingRed();
            if (now - stateSince > 3400L) {
                finishCycle(now);
                return;
            }

            VisionDetector.Match open = VisionDetector.detectOpenButton(frame);
            if (open != null && now - lastClickTime >= 10L) {
                if (AutoClickAccessibilityService.clickAtTwice(
                        open.x * clickScaleX, open.y * clickScaleY)) {
                    lastClickTime = now;
                    state = STATE_WAIT_RESULT;
                    stateSince = now;
                    visualFallbackUntil = 0;
                    scheduleResultChecks();
                }
            }
            return;
        }

        if (state != STATE_WAIT_PACKET) return;
        if (now < cooldownUntil || now - lastClickTime < 70L) {
            resetPendingRed();
            return;
        }

        VisionDetector.Match redPacket = VisionDetector.detectRedPacket(frame);
        if (redPacket == null) {
            if (pendingRedCount > 0 && now - pendingRedTime > 90L) resetPendingRed();
            return;
        }

        // Strict high-confidence layout may click on the first visual frame. Borderline matches
        // still require two adjacent frames, preserving the anti-emoji protection.
        boolean confirmed = redPacket.score >= 0.82 || confirmRedPacket(redPacket, now);
        if (!confirmed) return;

        if (AutoClickAccessibilityService.clickAt(
                redPacket.x * clickScaleX, redPacket.y * clickScaleY)) {
            lastClickTime = now;
            state = STATE_WAIT_OPEN;
            stateSince = now;
            visualFallbackUntil = Math.max(visualFallbackUntil, now + 3400L);
            resetPendingRed();
        }
    }

    private boolean confirmRedPacket(VisionDetector.Match match, long now) {
        if (match == null) return false;
        if (pendingRedCount <= 0 || now - pendingRedTime > 110L) {
            pendingRedX = match.x;
            pendingRedY = match.y;
            pendingRedTime = now;
            pendingRedCount = 1;
            return false;
        }

        float dx = match.x - pendingRedX;
        float dy = match.y - pendingRedY;
        float maxDistance = Math.max(18f, captureWidth * 0.055f);
        if (dx * dx + dy * dy > maxDistance * maxDistance) {
            pendingRedX = match.x;
            pendingRedY = match.y;
            pendingRedTime = now;
            pendingRedCount = 1;
            return false;
        }

        pendingRedX = (pendingRedX + match.x) * 0.5f;
        pendingRedY = (pendingRedY + match.y) * 0.5f;
        pendingRedTime = now;
        pendingRedCount++;
        return pendingRedCount >= 2;
    }

    private void finishCycle(long now) {
        state = STATE_WAIT_PACKET;
        stateSince = now;
        cooldownUntil = now + 260L;
        notificationFlowUntil = 0;
        visualFallbackUntil = 0;
        resetPendingRed();

        Handler h = captureHandler;
        if (h != null) {
            h.postDelayed(this::scheduleAccessibilityFastPath, 260L);
        }
    }

    private void resetPendingRed() {
        pendingRedX = -1f;
        pendingRedY = -1f;
        pendingRedTime = 0;
        pendingRedCount = 0;
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * captureWidth;
        int bitmapWidth = captureWidth + Math.max(0, rowPadding / pixelStride);

        Bitmap padded = Bitmap.createBitmap(
                bitmapWidth, captureHeight, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (bitmapWidth == captureWidth) return padded;

        Bitmap cropped = Bitmap.createBitmap(
                padded, 0, 0, captureWidth, captureHeight);
        padded.recycle();
        return cropped;
    }

    private void stopCapture() {
        running = false;
        resetPendingRed();
        releaseCaptureObjects(true);
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void releaseCaptureObjects(boolean stopProjection) {
        if (imageReader != null) imageReader.setOnImageAvailableListener(null, null);
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Throwable ignored) {}
            imageReader = null;
        }
        if (stopProjection && mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Throwable ignored) {}
        }
        mediaProjection = null;
        if (captureThread != null) {
            try { captureThread.quitSafely(); } catch (Throwable ignored) {}
            captureThread = null;
            captureHandler = null;
        }
    }

    @Override
    public void onDestroy() {
        stopCapture();
        instance = new WeakReference<>(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "后台全自动", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("微信红包后台自动处理运行状态");
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, CaptureService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(
                this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("微信红包后台全自动正在运行")
                .setContentText("通知触发 → 节点极速领取 → 自动返回；视觉仅作兜底")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stopIntent).build())
                .build();
    }

    private void showToast(String text) {
        mainHandler.post(() -> Toast.makeText(
                getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }
}
