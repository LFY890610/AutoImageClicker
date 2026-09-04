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
import java.util.ArrayList;
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
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!isAutomationEnabled(AutoClickAccessibilityService.this)) return;
            processCurrentUi();
            handler.postDelayed(this, 220L);
        }
    };

    private int state = WAIT_PACKET;
    private long stateSince = 0L;
    private long lastActionAt = 0L;
    private long lastPacketAt = 0L;
    private Rect lastPacketRect = null;
    private String activeWeChatPackage = "";
    private int sequence = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = new WeakReference<>(this);
        activeWeChatPackage = "";
        resetState();
        restartHeartbeat();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isAutomationEnabled(this)) return;

        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String cls = event.getClassName() == null ? "" : event.getClassName().toString();

        // Absolute safety rule: our own UI must never be treated as WeChat or a clone.
        if (isSelfPackage(pkg)) return;
        if (!isWeChatIdentity(pkg, cls)) return;

        if (!pkg.isEmpty()) activeWeChatPackage = pkg;

        long now = SystemClock.uptimeMillis();
        AccessibilityNodeInfo source = null;
        try { source = event.getSource(); } catch (Throwable ignored) {}

        if (source != null && processScope(source, now)) return;
        processCurrentUi();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacksAndMessages(null);
        activeWeChatPackage = "";
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

    public static void requestImmediateCheck() {
        AutoClickAccessibilityService service = instance.get();
        if (service != null && isAutomationEnabled(service)) {
            service.handler.post(service::processCurrentUi);
        }
    }

    private void onAutomationFlagChanged(boolean enabled) {
        handler.removeCallbacksAndMessages(null);
        sequence++;
        resetState();
        if (!enabled) {
            activeWeChatPackage = "";
            return;
        }
        handler.post(this::processCurrentUi);
        restartHeartbeat();
    }

    private void restartHeartbeat() {
        handler.removeCallbacks(heartbeat);
        if (isAutomationEnabled(this)) handler.postDelayed(heartbeat, 120L);
    }

    private void resetState() {
        state = WAIT_PACKET;
        stateSince = SystemClock.uptimeMillis();
        lastActionAt = 0L;
    }

    private boolean isSelfPackage(String pkg) {
        return pkg != null && pkg.equals(getPackageName());
    }

    private static boolean isWeChatIdentity(String pkg, String cls) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        String c = cls == null ? "" : cls;

        // Main WeChat and most system dual-app implementations keep the WeChat package identity.
        if (WECHAT_PACKAGE.equals(p)
                || p.startsWith(WECHAT_PACKAGE + ".")
                || p.contains("tencent.mm")) return true;

        // Some clones repackage the APK but keep original WeChat activity/view class names.
        return c.startsWith("com.tencent.mm.");
    }

    private boolean processScope(AccessibilityNodeInfo scope, long now) {
        if (scope == null || !canActOnCurrentWindow()) return false;

        if (state == WAIT_CLEAR && now - stateSince >= 100L) {
            state = WAIT_PACKET;
            stateSince = now;
        }

        if (state == WAIT_PACKET) {
            PacketCandidate packet = findBestRedPacket(scope, now);
            if (packet != null && clickPacket(packet)) {
                onPacketClicked(packet, now);
                return true;
            }
            return false;
        }

        if (state == WAIT_OPEN) {
            if (subtreeContainsAny(scope, FINISHED_TERMS, 5)) {
                backOnce();
                enterWaitClear(now);
                return true;
            }

            if (clickExactOpenOnce(scope)) {
                enterWaitResult(now);
                return true;
            }

            AccessibilityNodeInfo root = safeRoot();
            Rect rootRect = bounds(root);
            if (rootRect != null && clickCentralPopupActionOnce(scope, rootRect, 180)) {
                enterWaitResult(now);
                return true;
            }
            return false;
        }

        if (state == WAIT_RESULT && subtreeContainsAny(scope, FINISHED_TERMS, 5)) {
            backOnce();
            enterWaitClear(now);
            return true;
        }
        return false;
    }

    private void processCurrentUi() {
        if (!isAutomationEnabled(this)) return;
        AccessibilityNodeInfo root = safeRoot();
        if (root == null) return;
        long now = SystemClock.uptimeMillis();

        if (processScope(root, now)) return;

        if (state == WAIT_OPEN) {
            long elapsed = now - stateSince;
            if (elapsed >= 130L && elapsed < 360L && clickExpectedOpenCenterOnce(root)) {
                enterWaitResult(now);
                return;
            }
            if (elapsed > 1700L) {
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
            if (elapsed >= 850L) {
                backOnce();
                enterWaitClear(now);
            }
            return;
        }

        if (state == WAIT_CLEAR && now - stateSince >= 120L) {
            state = WAIT_PACKET;
            stateSince = now;
        }
    }

    private void onPacketClicked(PacketCandidate packet, long now) {
        lastActionAt = now;
        lastPacketAt = now;
        lastPacketRect = new Rect(packet.rect);
        state = WAIT_OPEN;
        stateSince = now;
        int token = ++sequence;

        long[] delays = {20L, 45L, 80L, 130L, 210L, 340L, 560L, 900L, 1500L};
        for (long d : delays) {
            handler.postDelayed(() -> {
                if (token == sequence && state == WAIT_OPEN) processCurrentUi();
            }, d);
        }
    }

    private void enterWaitResult(long now) {
        lastActionAt = now;
        state = WAIT_RESULT;
        stateSince = now;
        int token = ++sequence;
        long[] delays = {45L, 90L, 160L, 280L, 480L, 760L, 900L};
        for (long d : delays) {
            handler.postDelayed(() -> {
                if (token == sequence && state == WAIT_RESULT) processCurrentUi();
            }, d);
        }
    }

    private void enterWaitClear(long now) {
        lastActionAt = now;
        state = WAIT_CLEAR;
        stateSince = now;
        sequence++;
    }

    private AccessibilityNodeInfo safeRoot() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;

            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            String cls = root.getClassName() == null ? "" : root.getClassName().toString();

            // Never scan or act on our own UI, Settings, launcher, lock screen, or arbitrary apps.
            if (isSelfPackage(pkg)) return null;

            if (isWeChatIdentity(pkg, cls)) {
                if (!pkg.isEmpty()) activeWeChatPackage = pkg;
                return root;
            }

            // A remembered clone package is accepted only if it was learned earlier from a genuine
            // WeChat package/class identity. Text such as “微信红包” alone can never teach a package.
            if (!activeWeChatPackage.isEmpty() && activeWeChatPackage.equals(pkg)) return root;
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean canActOnCurrentWindow() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            String cls = root.getClassName() == null ? "" : root.getClassName().toString();
            if (isSelfPackage(pkg)) return false;
            if (isWeChatIdentity(pkg, cls)) return true;
            return !activeWeChatPackage.isEmpty() && activeWeChatPackage.equals(pkg);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private PacketCandidate findBestRedPacket(AccessibilityNodeInfo scope, long now) {
        if (scope == null) return null;
        List<AccessibilityNodeInfo> labels = new ArrayList<>();
        try {
            List<AccessibilityNodeInfo> direct = scope.findAccessibilityNodeInfosByText("微信红包");
            if (direct != null) labels.addAll(direct);
        } catch (Throwable ignored) {}

        if (labels.isEmpty()) collectPacketLabels(scope, labels, 0, new int[]{0});
        if (labels.isEmpty()) return null;

        PacketCandidate best = null;
        double bestScore = -1e30;
        for (AccessibilityNodeInfo label : labels) {
            if (label == null || !label.isVisibleToUser()) continue;
            if (!containsPacketLabel(label)) continue;

            AccessibilityNodeInfo candidate = choosePacketContainer(label);
            if (candidate == null) candidate = label;
            if (subtreeContainsAny(candidate,
                    new String[]{"已领取", "已领完", "已被领完", "已过期", "手慢了"}, 3)) {
                continue;
            }

            Rect r = bounds(candidate);
            if (r == null) r = bounds(label);
            if (r == null || r.width() < 35 || r.height() < 20) continue;

            if (lastPacketRect != null && now - lastPacketAt < 4500L
                    && closeRects(r, lastPacketRect, 42)) continue;

            double score = r.bottom * 20.0 + r.centerY();
            if (subtreeContainsAny(candidate,
                    new String[]{"恭喜发财", "大吉大利"}, 3)) score += 1_000_000.0;
            if (candidate.isClickable()) score += 100_000.0;

            if (score > bestScore) {
                bestScore = score;
                best = new PacketCandidate(candidate, r);
            }
        }
        return best;
    }

    private static void collectPacketLabels(
            AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth, int[] visited) {
        if (node == null || depth > 12 || visited[0]++ > 420 || out.size() >= 12) return;
        try {
            if (node.isVisibleToUser() && containsPacketLabel(node)) out.add(node);
            int n = Math.min(node.getChildCount(), 28);
            for (int i = 0; i < n; i++) {
                collectPacketLabels(node.getChild(i), out, depth + 1, visited);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean containsPacketLabel(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String t = node.getText() == null ? "" : node.getText().toString().trim();
        String d = node.getContentDescription() == null
                ? "" : node.getContentDescription().toString().trim();
        return t.equals("微信红包") || d.equals("微信红包")
                || t.contains("微信红包") || d.contains("微信红包");
    }

    private static AccessibilityNodeInfo choosePacketContainer(AccessibilityNodeInfo label) {
        AccessibilityNodeInfo cur = label;
        AccessibilityNodeInfo reasonable = null;
        for (int i = 0; cur != null && i <= 9; i++) {
            Rect r = boundsStatic(cur);
            try {
                if (cur.isVisibleToUser() && cur.isClickable()) return cur;
            } catch (Throwable ignored) {}
            if (r != null && r.width() >= 120 && r.height() >= 45 && r.height() <= 500) {
                reasonable = cur;
            }
            cur = cur.getParent();
        }
        return reasonable;
    }

    private boolean clickPacket(PacketCandidate packet) {
        if (packet == null || packet.node == null || !canActOnCurrentWindow()) return false;
        try {
            if (packet.node.isClickable()
                    && packet.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        } catch (Throwable ignored) {}
        return clickAt(packet.rect.exactCenterX(), packet.rect.exactCenterY());
    }

    private boolean clickExactOpenOnce(AccessibilityNodeInfo scope) {
        if (!canActOnCurrentWindow()) return false;
        AccessibilityNodeInfo best = findBestOpenNode(scope);
        if (best == null) return false;
        Rect r = bounds(best);
        if (r == null) return false;
        try {
            if (best.isClickable() && best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        } catch (Throwable ignored) {}
        return clickAt(r.exactCenterX(), r.exactCenterY());
    }

    private boolean clickCentralPopupActionOnce(
            AccessibilityNodeInfo scope, Rect rootRect, int maxVisited) {
        if (!canActOnCurrentWindow() || scope == null || rootRect == null || rootRect.isEmpty()) {
            return false;
        }
        Candidate best = new Candidate(maxVisited);
        findCentralClickable(scope, rootRect, 0, best);
        if (best.node == null || best.rect == null) return false;
        try {
            if (best.node.isClickable()
                    && best.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        } catch (Throwable ignored) {}
        return clickAt(best.rect.exactCenterX(), best.rect.exactCenterY());
    }

    private boolean clickExpectedOpenCenterOnce(AccessibilityNodeInfo root) {
        if (!canActOnCurrentWindow()) return false;
        Rect r = bounds(root);
        if (r == null) return false;
        return clickAt(r.exactCenterX(), r.top + r.height() * 0.525f);
    }

    private boolean isRedPacketResultVisible(AccessibilityNodeInfo root) {
        return subtreeContainsAny(root, FINISHED_TERMS, 6);
    }

    private boolean backOnce() {
        return canActOnCurrentWindow() && performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private boolean clickAt(float x, float y) {
        if (!canActOnCurrentWindow()) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 2))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private static AccessibilityNodeInfo findBestOpenNode(AccessibilityNodeInfo scope) {
        if (scope == null) return null;
        AccessibilityNodeInfo best = null;
        double bestScore = -1e30;
        Rect reference = boundsStatic(scope);

        String[] terms = {"開", "开"};
        for (String term : terms) {
            List<AccessibilityNodeInfo> nodes;
            try { nodes = scope.findAccessibilityNodeInfosByText(term); }
            catch (Throwable ignored) { continue; }
            if (nodes == null) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                if (!isExactOpen(node.getText()) && !isExactOpen(node.getContentDescription())) continue;
                AccessibilityNodeInfo candidate = findClickableAncestor(node, 6);
                if (candidate == null) candidate = node;
                Rect r = boundsStatic(candidate);
                if (r == null) continue;
                double score = 0.0;
                if (reference != null) {
                    float dx = r.exactCenterX() - reference.exactCenterX();
                    float dy = r.exactCenterY() - reference.exactCenterY();
                    score = -(dx * dx + dy * dy);
                }
                if (candidate.isClickable()) score += 1_000_000.0;
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
        if (node == null || depth > 15 || best.visited++ >= best.maxVisited) return;
        try {
            if (node.isVisibleToUser() && node.isClickable()) {
                Rect r = boundsStatic(node);
                if (r != null) {
                    float rw = rootRect.width();
                    float rh = rootRect.height();
                    float dx = Math.abs(r.exactCenterX() - rootRect.exactCenterX());
                    float dy = Math.abs(r.exactCenterY() - rootRect.exactCenterY());
                    float minSide = Math.min(r.width(), r.height());
                    float maxSide = Math.max(r.width(), r.height());
                    String text = (node.getText() == null ? "" : node.getText().toString()) + " "
                            + (node.getContentDescription() == null
                            ? "" : node.getContentDescription().toString());

                    boolean sizeOk = minSide >= 28f
                            && r.width() <= rw * 0.55f
                            && r.height() <= rh * 0.30f;
                    boolean central = dx <= rw * 0.24f && dy <= rh * 0.24f;
                    boolean notClose = !text.contains("关闭") && !text.contains("返回")
                            && !text.equalsIgnoreCase("close");
                    if (sizeOk && central && notClose) {
                        double shapePenalty = maxSide <= 0 ? 3.0
                                : Math.abs(Math.log(Math.max(0.15,
                                r.width() / (double) r.height())));
                        double score = dx * dx + dy * dy + shapePenalty * 4000.0;
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
            try {
                if (cur.isVisibleToUser() && cur.isClickable()) return cur;
            } catch (Throwable ignored) {}
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean subtreeContainsAny(
            AccessibilityNodeInfo node, String[] terms, int depth) {
        if (node == null || terms == null) return false;
        try {
            String text = (node.getText() == null ? "" : node.getText().toString()) + " "
                    + (node.getContentDescription() == null
                    ? "" : node.getContentDescription().toString());
            for (String term : terms) {
                if (term != null && text.contains(term)) return true;
            }
            if (depth <= 0) return false;
            int count = Math.min(node.getChildCount(), 24);
            for (int i = 0; i < count; i++) {
                if (subtreeContainsAny(node.getChild(i), terms, depth - 1)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Rect boundsStatic(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            return r.isEmpty() ? null : r;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Rect bounds(AccessibilityNodeInfo node) {
        return boundsStatic(node);
    }

    private static boolean closeRects(Rect a, Rect b, int tolerance) {
        return a != null && b != null
                && Math.abs(a.centerX() - b.centerX()) <= tolerance
                && Math.abs(a.centerY() - b.centerY()) <= tolerance;
    }

    private static boolean isExactOpen(CharSequence value) {
        if (value == null) return false;
        String s = value.toString().trim();
        return "開".equals(s) || "开".equals(s);
    }

    private static final class PacketCandidate {
        final AccessibilityNodeInfo node;
        final Rect rect;
        PacketCandidate(AccessibilityNodeInfo node, Rect rect) {
            this.node = node;
            this.rect = rect;
        }
    }

    private static final class Candidate {
        AccessibilityNodeInfo node;
        Rect rect;
        double score = Double.POSITIVE_INFINITY;
        int visited = 0;
        final int maxVisited;
        Candidate(int maxVisited) { this.maxVisited = maxVisited; }
    }
}
