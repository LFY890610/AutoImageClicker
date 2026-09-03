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
            if (CaptureService.isRunning()) {
                // Node recognition runs first. A short visual-fallback window is opened only
                // around real WeChat UI changes, so the screen is not continuously scanned.
                CaptureService.noteWeChatUiChanged();
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

    /** Execute exactly two rapid taps at the same point. */
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

    /** Stage 1: click the newest visible node containing “微信红包”, exactly once. */
    public static boolean clickWeChatRedPacketOnce() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = safeRoot(service);
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

            // Newest message is normally lowest. The exact greeting provides an extra bonus.
            double score = r.bottom * 20.0 + r.centerY();
            AccessibilityNodeInfo context = candidate;
            for (int i = 0; i < 3 && context != null; i++) {
                if (subtreeContains(context, "恭喜发财，大吉大利")
                        || subtreeContains(context, "恭喜发财")) {
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

    /** Stage 2: find a central exact “開/开” node and tap its center exactly twice. */
    public static boolean clickWeChatOpenButtonTwice() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = safeRoot(service);
        if (root == null) return false;

        AccessibilityNodeInfo best = findBestOpenNode(root);
        if (best == null) return false;
        Rect r = new Rect();
        best.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return clickAtTwice(r.exactCenterX(), r.exactCenterY());
    }

    public static boolean hasWeChatOpenButton() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = safeRoot(service);
        return root != null && findBestOpenNode(root) != null;
    }

    /**
     * Stage 3: detect a successful/already-finished red-packet result using nodes only.
     * This is intentionally checked only after the open-button taps, preventing chat-text matches
     * from causing an unrelated automatic BACK action.
     */
    public static boolean isRedPacketResultVisible() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = safeRoot(service);
        if (root == null) return false;

        String[] terms = {
                "红包详情",
                "手慢了",
                "红包派完了",
                "已被领完",
                "已领取",
                "已存入零钱",
                "查看领取详情"
        };
        for (String term : terms) {
            if (hasVisibleText(root, term)) return true;
        }
        return false;
    }

    /** Leave the red-packet result/popup and return toward the chat page. */
    public static boolean backOnce() {
        AutoClickAccessibilityService service = instance.get();
        return service != null && service.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    // Compatibility wrappers used by older paths.
    public static boolean clickWeChatRedPacket() {
        return clickWeChatRedPacketOnce();
    }

    public static boolean clickWeChatOpenButton() {
        return clickWeChatOpenButtonTwice();
    }

    private static AccessibilityNodeInfo safeRoot(AutoClickAccessibilityService service) {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return null;
            CharSequence pkg = root.getPackageName();
            if (pkg != null && !WECHAT_PACKAGE.contentEquals(pkg)
                    && SystemClock.uptimeMillis() - lastWeChatEventAt >= 8000L) {
                return null;
            }
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

    private static boolean performSingleClickOrCenter(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return clickAt(r.exactCenterX(), r.exactCenterY());
    }

    private static boolean hasVisibleText(AccessibilityNodeInfo root, String term) {
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(term);
            if (nodes == null) return false;
            for (AccessibilityNodeInfo node : nodes) {
                if (node != null && node.isVisibleToUser()) {
                    CharSequence t = node.getText();
                    CharSequence d = node.getContentDescription();
                    if ((t != null && t.toString().contains(term))
                            || (d != null && d.toString().contains(term))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean subtreeContains(AccessibilityNodeInfo root, String needle) {
        if (root == null || needle == null) return false;
        CharSequence t = root.getText();
        CharSequence d = root.getContentDescription();
        if ((t != null && t.toString().contains(needle))
                || (d != null && d.toString().contains(needle))) return true;
        int n = Math.min(root.getChildCount(), 20);
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null && subtreeContainsShallow(child, needle, 2)) return true;
        }
        return false;
    }

    private static boolean subtreeContainsShallow(
            AccessibilityNodeInfo node, String needle, int depth) {
        if (node == null) return false;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if ((t != null && t.toString().contains(needle))
                || (d != null && d.toString().contains(needle))) return true;
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
