package com.lfy.autoimageclicker;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Locale;

public class WeChatNotificationListener extends NotificationListenerService {
    private long lastOpenAt = 0L;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !ReliableRedPacketAccessibilityService.isAutomationEnabled(this)) return;
        Notification n = sbn.getNotification();
        if (n == null) return;

        String text = collectText(n);
        if (!looksLikeRedPacket(text)) return;

        String pkg = sbn.getPackageName() == null ? "" : sbn.getPackageName();
        if (pkg.isEmpty() || pkg.equals(getPackageName())) return;

        String lower = pkg.toLowerCase(Locale.ROOT);
        boolean likelyWeChat = lower.equals("com.tencent.mm")
                || lower.contains("tencent.mm")
                || lower.contains("wechat")
                || lower.contains("weixin")
                || text.contains("微信红包")
                || text.contains("[红包]")
                || text.contains("【红包】");
        if (!likelyWeChat) return;

        ReliableRedPacketAccessibilityService.noteRedPacketNotification(this, pkg);

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastOpenAt < 700L) return;
        lastOpenAt = now;

        PendingIntent pi = n.contentIntent;
        if (pi == null) return;
        if (!sendPendingIntent(pi)) return;

        long[] delays = {40L, 90L, 160L, 260L, 420L, 700L, 1100L, 1700L};
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        for (long delay : delays) {
            h.postDelayed(ReliableRedPacketAccessibilityService::requestImmediateCheck, delay);
        }
    }

    private boolean sendPendingIntent(PendingIntent pi) {
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
                pi.send();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static String collectText(Notification n) {
        StringBuilder sb = new StringBuilder();
        if (n.tickerText != null) sb.append(n.tickerText).append(' ');
        if (n.extras != null) {
            append(sb, n.extras.getCharSequence(Notification.EXTRA_TITLE));
            append(sb, n.extras.getCharSequence(Notification.EXTRA_TEXT));
            append(sb, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            append(sb, n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            append(sb, n.extras.getCharSequence(Notification.EXTRA_INFO_TEXT));
            CharSequence[] lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) for (CharSequence line : lines) append(sb, line);
        }
        return sb.toString();
    }

    private static boolean looksLikeRedPacket(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return text.contains("微信红包")
                || text.contains("[红包]")
                || text.contains("【红包】")
                || text.contains("红包")
                || text.contains("恭喜发财")
                || lower.contains("red packet");
    }

    private static void append(StringBuilder sb, CharSequence value) {
        if (value != null) sb.append(value).append(' ');
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
