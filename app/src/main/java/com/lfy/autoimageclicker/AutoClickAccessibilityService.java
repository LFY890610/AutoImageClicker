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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private static final String KEY_LAST_OPEN_COUNT = "last_open_count";
    private static final String KEY_LAST_ACTION = "last_action";
    private static final String KEY_LAST_DIAG_TIME = "last_diag_time";
    private static final String KEY_MONITOR_START = "monitor_start_wall";
    private static final String KEY_BASELINE_COUNT = "baseline_count";
    private static final String KEY_NEW_PACKET_COUNT = "new_packet_count";
    private static final String KEY_MONITOR_MODE = "monitor_mode";

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
    private final Set<String> seenPacketSignatures = new HashSet<>();

    private int state = WAIT_PACKET;
    private long stateSince = 0L;
    private long monitorStartedElapsed = 0L;
    private long pageEnteredAt = 0L;
    private long pendingRedPacketUntil = 0L;
    private String pendingRedPacketPackage = "";
    private String activeWeChatPackage = "";
    private String baselinePackage = "";
    private boolean baselineReady = false;
    private int sequence = 0;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!isAutomationEnabled(AutoClickAccessibilityService.this)) return;
            processCurrentUi();
            handler.postDelayed(this, state == WAIT_PACKET ? 500L : 70L);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = new WeakReference<>(this);
        if (isAutomationEnabled(this)) startNewMonitoringSession();
        else resetState();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isAutomationEnabled(this)) return;

        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String cls = event.getClassName() == null ? "" : event.getClassName().toString();
        if (pkg.equals(getPackageName())) return;
        if (!isSystemNoisePackage(pkg)) saveLastSeen(pkg, cls);

        boolean accepted = isAcceptedIdentity(pkg, cls);
        if (!isSystemNoisePackage(pkg)) {
            writeIdentity(accepted ? "已识别为微信/可信分身" : "未识别为微信/分身");
        }
        if (!accepted) return;

        activeWeChatPackage = pkg;
        long now = SystemClock.uptimeMillis();
        int type = event.getEventType();

        if (state != WAIT_PACKET) {
            AccessibilityNodeInfo source = safeEventSource(event);
            if (source != null && processOpenOrResult(source, now, true)) return;
            processCurrentUi();
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            markPageForBaseline(pkg, now);
            if (hasPendingSignalFor(pkg, now)) {
                handler.postDelayed(this::processCurrentUi, 90L);
            } else {
                int token = ++sequence;
                handler.postDelayed(() -> {
                    if (token == sequence && state == WAIT_PACKET) buildBaselineFromCurrentWindow();
                }, 180L);
            }
            return;
        }

        if (hasPendingSignalFor(pkg, now)) {
            if (processNewestPacketFromCurrentWindow(true)) return;
        }

        if (!baselineReady || !pkg.equals(baselinePackage)) {
            buildBaselineFromCurrentWindow();
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            AccessibilityNodeInfo source = safeEventSource(event);
            if (source != null && processNewPacketFromScope(source, now)) return;
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacksAndMessages(null);
        seenPacketSignatures.clear();
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

    public static void noteRedPacketNotification(Context context, String pkg) {
        if (!isAutomationEnabled(context) || !isSafeClonePackage(context, pkg)) return;
        trustPackageFromRedPacketNotification(context, pkg);
        AutoClickAccessibilityService service = instance.get();
        if (service != null) {
            service.pendingRedPacketPackage = pkg;
            service.pendingRedPacketUntil = SystemClock.uptimeMillis() + 6000L;
            service.writeAction("监控开始后收到红包通知，等待对应新红包出现");
            service.handler.postDelayed(service::processCurrentUi, 80L);
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
        if (!isSafeClonePackage(context, pkg)) return false;
        prefs(context).edit().putString(KEY_TRUSTED_CLONE, pkg).apply();
        AutoClickAccessibilityService service = instance.get();
        if (service != null) {
            service.activeWeChatPackage = pkg;
            service.baselineReady = false;
            service.baselinePackage = "";
            service.writeIdentity("已手动设为可信微信分身：" + pkg);
        }
        return true;
    }

    public static void trustPackageFromRedPacketNotification(Context context, String pkg) {
        if (!isSafeClonePackage(context, pkg)) return;
        prefs(context).edit().putString(KEY_TRUSTED_CLONE, pkg).apply();
    }

    public static String getDiagnostics(Context context) {
        SharedPreferences p = prefs(context);
        long start = p.getLong(KEY_MONITOR_START, 0L);
        String sessionAge = start <= 0L ? "未开始"
                : Math.max(0L, (System.currentTimeMillis() - start) / 1000L) + "秒";
        long diag = p.getLong(KEY_LAST_DIAG_TIME, 0L);
        String diagAge = diag <= 0L ? "无"
                : Math.max(0L, (System.currentTimeMillis() - diag) / 1000L) + "秒前";

        return "监控模式：" + p.getString(KEY_MONITOR_MODE, "未开始")
                + "\n本次监控已运行：" + sessionAge
                + "\n历史基线红包数：" + p.getInt(KEY_BASELINE_COUNT, 0)
                + "\n本次发现新红包数：" + p.getInt(KEY_NEW_PACKET_COUNT, 0)
                + "\n最近微信/分身包名：" + p.getString(KEY_LAST_SEEN_PACKAGE, "未记录")
                + "\n身份判断：" + p.getString(KEY_LAST_IDENTITY, "未检测")
                + "\n可信分身包名：" + p.getString(KEY_TRUSTED_CLONE, "未设置")
                + "\n当前微信根节点：" + p.getString(KEY_LAST_ROOT, "未检测")
                + "\n当前可见含‘红包’节点数：" + p.getInt(KEY_LAST_PACKET_COUNT, -1)
                + "\n‘开/開’节点数：" + p.getInt(KEY_LAST_OPEN_COUNT, -1)
                + "\n最后动作：" + p.getString(KEY_LAST_ACTION, "暂无动作")
                + "\n诊断更新时间：" + diagAge;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void onAutomationFlagChanged(boolean enabled) {
        handler.removeCallbacksAndMessages(null);
        sequence++;
        if (!enabled) {
            seenPacketSignatures.clear();
            baselineReady = false;
            pendingRedPacketUntil = 0L;
            pendingRedPacketPackage = "";
            resetState();
            writeAction("自动领取已停止");
            return;
        }
        startNewMonitoringSession();
    }

    private void startNewMonitoringSession() {
        handler.removeCallbacksAndMessages(null);
        sequence++;
        seenPacketSignatures.clear();
        state = WAIT_PACKET;
        stateSince = SystemClock.uptimeMillis();
        monitorStartedElapsed = stateSince;
        pageEnteredAt = stateSince;
        baselineReady = false;
        baselinePackage = "";
        pendingRedPacketUntil = 0L;
        pendingRedPacketPackage = "";
        activeWeChatPackage = "";
        prefs(this).edit()
                .putLong(KEY_MONITOR_START, System.currentTimeMillis())
                .putInt(KEY_BASELINE_COUNT, 0)
                .putInt(KEY_NEW_PACKET_COUNT, 0)
                .putString(KEY_MONITOR_MODE, "仅监控启动后的新红包，历史红包不处理")
                .apply();
        writeAction("监控从现在开始；进入聊天时先建立历史红包基线，不点击旧红包");
        restartHeartbeat();
    }

    private void restartHeartbeat() {
        handler.removeCallbacks(heartbeat);
        if (isAutomationEnabled(this)) handler.postDelayed(heartbeat, 300L);
    }

    private void resetState() {
        state = WAIT_PACKET;
        stateSince = SystemClock.uptimeMillis();
    }

    private void markPageForBaseline(String pkg, long now) {
        pageEnteredAt = now;
        baselineReady = false;
        baselinePackage = pkg == null ? "" : pkg;
        seenPacketSignatures.clear();
        writeAction("进入/滚动微信页面：当前红包只建立历史基线，不点击");
    }

    private void buildBaselineFromCurrentWindow() {
        if (!isAutomationEnabled(this) || state != WAIT_PACKET) return;
        AccessibilityNodeInfo root = safeRoot();
        if (root == null) return;
        String pkg = nodePackage(root);
        List<PacketCandidate> packets = findPacketCandidates(root);
        seenPacketSignatures.clear();
        for (PacketCandidate p : packets) seenPacketSignatures.add(p.signature);
        baselinePackage = pkg;
        baselineReady = true;
        pageEnteredAt = SystemClock.uptimeMillis();
        prefs(this).edit()
                .putInt(KEY_BASELINE_COUNT, packets.size())
                .putInt(KEY_LAST_PACKET_COUNT, packets.size())
                .apply();
        writeAction("历史基线已建立：忽略当前已有红包 " + packets.size() + " 个，只等之后新出现的红包");
    }

    private boolean processNewPacketFromScope(AccessibilityNodeInfo scope, long now) {
        if (!baselineReady || state != WAIT_PACKET) return false;
        List<PacketCandidate> candidates = findPacketCandidates(scope);
        if (candidates.isEmpty()) return false;

        PacketCandidate newest = null;
        for (PacketCandidate c : candidates) {
            if (seenPacketSignatures.contains(c.signature)) continue;
            if (newest == null || c.rect.bottom > newest.rect.bottom) newest = c;
        }
        for (PacketCandidate c : candidates) seenPacketSignatures.add(c.signature);
        if (newest == null) return false;

        if (now - pageEnteredAt < 250L && !hasPendingSignalFor(nodePackage(newest.node), now)) {
            writeAction("页面刚进入，新增红包先并入历史基线，防止误点旧记录");
            return false;
        }
        return clickNewPacket(newest, now);
    }

    private boolean processNewestPacketFromCurrentWindow(boolean notificationDriven) {
        if (state != WAIT_PACKET) return false;
        AccessibilityNodeInfo root = safeRoot();
        if (root == null) return false;
        List<PacketCandidate> candidates = findPacketCandidates(root);
        if (candidates.isEmpty()) return false;

        PacketCandidate newest = null;
        for (PacketCandidate c : candidates) {
            if (!notificationDriven && seenPacketSignatures.contains(c.signature)) continue;
            if (newest == null || c.rect.bottom > newest.rect.bottom) newest = c;
        }
        if (newest == null) return false;
        for (PacketCandidate c : candidates) seenPacketSignatures.add(c.signature);
        baselineReady = true;
        baselinePackage = nodePackage(root);
        return clickNewPacket(newest, SystemClock.uptimeMillis());
    }

    private boolean clickNewPacket(PacketCandidate packet, long now) {
        if (packet == null || !canActOnCurrentWindow()) return false;
        boolean clicked = false;
        try {
            if (packet.node.isClickable()) {
                clicked = packet.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        } catch (Throwable ignored) {}
        if (!clicked) clicked = clickAt(packet.rect.exactCenterX(), packet.rect.exactCenterY());
        if (!clicked) return false;

        seenPacketSignatures.add(packet.signature);
        int count = prefs(this).getInt(KEY_NEW_PACKET_COUNT, 0) + 1;
        prefs(this).edit().putInt(KEY_NEW_PACKET_COUNT, count).apply();
        pendingRedPacketUntil = 0L;
        pendingRedPacketPackage = "";
        state = WAIT_OPEN;
        stateSince = now;
        writeAction("检测到监控开始后的新红包，已点击1次，等待开按钮");

        int token = ++sequence;
        long[] delays = {15L, 35L, 65L, 100L, 150L, 230L, 360L, 560L, 900L, 1500L};
        for (long d : delays) {
            handler.postDelayed(() -> {
                if (token == sequence && state == WAIT_OPEN) processCurrentUi();
            }, d);
        }
        return true;
    }

    private void processCurrentUi() {
        if (!isAutomationEnabled(this)) return;
        long now = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = safeRoot();
        if (root == null) {
            writeRootStatus("当前前台不是可信微信/分身");
            return;
        }

        updateNodeDiagnostics(root);

        if (state == WAIT_PACKET) {
            String pkg = nodePackage(root);
            if (hasPendingSignalFor(pkg, now)) {
                processNewestPacketFromCurrentWindow(true);
                return;
            }
            if (!baselineReady || !pkg.equals(baselinePackage)) buildBaselineFromCurrentWindow();
            return;
        }

        if (processOpenOrResult(root, now, false)) return;

        if (state == WAIT_OPEN) {
            long elapsed = now - stateSince;
            if (elapsed >= 120L && elapsed < 420L && clickExpectedOpenCenterOnce(root)) {
                writeAction("未读到开按钮文字，已点击红包弹窗中心1次");
                enterWaitResult(now);
                return;
            }
            if (elapsed > 1800L) {
                if (backOnce()) writeAction("未找到开按钮，已退出红包弹窗");
                enterWaitClear(now);
            }
            return;
        }

        if (state == WAIT_RESULT) {
            long elapsed = now - stateSince;
            if (isRedPacketResultVisible(root) || elapsed >= 900L) {
                if (backOnce()) writeAction("领取流程结束，已返回聊天");
                enterWaitClear(now);
            }
            return;
        }

        if (state == WAIT_CLEAR && now - stateSince >= 180L) {
            state = WAIT_PACKET;
            stateSince = now;
            baselineReady = false;
            seenPacketSignatures.clear();
            handler.postDelayed(this::buildBaselineFromCurrentWindow, 160L);
        }
    }

    private boolean processOpenOrResult(AccessibilityNodeInfo scope, long now, boolean fromEvent) {
        if (state == WAIT_OPEN) {
            if (subtreeContainsAny(scope, FINISHED_TERMS, 5)) {
                if (!fromEvent && backOnce()) {
                    writeAction("红包已领取/不可领取，已返回聊天");
                    enterWaitClear(now);
                    return true;
                }
                return false;
            }
            AccessibilityNodeInfo open = findBestOpenNode(scope);
            if (open != null) {
                Rect r = bounds(open);
                boolean clicked = false;
                try {
                    if (open.isClickable()) clicked = open.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } catch (Throwable ignored) {}
                if (!clicked && r != null && canActOnCurrentWindow()) {
                    clicked = clickAt(r.exactCenterX(), r.exactCenterY());
                }
                if (clicked) {
                    writeAction("已点击‘开/開’1次");
                    enterWaitResult(now);
                    return true;
                }
            }
        }
        if (state == WAIT_RESULT && subtreeContainsAny(scope, FINISHED_TERMS, 5) && !fromEvent) {
            if (backOnce()) {
                writeAction("领取结果已出现，已返回聊天");
                enterWaitClear(now);
                return true;
            }
        }
        return false;
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

    private boolean hasPendingSignalFor(String pkg, long now) {
        if (now > pendingRedPacketUntil) return false;
        if (pendingRedPacketPackage == null || pendingRedPacketPackage.isEmpty()) return true;
        return pendingRedPacketPackage.equals(pkg)
                || isMainWeChatIdentity(pkg, "") && isMainWeChatIdentity(pendingRedPacketPackage, "");
    }

    private AccessibilityNodeInfo safeEventSource(AccessibilityEvent event) {
        try { return event.getSource(); }
        catch (Throwable ignored) { return null; }
    }

    private AccessibilityNodeInfo safeRoot() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            String pkg = nodePackage(root);
            String cls = root.getClassName() == null ? "" : root.getClassName().toString();
            if (pkg.equals(getPackageName())) return null;
            if (isAcceptedIdentity(pkg, cls)
                    || (!activeWeChatPackage.isEmpty() && activeWeChatPackage.equals(pkg))) {
                activeWeChatPackage = pkg;
                writeRootStatus("可读取，包名=" + pkg);
                return root;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean canActOnCurrentWindow() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;
            String pkg = nodePackage(root);
            String cls = root.getClassName() == null ? "" : root.getClassName().toString();
            if (pkg.equals(getPackageName())) return false;
            return isAcceptedIdentity(pkg, cls)
                    || (!activeWeChatPackage.isEmpty() && activeWeChatPackage.equals(pkg));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private List<PacketCandidate> findPacketCandidates(AccessibilityNodeInfo scope) {
        List<AccessibilityNodeInfo> nodes = collectNodesByText(scope, "红包", 30);
        List<PacketCandidate> out = new ArrayList<>();
        Rect scopeRect = bounds(scope);
        for (AccessibilityNodeInfo label : nodes) {
            if (label == null) continue;
            boolean visible;
            try { visible = label.isVisibleToUser(); }
            catch (Throwable ignored) { visible = false; }
            if (!visible) continue;

            String text = compact(nodeText(label));
            if (!text.contains("红包") || containsFinishedTerm(text)) continue;
            AccessibilityNodeInfo card = chooseNearestPacketContainer(label, scopeRect);
            if (card == null) card = label;
            if (subtreeContainsAny(card, FINISHED_TERMS, 2)) continue;
            Rect r = bounds(card);
            if (r == null) r = bounds(label);
            if (!isMessageLikeRect(r, scopeRect)) continue;

            boolean shortPacketText = text.length() <= 18;
            boolean strong = shortPacketText
                    || text.contains("微信红包")
                    || text.contains("领取红包")
                    || text.contains("恭喜发财")
                    || text.contains("大吉大利");
            if (!strong) continue;
            out.add(new PacketCandidate(card, r, packetSignature(card, r, text)));
        }
        return out;
    }

    private static String packetSignature(AccessibilityNodeInfo node, Rect r, String text) {
        String pkg = nodePackage(node);
        int qx = r == null ? 0 : r.centerX() / 12;
        int qy = r == null ? 0 : r.centerY() / 12;
        int qw = r == null ? 0 : r.width() / 12;
        int qh = r == null ? 0 : r.height() / 12;
        return pkg + "|" + qx + ":" + qy + ":" + qw + ":" + qh + "|" + text;
    }

    private static AccessibilityNodeInfo chooseNearestPacketContainer(
            AccessibilityNodeInfo label, Rect scopeRect) {
        AccessibilityNodeInfo cur = label;
        AccessibilityNodeInfo nearest = label;
        for (int i = 0; cur != null && i <= 5; i++) {
            Rect r = boundsStatic(cur);
            if (isMessageLikeRect(r, scopeRect) && r.width() >= 60 && r.height() >= 30) {
                nearest = cur;
                try {
                    if (cur.isVisibleToUser() && cur.isClickable()) return cur;
                } catch (Throwable ignored) {}
            }
            cur = cur.getParent();
        }
        return nearest;
    }

    private static boolean isMessageLikeRect(Rect r, Rect scopeRect) {
        if (r == null || r.isEmpty() || r.width() < 20 || r.height() < 18 || r.height() > 420) {
            return false;
        }
        return scopeRect == null || scopeRect.isEmpty()
                || !(r.width() > scopeRect.width() * 0.95f
                && r.height() > scopeRect.height() * 0.55f);
    }

    private AccessibilityNodeInfo findBestOpenNode(AccessibilityNodeInfo scope) {
        AccessibilityNodeInfo best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Rect reference = bounds(scope);
        for (String term : new String[]{"開", "开"}) {
            List<AccessibilityNodeInfo> nodes = collectNodesByText(scope, term, 12);
            for (AccessibilityNodeInfo n : nodes) {
                if (n == null) continue;
                if (!isExactOpen(n.getText()) && !isExactOpen(n.getContentDescription())) continue;
                AccessibilityNodeInfo c = findClickableAncestor(n, 5);
                if (c == null) c = n;
                Rect r = bounds(c);
                if (r == null) continue;
                double score = 0;
                if (reference != null) {
                    double dx = r.exactCenterX() - reference.exactCenterX();
                    double dy = r.exactCenterY() - reference.exactCenterY();
                    score = -(dx * dx + dy * dy);
                }
                try { if (c.isClickable()) score += 1_000_000.0; }
                catch (Throwable ignored) {}
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
        }
        return best;
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

    private static boolean isSafeClonePackage(Context context, String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(context.getPackageName())) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return !p.equals("android")
                && !p.startsWith("com.android.")
                && !p.startsWith("android.")
                && !p.contains("systemui")
                && !p.contains("launcher");
    }

    private static boolean isSystemNoisePackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.equals("android")
                || p.startsWith("com.android.")
                || p.startsWith("android.")
                || p.contains("systemui")
                || p.contains("launcher");
    }

    private static List<AccessibilityNodeInfo> collectNodesByText(
            AccessibilityNodeInfo root, String term, int maxResults) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root == null || term == null || term.isEmpty()) return out;
        try {
            List<AccessibilityNodeInfo> direct = root.findAccessibilityNodeInfosByText(term);
            if (direct != null) {
                for (AccessibilityNodeInfo n : direct) {
                    if (n != null) out.add(n);
                    if (out.size() >= maxResults) return out;
                }
            }
        } catch (Throwable ignored) {}
        if (!out.isEmpty()) return out;
        collectNodesContaining(root, term, out, 0, new int[]{0}, maxResults);
        return out;
    }

    private static void collectNodesContaining(
            AccessibilityNodeInfo node, String term, List<AccessibilityNodeInfo> out,
            int depth, int[] visited, int maxResults) {
        if (node == null || depth > 13 || visited[0]++ > 500 || out.size() >= maxResults) return;
        try {
            if (node.isVisibleToUser() && nodeText(node).contains(term)) out.add(node);
            int count = Math.min(node.getChildCount(), 30);
            for (int i = 0; i < count; i++) {
                collectNodesContaining(node.getChild(i), term, out,
                        depth + 1, visited, maxResults);
            }
        } catch (Throwable ignored) {}
    }

    private static AccessibilityNodeInfo findClickableAncestor(
            AccessibilityNodeInfo node, int maxParents) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; cur != null && i <= maxParents; i++) {
            try { if (cur.isVisibleToUser() && cur.isClickable()) return cur; }
            catch (Throwable ignored) {}
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean subtreeContainsAny(
            AccessibilityNodeInfo node, String[] terms, int depth) {
        if (node == null || terms == null) return false;
        try {
            String text = nodeText(node);
            for (String term : terms) if (term != null && text.contains(term)) return true;
            if (depth <= 0) return false;
            int count = Math.min(node.getChildCount(), 24);
            for (int i = 0; i < count; i++) {
                if (subtreeContainsAny(node.getChild(i), terms, depth - 1)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static String compact(String s) {
        return s == null ? "" : s.replace(" ", "").replace("\n", "").trim();
    }

    private static String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        String t = node.getText() == null ? "" : node.getText().toString();
        String d = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        return (t + " " + d).trim();
    }

    private static String nodePackage(AccessibilityNodeInfo node) {
        return node == null || node.getPackageName() == null ? "" : node.getPackageName().toString();
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
        } catch (Throwable ignored) { return null; }
    }

    private Rect bounds(AccessibilityNodeInfo node) {
        return boundsStatic(node);
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
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis()).apply();
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
        prefs(this).edit()
                .putInt(KEY_LAST_PACKET_COUNT, collectNodesByText(root, "红包", 40).size())
                .putInt(KEY_LAST_OPEN_COUNT, countOpen(root))
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis()).apply();
    }

    private static int countOpen(AccessibilityNodeInfo root) {
        int count = 0;
        for (String term : new String[]{"開", "开"}) {
            for (AccessibilityNodeInfo n : collectNodesByText(root, term, 12)) {
                if (isExactOpen(n.getText()) || isExactOpen(n.getContentDescription())) count++;
            }
        }
        return count;
    }

    private static final class PacketCandidate {
        final AccessibilityNodeInfo node;
        final Rect rect;
        final String signature;
        PacketCandidate(AccessibilityNodeInfo node, Rect rect, String signature) {
            this.node = node;
            this.rect = rect;
            this.signature = signature;
        }
    }
}
