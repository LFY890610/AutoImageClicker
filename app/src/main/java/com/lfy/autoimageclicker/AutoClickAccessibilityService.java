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
import java.util.Locale;

public class AutoClickAccessibilityService extends AccessibilityService {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String PREFS = "red_packet_automation";
    private static final String KEY_ENABLED = "enabled";

    private static final int WAIT_PACKET = 0;
    private static final int WAIT_OPEN = 1;
    private static final int WAIT_RESULT = 2;
    private static final int WAIT_CLEAR = 3;

    private static final String[] FINISHED_TERMS = {
            "红包详情", "查看领取详情", "已领取", "手慢了", "来晚了",
            "红包派完了", "已被领完", "已领完", "已存入零钱", "已过期"
    };

    private static volatile WeakReference<AutoClickAccessibilityService> instance =
            new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int state = WAIT_PACKET;
    private long stateSince = 0L;
    private long lastActionAt = 0L;
    private int lastPacketNodeHash = 0;
    private long lastPacketAt = 0L;
    private Rect lastPacketRect = null;
    private String activeWeChatPackage = WECHAT_PACKAGE;
    private int sessionToken = 0;
    private boolean fallbackPending = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = new WeakReference<>(this);
        resetState();
        if (isAutomationEnabled(this)) requestFallback(0L, sessionToken);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isAutomationEnabled(this)) return;
        if (!isWeChatEvent(event)) return;

        CharSequence pkg = event.getPackageName();
        if (pkg != null) activeWeChatPackage = pkg.toString();

        long now = SystemClock.uptimeMillis();
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
        } catch (Throwable ignored) {}

        // Fast path: use the node that actually changed. Most successful claims never need a
        // whole-window scan, which reduces both latency and CPU use.
        if (source != null && processEventSource(source, now)) return;

        // Window-state events often carry a coarse source. Do one immediate fallback scan only
        // when the event itself was not enough.
        requestFallback(0L, sessionToken);
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
        fallbackPending = false;
        sessionToken++;
        resetState();
        if (enabled) requestFallback(0L, sessionToken);
    }

    private void resetState() {
        state = WAIT_PACKET;
        stateSince = SystemClock.uptimeMillis();
        lastActionAt = 0L;
    }

    private boolean isWeChatEvent(AccessibilityEvent event) {
        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();
        if (isWeChatPackageName(pkg)) return true;

        // Some vendor clone implementations repackage the app but preserve WeChat activity/view
        // class names. This keeps those clones working without processing every app's UI tree.
        CharSequence clsCs = event.getClassName();
        String cls = clsCs == null ? "" : clsCs.toString();
        return cls.startsWith("com.tencent.mm.");
    }

    private static boolean isWeChatPackageName(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return WECHAT_PACKAGE.equals(p)
                || p.startsWith(WECHAT_PACKAGE + ".")
                || p.contains("tencent.mm")
                || p.contains("wechat")
                || p.contains("weixin");
    }

    private boolean processEventSource(AccessibilityNodeInfo source, long now) {
        if (state == WAIT_CLEAR && now - stateSince >= 80L) {
            state = WAIT_PACKET;
            stateSince = now;
        }

        if (state == WAIT_PACKET) {
            PacketCandidate packet = findBestRedPacket(source, now, 120);
            if (packet != null && clickPacket(packet)) {
                onPacketClicked(packet, now);
                return true;
            }
            return false;
        }

        if (state == WAIT_OPEN) {
            if (subtreeContainsAny(source, FINISHED_TERMS, 4)) {
                backOnce();
                enterWaitClear(now);
                return true;
            }

            if (clickExactOpenOnce(source)) {
                enterWaitResult(now);
                return true;
            }

            AccessibilityNodeInfo root = safeRoot();
            Rect rootRect = getBounds(root);
            if (rootRect != null && clickCentralPopupActionOnce(source, rootRect, 100)) {
                enterWaitResult(now);
                return true;
            }
            return false;
        }

        if (state == WAIT_RESULT) {
            if (subtreeContainsAny(source, FINISHED_TERMS, 4)) {
                backOnce();
                enterWaitClear(now);
                return true;
            }
        }
        return false;
    }

    private void requestFallback(long delayMs, int token) {
        if (!isAutomationEnabled(this)) return;
        if (delayMs <= 0L) {
            if (fallbackPending) return;
            fallbackPending = true;
            handler.post(() -> {
                fallbackPending = false;
                if (token == sessionToken) processCurrentUi();
            });
        } else {
            handler.postDelayed(() -> {
                if (token == sessionToken) processCurrentUi();
            }, delayMs);
        }
    }

    private void processCurrentUi() {
        if (!isAutomationEnabled(this)) return;
        AccessibilityNodeInfo root = safeRoot();
        if (root == null) return;
        long now = SystemClock.uptimeMillis();

        if (state == WAIT_PACKET) {
            PacketCandidate packet = findBestRedPacket(root, now, 360);
            if (packet != null && clickPacket(packet)) onPacketClicked(packet, now);
            return;
        }

        if (state == WAIT_OPEN) {
            if (isRedPacketResultVisible(root)) {
                backOnce();
                enterWaitClear(now);
                return;
            }

            // No artificial 25/55 ms gate: if WeChat has already created the control, click it now.
            if (clickExactOpenOnce(root)) {
                enterWaitResult(now);
                return;
            }

            Rect rootRect = getBounds(root);
            if (rootRect != null && clickCentralPopupActionOnce(root, rootRect, 260)) {
                enterWaitResult(now);
                return;
            }

            long elapsed = now - stateSince;
            // Last-resort one-shot coordinate tap, only after a verified packet click and only once.
            if (elapsed >= 150L && elapsed < 360L && clickExpectedOpenCenterOnce(root)) {
                enterWaitResult(now);
                return;
            }

            if (elapsed > 1500L) {
                backOnce();
                enterWaitClear(now);
            }
            return;
        }

        if (state == WAIT_RESULT) {
            long elapsed = now - stateSince;
            if (isRedPacketResultVisible(root)) {
                backOnce();
                enterWaitClear(now);
                return;
            }
            if (elapsed >= 900L) {
                backOnce();
                enterWaitClear(now);
            }
            return;
        }

        if (state == WAIT_CLEAR && now - stateSince >= 100L) {
            state = WAIT_PACKET;
            stateSince = now;
        }
    }

    private void onPacketClicked(PacketCandidate packet, long now) {
        lastActionAt = now;
        lastPacketNodeHash = packet.nodeHash;
        lastPacketAt = now;
        lastPacketRect = new Rect(packet.rect);
        state = WAIT_OPEN;
        stateSince = now;
        int token = ++sessionToken;

        // Events remain primary. These sparse checks only cover WeChat versions that fail to emit a
        // useful accessibility event while the packet animation is opening.
        long[] delays = {35L, 80L, 150L, 260L, 450L, 800L, 1450L};
        for (long delay : delays) requestFallback(delay, token);
    }

    private void enterWaitResult(long now) {
        lastActionAt = now;
        state = WAIT_RESULT;
        stateSince = now;
        int token = ++sessionToken;
        long[] delays = {60L, 140L, 300L, 560L, 920L};
        for (long delay : delays) requestFallback(delay, token);
    }

    private void enterWaitClear(long now) {
        lastActionAt = now;
        state = WAIT_CLEAR;
        stateSince = now;
        int token = ++sessionToken;
        requestFallback(120L, token);
    }

    private AccessibilityNodeInfo safeRoot() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            CharSequence pkgCs = root.getPackageName();
            String pkg = pkgCs == null ? "" : pkgCs.toString();
            if (isWeChatPackageName(pkg)) {
                activeWeChatPackage = pkg;
                return root;
            }
            if (!activeWeChatPackage.isEmpty() && activeWeChatPackage.equals(pkg)) return root;

            CharSequence clsCs = root.getClassName();
            String cls = clsCs == null ? "" : clsCs.toString();
            if (cls.startsWith("com.tencent.mm.")) {
                activeWeChatPackage = pkg;
                return root;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private PacketCandidate findBestRedPacket(
            AccessibilityNodeInfo scope, long now, int maxVisited) {
        if (scope == null) return null;
        List<AccessibilityNodeInfo> labels;
        try {
            labels = scope.findAccessibilityNodeInfosByText("微信红包");
        } catch (Throwable ignored) {
            return null;
        }
        if (labels == null || labels.isEmpty()) return null;

        PacketCandidate best = null;
        double bestScore = -1e30;
        int visited = 0;
        for (AccessibilityNodeInfo label : labels) {
            if (++visited > maxVisited) break;
            if (label == null || !label.isVisibleToUser()) continue;
            if (!isExact(label.getText(), "微信红包")
                    && !isExact(label.getContentDescription(), "微信红包")) continue;

            AccessibilityNodeInfo candidate = findClickableAncestor(label, 8);
            if (candidate == null) continue;
            if (subtreeContainsAny(candidate,
                    new String[]{"已领取", "已领完", "已被领完", "已过期", "手慢了"}, 3)) {
                continue;
            }

            Rect r = getBounds(candidate);
            if (r == null || r.width() < 80 || r.height() < 40) continue;

            int nodeHash = candidate.hashCode();
            if (nodeHash == lastPacketNodeHash && now - lastPacketAt < 60000L) continue;
            if (lastPacketRect != null && now - lastPacketAt < 5000L
                    && closeRects(r, lastPacketRect, 36)) continue;

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

    private boolean clickExactOpenOnce(AccessibilityNodeInfo scope) {
        AccessibilityNodeInfo best = findBestOpenNode(scope);
        if (best == null) return false;
        Rect r = getBounds(best);
        if (r == null) return false;
        if (best.isClickable() && best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return clickAt(r.exactCenterX(), r.exactCenterY());
    }

    private boolean clickCentralPopupActionOnce(
            AccessibilityNodeInfo scope, Rect rootRect, int maxVisited) {
        if (scope == null || rootRect == null || rootRect.isEmpty()) return false;
        Candidate best = new Candidate(maxVisited);
        findCentralClickable(scope, rootRect, 0, best);
        if (best.node == null || best.rect == null) return false;
        if (best.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        return clickAt(best.rect.exactCenterX(), best.rect.exactCenterY());
    }

    private boolean clickExpectedOpenCenterOnce(AccessibilityNodeInfo root) {
        Rect r = getBounds(root);
        if (r == null) return false;
        return clickAt(r.exactCenterX(), r.top + r.height() * 0.53f);
    }

    private boolean isRedPacketResultVisible(AccessibilityNodeInfo root) {
        return subtreeContainsAny(root, FINISHED_TERMS, 5);
    }

    private boolean backOnce() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private boolean clickAt(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 3))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private static AccessibilityNodeInfo findBestOpenNode(AccessibilityNodeInfo scope) {
        if (scope == null) return null;
        Rect rootRect = getBoundsStatic(scope);
        if (rootRect == null) return null;

        AccessibilityNodeInfo best = null;
        double bestScore = -1e30;
        String[] terms = {"開", "开"};
        for (String term : terms) {
            List<AccessibilityNodeInfo> nodes;
            try {
                nodes = scope.findAccessibilityNodeInfosByText(term);
            } catch (Throwable ignored) {
                continue;
            }
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                if (!isExactOpen(node.getText()) && !isExactOpen(node.getContentDescription())) continue;

                AccessibilityNodeInfo candidate = findClickableAncestor(node, 5);
                if (candidate == null) candidate = node;
                Rect r = getBoundsStatic(candidate);
                if (r == null) continue;

                double score = r.bottom * 0.001;
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
        if (node == null || depth > 14 || best.visited >= best.maxVisited) return;
        best.visited++;
        try {
            if (node.isVisibleToUser() && node.isClickable()) {
                Rect r = getBoundsStatic(node);
                if (r != null) {
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

                    boolean sizeOk = minSide >= 30f
                            && r.width() <= rw * 0.52f
                            && r.height() <= rh * 0.30f;
                    boolean central = dx <= rw * 0.23f && dy <= rh * 0.23f;
                    boolean notClose = !text.contains("关闭") && !text.contains("返回")
                            && !text.equalsIgnoreCase("close");

                    if (sizeOk && central && notClose) {
                        double shapePenalty = maxSide <= 0 ? 3.0
                                : Math.abs(Math.log(Math.max(0.15,
                                r.width() / (double) r.height())));
                        double score = dx * dx + dy * dy + shapePenalty * 4200.0;
                        if (score < best.score) {
                            best.score = score;
                            best.node = node;
                            best.rect = r;
                        }
                    }
                }
            }

            int count = Math.min(node.getChildCount(), 24);
            for (int i = 0; i < count && best.visited < best.maxVisited; i++) {
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
        int count = Math.min(node.getChildCount(), 20);
        for (int i = 0; i < count; i++) {
            if (subtreeContainsAny(node.getChild(i), terms, depth - 1)) return true;
        }
        return false;
    }

    private Rect getBounds(AccessibilityNodeInfo node) {
        return getBoundsStatic(node);
    }

    private static Rect getBoundsStatic(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            return r.isEmpty() ? null : r;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean closeRects(Rect a, Rect b, int tolerance) {
        return a != null && b != null
                && Math.abs(a.centerX() - b.centerX()) <= tolerance
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
        final int maxVisited;

        Candidate(int maxVisited) {
            this.maxVisited = maxVisited;
        }
    }
}
