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
    private static volatile WeakReference<AutoClickAccessibilityService> instance = new WeakReference<>(null);
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
            // Record WeChat activity even before capture starts. Some red-packet popup windows
            // temporarily expose no stable root package, so this recent-event marker is used as
            // a safe context fallback instead of blocking a valid image-recognition click.
            lastWeChatEventAt = SystemClock.uptimeMillis();
            if (CaptureService.isRunning()) {
                CaptureService.requestAccessibilityFastPath();
            }
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

    /**
     * Returns true when the current root explicitly belongs to WeChat, or when WeChat emitted
     * an accessibility event very recently. The latter fixes popup pages where root package
     * reporting is temporarily null/unstable.
     */
    public static boolean isWeChatForeground() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null && root.getPackageName() != null
                    && WECHAT_PACKAGE.contentEquals(root.getPackageName())) {
                return true;
            }
        } catch (Throwable ignored) {}
        return SystemClock.uptimeMillis() - lastWeChatEventAt < 8000L;
    }

    /** Execute exactly one tap at the supplied screen coordinate. */
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

    /**
     * Execute two rapid taps at exactly the same point. The second tap starts 55 ms after the
     * first one so Android treats them as two distinct taps while keeping the total delay tiny.
     */
    public static boolean clickAtTwice(float x, float y) {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;

        Path first = new Path();
        first.moveTo(x, y);
        Path second = new Path();
        second.moveTo(x, y);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(first, 0, 5))
                .addStroke(new GestureDescription.StrokeDescription(second, 55, 5))
                .build();
        return service.dispatchGesture(gesture, null, null);
    }

    /**
     * First stage: click the newest matching WeChat red packet exactly once.
     */
    public static boolean clickWeChatRedPacketOnce() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;

        List<AccessibilityNodeInfo> labels = root.findAccessibilityNodeInfosByText("微信红包");
        if (labels == null || labels.isEmpty()) return false;

        AccessibilityNodeInfo best = null;
        double bestScore = -1e30;
        for (AccessibilityNodeInfo label : labels) {
            if (label == null || !label.isVisibleToUser()) continue;
            AccessibilityNodeInfo candidate = findClickableAncestor(label, 8);
            if (candidate == null) candidate = label;

            Rect r = new Rect();
            candidate.getBoundsInScreen(r);
            if (r.isEmpty()) continue;

            // Prefer the lowest/newest visible packet. Greeting text provides a strong bonus.
            double score = r.bottom * 20.0 + r.centerY();
            AccessibilityNodeInfo context = candidate;
            for (int i = 0; i < 3 && context != null; i++) {
                if (subtreeContains(context, "恭喜发财，大吉大利") || subtreeContains(context, "恭喜发财")) {
                    score += 1_000_000.0;
                    break;
                }
                context = context.getParent();
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return performSingleClickOrCenter(best);
    }

    /**
     * Second stage: locate an exposed central 開/开 node and tap its center exactly twice.
     */
    public static boolean clickWeChatOpenButtonTwice() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;

        Rect rootRect = new Rect();
        root.getBoundsInScreen(rootRect);
        float cx = rootRect.exactCenterX();
        float cy = rootRect.exactCenterY();
        if (cx <= 0 || cy <= 0) return false;

        AccessibilityNodeInfo best = null;
        double bestScore = -1e30;
        String[] terms = {"開", "开"};
        for (String term : terms) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(term);
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                if (!isExactOpen(text) && !isExactOpen(desc)) continue;

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

        if (best == null) return false;
        Rect r = new Rect();
        best.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return clickAtTwice(r.exactCenterX(), r.exactCenterY());
    }

    // Compatibility wrappers used by older code paths.
    public static boolean clickWeChatRedPacket() {
        return clickWeChatRedPacketOnce();
    }

    public static boolean clickWeChatOpenButton() {
        return clickWeChatOpenButtonTwice();
    }

    private static AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node, int maxParents) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; cur != null && i <= maxParents; i++) {
            if (cur.isVisibleToUser() && cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean performSingleClickOrCenter(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return clickAt(r.exactCenterX(), r.exactCenterY());
    }

    private static boolean subtreeContains(AccessibilityNodeInfo root, String needle) {
        if (root == null || needle == null) return false;
        CharSequence t = root.getText();
        CharSequence d = root.getContentDescription();
        if ((t != null && t.toString().contains(needle)) || (d != null && d.toString().contains(needle))) return true;
        int n = Math.min(root.getChildCount(), 20);
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null && subtreeContainsShallow(child, needle, 2)) return true;
        }
        return false;
    }

    private static boolean subtreeContainsShallow(AccessibilityNodeInfo node, String needle, int depth) {
        if (node == null) return false;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if ((t != null && t.toString().contains(needle)) || (d != null && d.toString().contains(needle))) return true;
        if (depth <= 0) return false;
        int n = Math.min(node.getChildCount(), 12);
        for (int i = 0; i < n; i++) {
            if (subtreeContainsShallow(node.getChild(i), needle, depth - 1)) return true;
        }
        return false;
    }

    private static boolean isExactOpen(CharSequence value) {
        if (value == null) return false;
        String s = value.toString().trim();
        return "開".equals(s) || "开".equals(s);
    }
}
