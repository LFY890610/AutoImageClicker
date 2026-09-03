package com.lfy.autoimageclicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
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
    private boolean fastPathPending = false;
    private int state = 0; // 0 = wait red packet, 1 = wait open button

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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
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

        // Capture at a reduced resolution. This keeps the same aspect/layout while cutting
        // the number of pixels that must be copied and analyzed by roughly 3-5x.
        float captureScale = Math.min(1f, 540f / Math.max(1, screenWidth));
        captureWidth = Math.max(1, Math.round(screenWidth * captureScale));
        captureHeight = Math.max(1, Math.round(screenHeight * captureScale));
        captureDensityDpi = Math.max(1, Math.round(densityDpi * captureScale));
        clickScaleX = screenWidth / (float) captureWidth;
        clickScaleY = screenHeight / (float) captureHeight;

        captureThread = new HandlerThread("ScreenCaptureWorker", android.os.Process.THREAD_PRIORITY_DISPLAY);
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
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

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3);
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
        state = 0;
        stateSince = System.currentTimeMillis();
        cooldownUntil = 0;
        lastAnalyzeTime = 0;
        lastClickTime = 0;
        running = true;
        showToast("极速识别已启动");

        // Try immediately in case the target is already visible when capture starts.
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
            if (now - lastFastPathTime < 12) return;
            lastFastPathTime = now;
            analyzeAccessibility(now);
        });
    }

    private boolean analyzeAccessibility(long now) {
        if (!AutoClickAccessibilityService.isConnected()) return false;

        if (state == 1) {
            if (now - stateSince > 2800) {
                state = 0;
                cooldownUntil = now + 180;
                return false;
            }
            if (now - lastClickTime >= 45 && AutoClickAccessibilityService.clickCenterTextIfPresent("開", "开")) {
                lastClickTime = now;
                state = 0;
                cooldownUntil = now + 220;
                return true;
            }
        } else {
            if (now >= cooldownUntil && now - lastClickTime >= 160
                    && AutoClickAccessibilityService.clickLatestTextIfPresent("微信红包")) {
                lastClickTime = now;
                state = 1;
                stateSince = now;
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

            // UI-tree matching is much faster and more exact than image matching.
            if (analyzeAccessibility(now)) return;

            long interval = state == 1 ? 18 : 32;
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
        if (!AutoClickAccessibilityService.isConnected()) return;

        if (state == 1) {
            if (now - stateSince > 2800) {
                state = 0;
                cooldownUntil = now + 180;
                return;
            }

            VisionDetector.Match open = VisionDetector.detectOpenButton(frame);
            if (open != null && now - lastClickTime >= 45) {
                if (AutoClickAccessibilityService.clickAt(open.x * clickScaleX, open.y * clickScaleY)) {
                    lastClickTime = now;
                    state = 0;
                    cooldownUntil = now + 220;
                }
            }
            return;
        }

        if (now < cooldownUntil || now - lastClickTime < 160) return;
        VisionDetector.Match redPacket = VisionDetector.detectRedPacket(frame);
        if (redPacket != null) {
            if (AutoClickAccessibilityService.clickAt(redPacket.x * clickScaleX, redPacket.y * clickScaleY)) {
                lastClickTime = now;
                state = 1;
                stateSince = now;
            }
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * captureWidth;
        int bitmapWidth = captureWidth + Math.max(0, rowPadding / pixelStride);

        Bitmap padded = Bitmap.createBitmap(bitmapWidth, captureHeight, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (bitmapWidth == captureWidth) return padded;

        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, captureWidth, captureHeight);
        padded.recycle();
        return cropped;
    }

    private void stopCapture() {
        running = false;
        releaseCaptureObjects(true);
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void releaseCaptureObjects(boolean stopProjection) {
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
        }
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
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "自动识别", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("自动识别点击器运行状态");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, CaptureService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("自动识别点击器正在运行")
                .setContentText("极速检测红包和“开”按钮")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stopIntent).build())
                .build();
    }

    private void showToast(String text) {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }
}
