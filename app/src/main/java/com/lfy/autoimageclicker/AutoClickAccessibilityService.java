package com.lfy.autoimageclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.ref.WeakReference;
import java.util.List;

public class AutoClickAccessibilityService extends AccessibilityService {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String PREFS = "red_packet_automation";
    private static final String KEY_ENABLED = "enabled";

    private static final int WAIT_PACKET = 0;
    private static final int WAIT_OPEN = 1;
    private static final int WAIT_RESULT = 2;
    private static final int WAIT_CLEAR = 3;

    private static volatile WeakReference<AutoClickAccessibilityService> instance =
            new WeakReference<>(null);
    private static volatile long lastWeChatEventAt = 0L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean processPending = false;
    private int state = WAIT_PACKET;
    private long stateSince = 0L;
    private long lastActionAt = 0L;
    private int lastPacketNodeHash = 0;
    private long lastPacketAt = 0L;
    private Rect lastPacketRect = null;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = new WeakReference<>(this);
        resetState();
        if (isAutomationEnabled(this)) requestProcess(0L);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isAutomationEnabled(this)) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !WECHAT_PACKAGE.contentEquals(pkg)) return;

        lastWeChatEventAt = SystemClock.uptimeMillis();
        requestProcess(0L);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacksAndMessages(null);
        instance = new WeakReference<>(null);
        return super.onUnbind(intent);
    }

    public static boolean isConnected() {
        return instance.get() != null;
    }

    public static boolean isAutomationEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static void setAutomationEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply();
        AutoClickAccessibilityService service = instance.get();
        if (service != null) service.onAutomationFlagChanged(enabled);
    }

    private void onAutomationFlagChanged(boolean enabled) {
        handler.removeCallbacksAndMessages(null);
        processPending = false;
        resetState();
        if (enabled) requestProcess(0L);
    }

    private void resetState() {
        state = WAIT_PACKET;
        stateSince = SystemClock.uptimeMillis();
        lastActionAt = 0L;
    }

    private void requestProcess(long delayMs) {
        if (!isAutomationEnabled(this)) return;
        if (delayMs <= 0L) {
            if (processPending) return;
            processPending = true;
            handler.post(() -> {
                processPending = false;
                processCurrentUi();
            });
        } else {
            handler.postDelayed(this::processCurrentUi, delayMs);
        }
    }

    private void processCurrentUi() {
        if (!isAutomationEnabled(this)) return;
        AccessibilityNodeInfo root = safeRoot();
        if (root == null) return;

        long now = SystemClock.uptimeMillis();

        if (state == WAIT_PACKET) {
            PacketCandidate packet = findBestRedPacket(root, now);
            if (packet != null && now - lastActionAt >= 35L && clickPacket(packet)) {
                lastActionAt = now;
                lastPacketNodeHash = packet.nodeHash;
                lastPacketAt = now;
                lastPacketRect = new Rect(packet.rect);
                state = WAIT_OPEN;
                stateSince = now;
                scheduleOpenChecks();
            }
            return;
        }

        if (state == WAIT_OPEN) {
            if (isRedPacketResultVisible(root)) {
                backOnce();
                enterWaitClear(now);
                return;
            }

            long elapsed = now - stateSince;
            if (elapsed >= 25L && clickExactOpenOnce(root)) {
                enterWaitResult(now);
                return;
            }
            if (elapsed >= 55L && clickCentralPopupActionOnce(root)) {
                enterWaitResult(now);
                return;
            }

            // Pure-accessibility coordinate fallback. It is used only after a verified red packet
            // was already clicked, never during normal chat scanning, and is issued only once.
            if (elapsed >= 220L && elapsed < 420L && clickExpectedOpenCenterOnce(root)) {
                enterWaitResult(now);
                return;
            }

            if (elapsed > 1800L) {
                backOnce();
                enterWaitClear(now);
            }
            return;
        }

        if (state == WAIT_RESULT) {
            long elapsed = now - stateSince;
            if (elapsed >= 70L && isRedPacketResultVisible(root)) {
                backOnce();
                enterWaitClear(now);
                return;
            }

            // Some WeChat versions do not expose the result-page text. Return once after enough
            // time for the claim action to complete; no polling or screen capture is used.
            if (elapsed >= 1050L) {
                backOnce();
                enterWaitClear(now);
            }
            return;
        }

        if (state == WAIT_CLEAR) {
            if (now - stateSince >= 140L) {
                state = WAIT_PACKET;
                stateSince = now;
                requestProcess(0L);
            }
        }
    }

    private void scheduleOpenChecks() {
        long[] delays = {25L, 55L, 90L, 140L, 220L, 340L, 520L, 800L, 1200L, 1750L};
        for (long delay : delays) requestProcess(delay);
    }

    private void enterWaitResult(long now) {
        lastActionAt = now;
        state = WAIT_RESULT;
        stateSince = now;
        long[] delays = {70L, 130L, 220L, 360L, 560L, 820L, 1080L};
        for (long delay : delays) requestProcess(delay);
    }

    private void enterWaitClear(long now) {
        lastActionAt = now;
        state = WAIT_CLEAR;
        stateSince = now;
        requestProcess(160L);
    }

    private AccessibilityNodeInfo safeRoot() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            CharSequence pkg = root.getPackageName();
            if (pkg != null && WECHAT_PACKAGE.contentEquals(pkg)) return root;
            if (SystemClock.uptimeMillis() - lastWeChatEventAt < 1200L) return root;
        } catch (Throwable ignored) {}
        return null;
    }

    private PacketCandidate findBestRedPacket(AccessibilityNodeInfo root, long now) {
        List<AccessibilityNodeInfo> labels;
        try {
            labels = root.findAccessibilityNodeInfosByText("微信红包");
        } catch (Throwable ignored) {
            return null;
        }
        if (labels == null || labels.isEmpty()) return null;

        PacketCandidate best = null;
        double bestScore = -1e30;
        for (AccessibilityNodeInfo label : labels) {
            if (label == null || !label.isVisibleToUser()) continue;
            if (!isExact(label.getText(), "微信红包")
                    && !isExact(label.getContentDescription(), "微信红包")) continue;

            AccessibilityNodeInfo candidate = findClickableAncestor(label, 8);
            if (candidate == null) continue;
            if (subtreeContainsAny(candidate, new String[]{
                    "已领取", "已领完", "已被领完", "已过期", "手慢了"
            }, 3)) continue;

            Rect r = new Rect();
            candidate.getBoundsInScreen(r);
            if (r.isEmpty() || r.width() < 80 || r.height() < 40) continue;

            int nodeHash = candidate.hashCode();
            if (nodeHash == lastPacketNodeHash && now - lastPacketAt < 60000L) continue;
            if (lastPacketRect != null && now - lastPacketAt < 1800L
                    && closeRects(r, lastPacketRect, 24)) continue;

            double score = r.bottom * 20.0 + r.centerY();
            AccessibilityNodeInfo context = candidate;
            for (int i = 0; i < 3 && context != null; i++) {
                if (subtreeContainsAny(context,
                        new String[]{"恭喜发财", "大吉大利"}, 2)) {
                    score += 1_000_000.0;
                    break;
                }
                context = context.getParent();
            }

            if (score > bestScore) {
                bestScore = score;
                best = new PacketCandidate(candidate, r, nodeHash);
            }
        }
        return best;
    }

    private boolean clickPacket(PacketCandidate packet) {
        if (packet == null || packet.node == null) return false;
        if (packet.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return clickAt(packet.rect.exactCenterX(), packet.rect.exactCenterY());
    }

    private boolean clickExactOpenOnce(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = findBestOpenNode(root);
        if (best == null) return false;
        Rect r = new Rect();
        best.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        if (best.isClickable() && best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return clickAt(r.exactCenterX(), r.exactCenterY());
    }

    private boolean clickCentralPopupActionOnce(AccessibilityNodeInfo root) {
        Rect rootRect = new Rect();
        root.getBoundsInScreen(rootRect);
        if (rootRect.isEmpty()) return false;
        Candidate best = new Candidate();
        findCentralClickable(root, rootRect, 0, best);
        if (best.node == null || best.rect == null) return false;
        if (best.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return clickAt(best.rect.exactCenterX(), best.rect.exactCenterY());
    }

    private boolean clickExpectedOpenCenterOnce(AccessibilityNodeInfo root) {
        Rect r = new Rect();
        root.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return clickAt(r.exactCenterX(), r.top + r.height() * 0.53f);
    }

    private boolean isRedPacketResultVisible(AccessibilityNodeInfo root) {
        return subtreeContainsAny(root, new String[]{
                "红包详情", "查看领取详情", "已领取", "手慢了", "来晚了",
                "红包派完了", "已被领完", "已领完", "已存入零钱", "已过期"
        }, 5);
    }

    private boolean backOnce() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private boolean clickAt(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 4))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private static AccessibilityNodeInfo findBestOpenNode(AccessibilityNodeInfo root) {
        Rect rootRect = new Rect();
        root.getBoundsInScreen(rootRect);
        if (rootRect.isEmpty()) return null;

        AccessibilityNodeInfo best = null;
        double bestScore = -1e30;
        String[] terms = {"開", "开"};
        for (String term : terms) {
            List<AccessibilityNodeInfo> nodes;
            try {
                nodes = root.findAccessibilityNodeInfosByText(term);
            } catch (Throwable ignored) {
                continue;
            }
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                if (!isExactOpen(node.getText()) && !isExactOpen(node.getContentDescription())) continue;

                AccessibilityNodeInfo candidate = findClickableAncestor(node, 5);
                if (candidate == null) candidate = node;
                Rect r = new Rect();
                candidate.getBoundsInScreen(r);
                if (r.isEmpty()) continue;

                float dx = r.exactCenterX() - rootRect.exactCenterX();
                float dy = r.exactCenterY() - rootRect.exactCenterY();
                if (Math.abs(dx) > rootRect.width() * 0.30f
                        || Math.abs(dy) > rootRect.height() * 0.30f) continue;
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
        if (node == null || depth > 15 || best.visited > 280) return;
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

                    CharSequence t = node.getText();
                    CharSequence d = node.getContentDescription();
                    String text = ((t == null ? "" : t.toString()) + " "
                            + (d == null ? "" : d.toString())).trim();

                    boolean sizeOk = minSide >= 32f
                            && r.width() <= rw * 0.50f
                            && r.height() <= rh * 0.28f;
                    boolean central = dx <= rw * 0.22f && dy <= rh * 0.22f;
                    boolean notClose = !text.contains("关闭") && !text.contains("返回")
                            && !text.equalsIgnoreCase("close");

                    if (sizeOk && central && notClose) {
                        double shapePenalty = maxSide <= 0 ? 3.0
                                : Math.abs(Math.log(Math.max(0.15,
                                r.width() / (double) r.height())));
                        double score = dx * dx + dy * dy + shapePenalty * 4500.0;
                        if (score < best.score) {
                            best.score = score;
                            best.node = node;
                            best.rect = r;
                        }
                    }
                }
            }

            int count = Math.min(node.getChildCount(), 28);
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

    private static boolean subtreeContainsAny(
            AccessibilityNodeInfo node, String[] terms, int depth) {
        if (node == null || terms == null) return false;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        String text = (t == null ? "" : t.toString()) + " "
                + (d == null ? "" : d.toString());
        for (String term : terms) {
            if (term != null && text.contains(term)) return true;
        }
        if (depth <= 0) return false;
        int count = Math.min(node.getChildCount(), 24);
        for (int i = 0; i < count; i++) {
            if (subtreeContainsAny(node.getChild(i), terms, depth - 1)) return true;
        }
        return false;
    }

    private static boolean closeRects(Rect a, Rect b, int tolerance) {
        return Math.abs(a.centerX() - b.centerX()) <= tolerance
                && Math.abs(a.centerY() - b.centerY()) <= tolerance;
    }

    private static boolean isExact(CharSequence value, String expected) {
        return value != null && expected.equals(value.toString().trim());
    }

    private static boolean isExactOpen(CharSequence value) {
        if (value == null) return false;
        String s = value.toString().trim();
        return "開".equals(s) || "开".equals(s);
    }

    private static final class PacketCandidate {
        final AccessibilityNodeInfo node;
        final Rect rect;
        final int nodeHash;

        PacketCandidate(AccessibilityNodeInfo node, Rect rect, int nodeHash) {
            this.node = node;
            this.rect = rect;
            this.nodeHash = nodeHash;
        }
    }

    private static final class Candidate {
        AccessibilityNodeInfo node;
        Rect rect;
        double score = Double.POSITIVE_INFINITY;
        int visited = 0;
    }
}
