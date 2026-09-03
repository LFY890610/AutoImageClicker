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

    private static final int WAIT_PACKET = 0;
    private static final int WAIT_OPEN = 1;
    private static final int WAIT_RESULT = 2;
    private static final int WAIT_CLEAR = 3;

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

    private volatile int state = WAIT_PACKET;
    private long stateSince = 0L;
    private long lastAnalyzeTime = 0L;
    private long lastClickTime = 0L;
    private long lastFastPathTime = 0L;
    private boolean fastPathPending = false;

    private float pendingRedX = -1f;
    private float pendingRedY = -1f;
    private long pendingRedTime = 0L;
    private int pendingRedCount = 0;

    private float lastPacketX = -10000f;
    private float lastPacketY = -10000f;
    private int clearFrames = 0;

    public static boolean isRunning() {
        return running;
    }

    public static void requestAccessibilityFastPath() {
        CaptureService service = instance.get();
        if (service != null && running) service.scheduleAccessibilityFastPath();
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
        if (ACTION_STOP.equals(intent.getAction())) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction()) && !running) {
            startAsForeground();
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData == null) {
                showToast("屏幕捕获授权无效，请重新启动识别");
                stopCapture();
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                startCapture(resultCode, resultData);
            } catch (Throwable t) {
                showToast("启动识别失败：" + t.getClass().getSimpleName());
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

        // 480px width keeps the supplied WeChat packet features while allowing near-frame-rate scans.
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

        state = WAIT_PACKET;
        stateSince = System.currentTimeMillis();
        lastAnalyzeTime = 0L;
        lastClickTime = 0L;
        lastFastPathTime = 0L;
        lastPacketX = -10000f;
        lastPacketY = -10000f;
        clearFrames = 0;
        resetPendingRed();
        running = true;
        showToast("前台极速识别已启动：红包1次，開/开1次，完成后自动返回");
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
        long[] delays = {80L, 140L, 220L, 340L, 500L, 700L, 950L};
        for (long delay : delays) h.postDelayed(this::scheduleAccessibilityFastPath, delay);
    }

    private boolean analyzeAccessibility(long now) {
        if (!AutoClickAccessibilityService.isConnected()
                || !AutoClickAccessibilityService.isWeChatForeground()) return false;

        if (state == WAIT_OPEN) {
            // Already-claimed/expired packets may show a result without an open button.
            if (AutoClickAccessibilityService.isRedPacketResultVisible()) {
                AutoClickAccessibilityService.backOnce();
                enterWaitClear(now);
                return true;
            }

            if (now - lastClickTime >= 8L
                    && AutoClickAccessibilityService.clickWeChatOpenButtonOnce()) {
                lastClickTime = now;
                state = WAIT_RESULT;
                stateSince = now;
                scheduleResultChecks();
                return true;
            }

            if (now - stateSince > 2400L) {
                AutoClickAccessibilityService.backOnce();
                enterWaitClear(now);
            }
            return false;
        }

        if (state == WAIT_RESULT) {
            long elapsed = now - stateSince;
            if (elapsed >= 80L && AutoClickAccessibilityService.isRedPacketResultVisible()) {
                AutoClickAccessibilityService.backOnce();
                enterWaitClear(now);
                return true;
            }

            // Fallback for WeChat builds that do not expose result text.
            if (elapsed >= 700L) {
                AutoClickAccessibilityService.backOnce();
                enterWaitClear(now);
                return true;
            }
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
            if (analyzeAccessibility(now)) return;

            long interval = state == WAIT_RESULT ? 20L : 8L;
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

        if (state == WAIT_PACKET) {
            VisionDetector.Match packet = VisionDetector.detectRedPacket(frame);
            if (packet == null) {
                resetPendingRed();
                return;
            }

            // Very strong matches click on the first frame. Borderline matches require one extra
            // adjacent frame, which normally costs only a few tens of milliseconds.
            boolean confirmed = packet.score >= 0.84 || confirmRedPacket(packet, now);
            if (!confirmed || now - lastClickTime < 70L) return;

            if (AutoClickAccessibilityService.clickAt(
                    packet.x * clickScaleX, packet.y * clickScaleY)) {
                lastClickTime = now;
                lastPacketX = packet.x;
                lastPacketY = packet.y;
                state = WAIT_OPEN;
                stateSince = now;
                resetPendingRed();
            }
            return;
        }

        if (state == WAIT_OPEN) {
            VisionDetector.Match open = VisionDetector.detectOpenButton(frame);
            if (open != null && now - lastClickTime >= 8L) {
                if (AutoClickAccessibilityService.clickAt(
                        open.x * clickScaleX, open.y * clickScaleY)) {
                    lastClickTime = now;
                    state = WAIT_RESULT;
                    stateSince = now;
                    scheduleResultChecks();
                }
            }
            return;
        }

        if (state == WAIT_CLEAR) {
            VisionDetector.Match packet = VisionDetector.detectRedPacket(frame);
            if (packet == null) {
                clearFrames++;
                if (clearFrames >= 2) {
                    state = WAIT_PACKET;
                    stateSince = now;
                    resetPendingRed();
                }
                return;
            }

            clearFrames = 0;
            float dx = packet.x - lastPacketX;
            float dy = packet.y - lastPacketY;
            float newPacketDistance = Math.max(60f, captureWidth * 0.14f);
            if (dx * dx + dy * dy > newPacketDistance * newPacketDistance) {
                // A clearly different/new packet is allowed immediately.
                state = WAIT_PACKET;
                stateSince = now;
                resetPendingRed();
                if (packet.score >= 0.84) {
                    if (AutoClickAccessibilityService.clickAt(
                            packet.x * clickScaleX, packet.y * clickScaleY)) {
                        lastClickTime = now;
                        lastPacketX = packet.x;
                        lastPacketY = packet.y;
                        state = WAIT_OPEN;
                        stateSince = now;
                    }
                } else {
                    confirmRedPacket(packet, now);
                }
            }
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

    private void enterWaitClear(long now) {
        state = WAIT_CLEAR;
        stateSince = now;
        clearFrames = 0;
        resetPendingRed();
    }

    private void resetPendingRed() {
        pendingRedX = -1f;
        pendingRedY = -1f;
        pendingRedTime = 0L;
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

        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight);
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
                    CHANNEL_ID, "自动识别", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("微信红包前台极速识别运行状态");
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
                .setContentTitle("微信红包前台极速识别运行中")
                .setContentText("严格识别红包1次 → 開/开1次 → 自动返回")
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
