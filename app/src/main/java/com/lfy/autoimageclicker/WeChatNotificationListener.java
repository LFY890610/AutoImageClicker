package com.lfy.autoimageclicker;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
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
        if (now - lastOpenAt < 700L) return;
        lastOpenAt = now;

        wakeScreenBriefly();

        PendingIntent pi = n.contentIntent;
        if (pi == null) return;
        try {
            pi.send();
        } catch (PendingIntent.CanceledException ignored) {
            return;
        }

        // The notification PendingIntent opens the exact conversation. Re-check several times
        // during the short UI transition so the first visible red packet is handled immediately.
        long[] delays = {60L, 130L, 240L, 420L, 700L, 1050L};
        for (long delay : delays) {
            handler.postDelayed(CaptureService::requestAccessibilityFastPath, delay);
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
        } catch (Throwable ignored) {
        }
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
