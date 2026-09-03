package com.lfy.autoimageclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.ref.WeakReference;
import java.util.List;

public class AutoClickAccessibilityService extends AccessibilityService {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static volatile WeakReference<AutoClickAccessibilityService> instance =
            new WeakReference<>(null);
    private static volatile long lastWeChatEventAt = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = new WeakReference<>(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && WECHAT_PACKAGE.contentEquals(pkg)) {
            lastWeChatEventAt = SystemClock.uptimeMillis();
            if (CaptureService.isRunning()) CaptureService.requestAccessibilityFastPath();
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = new WeakReference<>(null);
        return super.onUnbind(intent);
    }

    public static boolean isConnected() {
        return instance.get() != null;
    }

    public static boolean isWeChatForeground() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null && root.getPackageName() != null
                    && WECHAT_PACKAGE.contentEquals(root.getPackageName())) return true;
        } catch (Throwable ignored) {}
        return SystemClock.uptimeMillis() - lastWeChatEventAt < 5000L;
    }

    public static boolean clickAt(float x, float y) {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 5))
                .build();
        return service.dispatchGesture(gesture, null, null);
    }

    /** Second stage only: exact central 開/开 node, exactly one click. */
    public static boolean clickWeChatOpenButtonOnce() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = safeRoot(service);
        if (root == null) return false;
        AccessibilityNodeInfo best = findBestOpenNode(root);
        if (best == null) return false;
        if (best.isClickable() && best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        best.getBoundsInScreen(r);
        return !r.isEmpty() && clickAt(r.exactCenterX(), r.exactCenterY());
    }

    public static boolean isRedPacketResultVisible() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = safeRoot(service);
        if (root == null) return false;
        String[] terms = {
                "红包详情", "查看领取详情", "已领取", "手慢了", "来晚了",
                "红包派完了", "已被领完", "已领完", "已存入零钱", "已过期"
        };
        for (String term : terms) {
            if (hasVisibleText(root, term)) return true;
        }
        return false;
    }

    public static boolean backOnce() {
        AutoClickAccessibilityService service = instance.get();
        return service != null && service.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private static AccessibilityNodeInfo safeRoot(AutoClickAccessibilityService service) {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return null;
            CharSequence pkg = root.getPackageName();
            if (pkg != null && !WECHAT_PACKAGE.contentEquals(pkg)
                    && SystemClock.uptimeMillis() - lastWeChatEventAt >= 5000L) return null;
            return root;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static AccessibilityNodeInfo findBestOpenNode(AccessibilityNodeInfo root) {
        Rect rootRect = new Rect();
        root.getBoundsInScreen(rootRect);
        float cx = rootRect.exactCenterX();
        float cy = rootRect.exactCenterY();
        if (cx <= 0 || cy <= 0) return null;

        AccessibilityNodeInfo best = null;
        double bestScore = -1e30;
        String[] terms = {"開", "开"};
        for (String term : terms) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(term);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                if (!isExactOpen(node.getText()) && !isExactOpen(node.getContentDescription())) continue;

                AccessibilityNodeInfo candidate = findClickableAncestor(node, 5);
                if (candidate == null) candidate = node;
                Rect r = new Rect();
                candidate.getBoundsInScreen(r);
                if (r.isEmpty()) continue;

                float dx = r.exactCenterX() - cx;
                float dy = r.exactCenterY() - cy;
                if (Math.abs(dx) > rootRect.width() * 0.32f
                        || Math.abs(dy) > rootRect.height() * 0.34f) continue;

                double score = -(dx * dx + dy * dy);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static AccessibilityNodeInfo findClickableAncestor(
            AccessibilityNodeInfo node, int maxParents) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; cur != null && i <= maxParents; i++) {
            if (cur.isVisibleToUser() && cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean hasVisibleText(AccessibilityNodeInfo root, String term) {
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(term);
            if (nodes == null) return false;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                CharSequence t = node.getText();
                CharSequence d = node.getContentDescription();
                if ((t != null && t.toString().contains(term))
                        || (d != null && d.toString().contains(term))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isExactOpen(CharSequence value) {
        if (value == null) return false;
        String s = value.toString().trim();
        return "開".equals(s) || "开".equals(s);
    }
}
