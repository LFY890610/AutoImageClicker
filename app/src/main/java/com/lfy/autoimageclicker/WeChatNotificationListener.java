package com.lfy.autoimageclicker;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class WeChatNotificationListener extends NotificationListenerService {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastOpenAt = 0L;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !WECHAT_PACKAGE.equals(sbn.getPackageName())) return;
        if (!CaptureService.isRunning()) return;

        Notification n = sbn.getNotification();
        if (n == null || !looksLikeRedPacket(n)) return;

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastOpenAt < 650L) return;

        // Do not interrupt another packet that is already being processed.
        if (!CaptureService.prepareForNotificationRedPacket()) return;
        lastOpenAt = now;

        wakeScreenBriefly();

        PendingIntent pi = n.contentIntent;
        if (pi == null) return;
        if (!sendNotificationIntent(pi)) return;

        // Re-check nodes repeatedly while WeChat enters the exact conversation. Usually the first
        // one or two checks are enough; image fallback remains armed only for a short period.
        long[] delays = {35L, 75L, 130L, 210L, 320L, 480L, 700L, 980L, 1350L};
        for (long delay : delays) {
            handler.postDelayed(() -> {
                CaptureService.noteWeChatUiChanged();
                CaptureService.requestAccessibilityFastPath();
            }, delay);
        }
    }

    private boolean sendNotificationIntent(PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                Bundle bundle = options.toBundle();
                pi.send(this, 0, null, null, null, null, bundle);
            } else {
                pi.send();
            }
            return true;
        } catch (Throwable first) {
            try {
                // Vendor ROM fallback.
                pi.send();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private boolean looksLikeRedPacket(Notification n) {
        StringBuilder all = new StringBuilder();
        if (n.tickerText != null) all.append(n.tickerText).append(' ');
        if (n.extras != null) {
            append(all, n.extras.getCharSequence(Notification.EXTRA_TITLE));
            append(all, n.extras.getCharSequence(Notification.EXTRA_TEXT));
            append(all, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            append(all, n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            append(all, n.extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
            CharSequence[] lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                for (CharSequence line : lines) append(all, line);
            }
        }

        String s = all.toString();
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        return s.contains("微信红包")
                || s.contains("[红包]")
                || s.contains("【红包】")
                || s.contains("红包")
                || s.contains("恭喜发财")
                || lower.contains("red packet");
    }

    private static void append(StringBuilder sb, CharSequence value) {
        if (value != null) sb.append(value).append(' ');
    }

    private void wakeScreenBriefly() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isInteractive()) return;
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "AutoImageClicker:RedPacketWake");
            wl.acquire(3500L);
        } catch (Throwable ignored) {}
    }

    public static boolean isNotificationAccessGranted(Context context) {
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), "enabled_notification_listeners");
            if (enabled == null) return false;
            ComponentName me = new ComponentName(context, WeChatNotificationListener.class);
            String flat = me.flattenToString();
            String shortFlat = me.flattenToShortString();
            return enabled.contains(flat) || enabled.contains(shortFlat);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
