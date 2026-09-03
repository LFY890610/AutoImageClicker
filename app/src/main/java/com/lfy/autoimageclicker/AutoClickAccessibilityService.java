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
    private static volatile float lastPacketClickX = -10000f;
    private static volatile float lastPacketClickY = -10000f;

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
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 4))
                .build();
        return service.dispatchGesture(gesture, null, null);
    }

    /**
     * Fastest and most accurate stage-1 path: use the exact built-in WeChat "微信红包" label.
     * Only labels inside a visible clickable ancestor are accepted, which avoids ordinary chat text
     * and emoji/sticker false positives. Exactly one click is issued.
     */
    public static boolean clickWeChatRedPacketOnce() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = safeRoot(service);
        if (root == null) return false;

        List<AccessibilityNodeInfo> labels;
        try {
            labels = root.findAccessibilityNodeInfosByText("微信红包");
        } catch (Throwable ignored) {
            return false;
        }
        if (labels == null || labels.isEmpty()) return false;

        AccessibilityNodeInfo best = null;
        Rect bestRect = null;
        double bestScore = -1e30;

        for (AccessibilityNodeInfo label : labels) {
            if (label == null || !label.isVisibleToUser()) continue;
            if (!isExact(label.getText(), "微信红包")
                    && !isExact(label.getContentDescription(), "微信红包")) continue;

            AccessibilityNodeInfo candidate = findClickableAncestor(label, 8);
            if (candidate == null) continue;

            Rect r = new Rect();
            candidate.getBoundsInScreen(r);
            if (r.isEmpty() || r.width() < 80 || r.height() < 40) continue;

            // The newest message is normally the lowest one in the current chat window.
            double score = r.bottom * 20.0 + r.centerY();
            AccessibilityNodeInfo context = candidate;
            for (int i = 0; i < 3 && context != null; i++) {
                if (subtreeContains(context, "恭喜发财")) {
                    score += 1_000_000.0;
                    break;
                }
                context = context.getParent();
            }

            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                bestRect = r;
            }
        }

        if (best == null || bestRect == null) return false;
        lastPacketClickX = bestRect.exactCenterX();
        lastPacketClickY = bestRect.exactCenterY();
        if (best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return clickAt(lastPacketClickX, lastPacketClickY);
    }

    public static float getLastPacketClickX() {
        return lastPacketClickX;
    }

    public static float getLastPacketClickY() {
        return lastPacketClickY;
    }

    /** Exact central 開/开 node, exactly one click. */
    public static boolean clickWeChatOpenButtonOnce() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = safeRoot(service);
        if (root == null) return false;
        AccessibilityNodeInfo best = findBestOpenNode(root);
        if (best == null) return false;
        Rect r = new Rect();
        best.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        if (best.isClickable() && best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return clickAt(r.exactCenterX(), r.exactCenterY());
    }

    /**
     * Some WeChat builds draw the 開 button as an unlabeled custom view. While WAIT_OPEN is active,
     * pick the small clickable control nearest the center of the red-packet popup and click once.
     */
    public static boolean clickCentralPopupActionOnce() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = safeRoot(service);
        if (root == null) return false;

        Rect rr = new Rect();
        root.getBoundsInScreen(rr);
        if (rr.isEmpty()) return false;

        Candidate best = new Candidate();
        findCentralClickable(root, rr, 0, best);
        if (best.node == null || best.rect == null) return false;

        if (best.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return clickAt(best.rect.exactCenterX(), best.rect.exactCenterY());
    }

    /** Last one-tap fallback used only after a verified red packet has already been opened. */
    public static boolean clickExpectedOpenCenterOnce() {
        AutoClickAccessibilityService service = instance.get();
        if (service == null) return false;
        AccessibilityNodeInfo root = safeRoot(service);
        if (root == null) return false;
        Rect r = new Rect();
        root.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        float x = r.exactCenterX();
        float y = r.top + r.height() * 0.53f;
        return clickAt(x, y);
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

    private static void findCentralClickable(
            AccessibilityNodeInfo node, Rect rootRect, int depth, Candidate best) {
        if (node == null || depth > 16 || best.visited > 320) return;
        best.visited++;

        try {
            if (node.isVisibleToUser() && node.isClickable()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                if (!r.isEmpty()) {
                    float rw = rootRect.width();
                    float rh = rootRect.height();
                    float dx = Math.abs(r.exactCenterX() - rootRect.exactCenterX());
                    float dy = Math.abs(r.exactCenterY() - rootRect.exactCenterY());
                    float minSide = Math.min(r.width(), r.height());
                    float maxSide = Math.max(r.width(), r.height());

                    boolean reasonableSize = minSide >= 34f
                            && r.width() <= rw * 0.55f
                            && r.height() <= rh * 0.32f;
                    boolean central = dx <= rw * 0.23f && dy <= rh * 0.23f;
                    CharSequence t = node.getText();
                    CharSequence d = node.getContentDescription();
                    String text = (t == null ? "" : t.toString()) + " "
                            + (d == null ? "" : d.toString());
                    boolean notClose = !text.contains("关闭") && !text.contains("返回")
                            && !text.equalsIgnoreCase("close");

                    if (reasonableSize && central && notClose) {
                        double roundPenalty = maxSide <= 0 ? 2.0
                                : Math.abs(Math.log(Math.max(0.15, r.width() / (double) r.height())));
                        double sizePenalty = Math.abs(minSide - Math.min(rw, rh) * 0.12) * 0.10;
                        double score = dx * dx + dy * dy + roundPenalty * 5000.0 + sizePenalty;
                        if (score < best.score) {
                            best.score = score;
                            best.node = node;
                            best.rect = r;
                        }
                    }
                }
            }

            int count = Math.min(node.getChildCount(), 30);
            for (int i = 0; i < count; i++) {
                findCentralClickable(node.getChild(i), rootRect, depth + 1, best);
            }
        } catch (Throwable ignored) {}
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

    private static boolean isExact(CharSequence value, String expected) {
        return value != null && expected.equals(value.toString().trim());
    }

    private static boolean isExactOpen(CharSequence value) {
        if (value == null) return false;
        String s = value.toString().trim();
        return "開".equals(s) || "开".equals(s);
    }

    private static final class Candidate {
        AccessibilityNodeInfo node;
        Rect rect;
        double score = Double.POSITIVE_INFINITY;
        int visited = 0;
    }
}
