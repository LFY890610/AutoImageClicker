package com.lfy.autoimageclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private static final String KEY_TRUSTED_CLONE = "trusted_clone_package";
    private static final String KEY_LAST_SEEN_PACKAGE = "last_seen_package";
    private static final String KEY_LAST_SEEN_CLASS = "last_seen_class";
    private static final String KEY_LAST_IDENTITY = "last_identity";
    private static final String KEY_LAST_ROOT = "last_root";
    private static final String KEY_LAST_PACKET_COUNT = "last_packet_count";
    private static final String KEY_LAST_EXACT_PACKET_COUNT = "last_exact_packet_count";
    private static final String KEY_LAST_OPEN_COUNT = "last_open_count";
    private static final String KEY_LAST_ACTION = "last_action";
    private static final String KEY_LAST_DIAG_TIME = "last_diag_time";

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
        writeAction("无障碍服务已连接");
        restartHeartbeat();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isAutomationEnabled(this)) return;

        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String cls = event.getClassName() == null ? "" : event.getClassName().toString();
        if (pkg.equals(getPackageName())) return;

        saveLastSeen(pkg, cls);

        boolean accepted = isAcceptedIdentity(pkg, cls);
        writeIdentity(accepted ? "已识别为微信/可信分身" : "未识别为微信/分身");
        if (!accepted) return;

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
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setAutomationEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        AutoClickAccessibilityService service = instance.get();
        if (service != null) service.onAutomationFlagChanged(enabled);
    }

    public static void requestImmediateCheck() {
        AutoClickAccessibilityService service = instance.get();
        if (service != null && isAutomationEnabled(service)) {
            service.handler.post(service::processCurrentUi);
        }
    }

    public static String getLastSeenPackage(Context context) {
        return prefs(context).getString(KEY_LAST_SEEN_PACKAGE, "");
    }

    public static String getTrustedClonePackage(Context context) {
        return prefs(context).getString(KEY_TRUSTED_CLONE, "");
    }

    public static boolean trustLastSeenAsClone(Context context) {
        String pkg = getLastSeenPackage(context);
        if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) return false;
        prefs(context).edit().putString(KEY_TRUSTED_CLONE, pkg).apply();
        AutoClickAccessibilityService service = instance.get();
        if (service != null) {
            service.activeWeChatPackage = pkg;
            service.writeIdentity("已手动设为可信微信分身");
            service.handler.post(service::processCurrentUi);
        }
        return true;
    }

    public static void trustPackageFromRedPacketNotification(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) return;
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.startsWith("android") || p.startsWith("com.android.systemui")) return;
        prefs(context).edit().putString(KEY_TRUSTED_CLONE, pkg).apply();
    }

    public static String getDiagnostics(Context context) {
        SharedPreferences p = prefs(context);
        String seenPkg = p.getString(KEY_LAST_SEEN_PACKAGE, "未记录");
        String seenCls = p.getString(KEY_LAST_SEEN_CLASS, "未记录");
        String identity = p.getString(KEY_LAST_IDENTITY, "未检测");
        String root = p.getString(KEY_LAST_ROOT, "未检测");
        int packetCount = p.getInt(KEY_LAST_PACKET_COUNT, -1);
        int exactCount = p.getInt(KEY_LAST_EXACT_PACKET_COUNT, -1);
        int openCount = p.getInt(KEY_LAST_OPEN_COUNT, -1);
        String action = p.getString(KEY_LAST_ACTION, "暂无动作");
        String clone = p.getString(KEY_TRUSTED_CLONE, "未设置");
        long time = p.getLong(KEY_LAST_DIAG_TIME, 0L);
        String age;
        if (time <= 0L) age = "无";
        else age = Math.max(0L, (System.currentTimeMillis() - time) / 1000L) + "秒前";
        return "最近应用包名：" + seenPkg
                + "\n最近类名：" + seenCls
                + "\n身份判断：" + identity
                + "\n可信分身包名：" + clone
                + "\n当前微信根节点：" + root
                + "\n含‘红包’节点数：" + packetCount
                + "\n含‘微信红包’节点数：" + exactCount
                + "\n‘开/開’节点数：" + openCount
                + "\n最后动作：" + action
                + "\n诊断更新时间：" + age;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void onAutomationFlagChanged(boolean enabled) {
        handler.removeCallbacksAndMessages(null);
        sequence++;
        resetState();
        if (!enabled) {
            activeWeChatPackage = "";
            writeAction("自动领取已停止");
            return;
        }
        writeAction("自动领取已启动，等待微信红包");
        handler.post(this::processCurrentUi);
        restartHeartbeat();
    }

    private void restartHeartbeat() {
        handler.removeCallbacks(heartbeat);
        if (isAutomationEnabled(this)) handler.postDelayed(heartbeat, 100L);
    }

    private void resetState() {
        state = WAIT_PACKET;
        stateSince = SystemClock.uptimeMillis();
    }

    private boolean isAcceptedIdentity(String pkg, String cls) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) return false;
        if (isMainWeChatIdentity(pkg, cls)) return true;
        String trusted = getTrustedClonePackage(this);
        return !trusted.isEmpty() && trusted.equals(pkg);
    }

    private static boolean isMainWeChatIdentity(String pkg, String cls) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        String c = cls == null ? "" : cls;
        return WECHAT_PACKAGE.equals(p)
                || p.startsWith(WECHAT_PACKAGE + ".")
                || p.contains("tencent.mm")
                || c.startsWith("com.tencent.mm.");
    }

    private boolean processScope(AccessibilityNodeInfo scope, long now) {
        if (scope == null || !canActOnCurrentWindow()) return false;

        if (state == WAIT_CLEAR && now - stateSince >= 120L) {
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
                if (backOnce()) writeAction("检测到红包结果，已返回聊天");
                enterWaitClear(now);
                return true;
            }
            if (clickExactOpenOnce(scope)) {
                writeAction("已点击‘开/開’1次");
                enterWaitResult(now);
                return true;
            }
            AccessibilityNodeInfo root = safeRoot();
            Rect rootRect = bounds(root);
            if (rootRect != null && clickCentralPopupActionOnce(scope, rootRect, 220)) {
                writeAction("已点击红包弹窗中央控件1次");
                enterWaitResult(now);
                return true;
            }
            return false;
        }

        if (state == WAIT_RESULT && subtreeContainsAny(scope, FINISHED_TERMS, 5)) {
            if (backOnce()) writeAction("领取结果已出现，已返回聊天");
            enterWaitClear(now);
            return true;
        }
        return false;
    }

    private void processCurrentUi() {
        if (!isAutomationEnabled(this)) return;
        AccessibilityNodeInfo root = safeRoot();
        if (root == null) {
            writeRootStatus("未获得可信微信根节点");
            return;
        }

        updateNodeDiagnostics(root);
        long now = SystemClock.uptimeMillis();
        if (processScope(root, now)) return;

        if (state == WAIT_OPEN) {
            long elapsed = now - stateSince;
            if (elapsed >= 120L && elapsed < 360L && clickExpectedOpenCenterOnce(root)) {
                writeAction("未读到开按钮文字，已对红包弹窗中心点击1次");
                enterWaitResult(now);
                return;
            }
            if (elapsed > 1700L) {
                if (backOnce()) writeAction("未找到开按钮，已退出红包弹窗");
                enterWaitClear(now);
            }
            return;
        }

        if (state == WAIT_RESULT) {
            long elapsed = now - stateSince;
            if (isRedPacketResultVisible(root)) {
                if (backOnce()) writeAction("领取完成，已返回聊天");
                enterWaitClear(now);
                return;
            }
            if (elapsed >= 900L) {
                if (backOnce()) writeAction("领取后自动返回聊天");
                enterWaitClear(now);
            }
            return;
        }

        if (state == WAIT_CLEAR && now - stateSince >= 140L) {
            state = WAIT_PACKET;
            stateSince = now;
        }
    }

    private void onPacketClicked(PacketCandidate packet, long now) {
        lastPacketAt = now;
        lastPacketRect = new Rect(packet.rect);
        state = WAIT_OPEN;
        stateSince = now;
        writeAction("已点击红包1次，等待开按钮");
        int token = ++sequence;
        long[] delays = {15L, 35L, 65L, 100L, 150L, 230L, 360L, 560L, 900L, 1500L};
        for (long d : delays) {
            handler.postDelayed(() -> {
                if (token == sequence && state == WAIT_OPEN) processCurrentUi();
            }, d);
        }
    }

    private void enterWaitResult(long now) {
        state = WAIT_RESULT;
        stateSince = now;
        int token = ++sequence;
        long[] delays = {40L, 80L, 140L, 240L, 400L, 650L, 920L};
        for (long d : delays) {
            handler.postDelayed(() -> {
                if (token == sequence && state == WAIT_RESULT) processCurrentUi();
            }, d);
        }
    }

    private void enterWaitClear(long now) {
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
            if (pkg.equals(getPackageName())) return null;
            if (isAcceptedIdentity(pkg, cls)) {
                activeWeChatPackage = pkg;
                writeRootStatus("可读取，包名=" + pkg);
                return root;
            }
            if (!activeWeChatPackage.isEmpty() && activeWeChatPackage.equals(pkg)) {
                writeRootStatus("可读取，已确认分身包名=" + pkg);
                return root;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean canActOnCurrentWindow() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            String cls = root.getClassName() == null ? "" : root.getClassName().toString();
            if (pkg.equals(getPackageName())) return false;
            return isAcceptedIdentity(pkg, cls)
                    || (!activeWeChatPackage.isEmpty() && activeWeChatPackage.equals(pkg));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private PacketCandidate findBestRedPacket(AccessibilityNodeInfo scope, long now) {
        if (scope == null) return null;
        List<AccessibilityNodeInfo> labels = new ArrayList<>();
        try {
            List<AccessibilityNodeInfo> direct = scope.findAccessibilityNodeInfosByText("红包");
            if (direct != null) labels.addAll(direct);
        } catch (Throwable ignored) {}
        if (labels.isEmpty()) collectPacketLabels(scope, labels, 0, new int[]{0});
        if (labels.isEmpty()) return null;

        PacketCandidate best = null;
        double bestScore = -1e30;
        for (AccessibilityNodeInfo label : labels) {
            if (label == null || !label.isVisibleToUser()) continue;
            String text = nodeText(label);
            if (!text.contains("红包")) continue;
            if (containsFinishedTerm(text)) continue;

            AccessibilityNodeInfo candidate = choosePacketContainer(label);
            if (candidate == null) candidate = label;
            if (subtreeContainsAny(candidate, FINISHED_TERMS, 3)) continue;

            Rect r = bounds(candidate);
            if (r == null) r = bounds(label);
            if (r == null || r.width() < 35 || r.height() < 20) continue;
            if (lastPacketRect != null && now - lastPacketAt < 4500L
                    && closeRects(r, lastPacketRect, 42)) continue;

            boolean strong = text.contains("微信红包")
                    || text.contains("领取红包")
                    || text.contains("[红包]")
                    || text.contains("【红包】")
                    || subtreeContainsAny(candidate,
                    new String[]{"微信红包", "恭喜发财", "大吉大利"}, 3);
            boolean structural = candidate.isClickable()
                    && r.width() >= 120 && r.height() >= 45
                    && r.width() > r.height() * 1.05f;
            if (!strong && !structural) continue;

            double score = r.bottom * 20.0 + r.centerY();
            if (strong) score += 1_000_000.0;
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
        if (node == null || depth > 13 || visited[0]++ > 480 || out.size() >= 18) return;
        try {
            if (node.isVisibleToUser() && nodeText(node).contains("红包")) out.add(node);
            int n = Math.min(node.getChildCount(), 30);
            for (int i = 0; i < n; i++) {
                collectPacketLabels(node.getChild(i), out, depth + 1, visited);
            }
        } catch (Throwable ignored) {}
    }

    private static AccessibilityNodeInfo choosePacketContainer(AccessibilityNodeInfo label) {
        AccessibilityNodeInfo cur = label;
        AccessibilityNodeInfo reasonable = null;
        for (int i = 0; cur != null && i <= 9; i++) {
            Rect r = boundsStatic(cur);
            try {
                if (cur.isVisibleToUser() && cur.isClickable()) return cur;
            } catch (Throwable ignored) {}
            if (r != null && r.width() >= 100 && r.height() >= 40 && r.height() <= 520) {
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
        for (String term : new String[]{"開", "开"}) {
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
                    String text = nodeText(node);
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
            String text = nodeText(node);
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

    private static String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        String t = node.getText() == null ? "" : node.getText().toString();
        String d = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        return (t + " " + d).trim();
    }

    private static boolean containsFinishedTerm(String text) {
        if (text == null) return false;
        for (String term : FINISHED_TERMS) if (text.contains(term)) return true;
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

    private void saveLastSeen(String pkg, String cls) {
        prefs(this).edit()
                .putString(KEY_LAST_SEEN_PACKAGE, pkg == null ? "" : pkg)
                .putString(KEY_LAST_SEEN_CLASS, cls == null ? "" : cls)
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis())
                .apply();
    }

    private void writeIdentity(String value) {
        prefs(this).edit().putString(KEY_LAST_IDENTITY, value)
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis()).apply();
    }

    private void writeRootStatus(String value) {
        prefs(this).edit().putString(KEY_LAST_ROOT, value)
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis()).apply();
    }

    private void writeAction(String value) {
        prefs(this).edit().putString(KEY_LAST_ACTION, value)
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis()).apply();
    }

    private void updateNodeDiagnostics(AccessibilityNodeInfo root) {
        int packetCount = countByText(root, "红包");
        int exactCount = countExactPacket(root);
        int openCount = countOpen(root);
        prefs(this).edit()
                .putInt(KEY_LAST_PACKET_COUNT, packetCount)
                .putInt(KEY_LAST_EXACT_PACKET_COUNT, exactCount)
                .putInt(KEY_LAST_OPEN_COUNT, openCount)
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis())
                .apply();
    }

    private static int countByText(AccessibilityNodeInfo root, String term) {
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(term);
            return nodes == null ? 0 : nodes.size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int countExactPacket(AccessibilityNodeInfo root) {
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("微信红包");
            if (nodes == null) return 0;
            int count = 0;
            for (AccessibilityNodeInfo n : nodes) {
                if (nodeText(n).contains("微信红包")) count++;
            }
            return count;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int countOpen(AccessibilityNodeInfo root) {
        int count = 0;
        for (String term : new String[]{"開", "开"}) {
            try {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(term);
                if (nodes == null) continue;
                for (AccessibilityNodeInfo n : nodes) {
                    if (isExactOpen(n.getText()) || isExactOpen(n.getContentDescription())) count++;
                }
            } catch (Throwable ignored) {}
        }
        return count;
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
