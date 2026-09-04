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
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ReliableRedPacketAccessibilityService extends AccessibilityService {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String PREFS = "red_packet_automation_v2";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TRUSTED_CLONE = "trusted_clone_package";
    private static final String KEY_LAST_WECHAT_PACKAGE = "last_wechat_package";
    private static final String KEY_LAST_IDENTITY = "last_identity";
    private static final String KEY_LAST_ROOT = "last_root";
    private static final String KEY_LAST_OVERLAY = "last_overlay";
    private static final String KEY_LAST_PACKET_COUNT = "last_packet_count";
    private static final String KEY_LAST_OPEN_COUNT = "last_open_count";
    private static final String KEY_LAST_ACTION = "last_action";
    private static final String KEY_LAST_DIAG_TIME = "last_diag_time";
    private static final String KEY_MONITOR_START = "monitor_start_wall";
    private static final String KEY_BASELINE_COUNT = "baseline_count";
    private static final String KEY_NEW_PACKET_COUNT = "new_packet_count";

    private static final int WAIT_PACKET = 0;
    private static final int WAIT_OPEN = 1;
    private static final int WAIT_RESULT = 2;
    private static final int WAIT_CLEAR = 3;

    private static final String[] FINISHED_TERMS = {
            "红包详情", "查看领取详情", "已领取", "手慢了", "来晚了",
            "红包派完了", "已被领完", "已领完", "已存入零钱", "已过期"
    };

    private static volatile WeakReference<ReliableRedPacketAccessibilityService> instance =
            new WeakReference<>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> visiblePacketCounts = new HashMap<>();

    private int state = WAIT_PACKET;
    private long stateSince = 0L;
    private long monitorStartedElapsed = 0L;
    private long pageSettlingUntil = 0L;
    private long pendingNotificationUntil = 0L;
    private String pendingNotificationPackage = "";
    private String currentWeChatPackage = "";
    private String lastBottomPacketKey = "";
    private boolean snapshotReady = false;
    private int sequence = 0;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!isAutomationEnabled(ReliableRedPacketAccessibilityService.this)) return;
            if (state == WAIT_PACKET) {
                refreshIdleState();
                handler.postDelayed(this, 900L);
            } else {
                processOpenResultState();
                handler.postDelayed(this, 70L);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = new WeakReference<>(this);
        if (isAutomationEnabled(this)) startMonitoringSession();
        else resetRuntime();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !isAutomationEnabled(this)) return;

        String pkg = stringOf(event.getPackageName());
        String cls = stringOf(event.getClassName());
        int type = event.getEventType();

        if (pkg.equals(getPackageName())) return;
        if (isOverlayOrSystemNoise(pkg)) {
            writeOverlay(pkg);
            return;
        }

        boolean accepted = isAcceptedIdentity(pkg, cls);
        if (!accepted) return;

        currentWeChatPackage = pkg;
        writeIdentity("已识别微信/分身：" + pkg);
        prefs(this).edit().putString(KEY_LAST_WECHAT_PACKAGE, pkg).apply();

        long now = SystemClock.uptimeMillis();

        if (state != WAIT_PACKET) {
            AccessibilityNodeInfo source = safeEventSource(event);
            if (source != null && nodeBelongsToAcceptedWeChat(source)) {
                if (processOpenOrResult(source, now, true)) return;
            }
            processOpenResultState();
            return;
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            beginPageSettle(now);
            return;
        }

        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        if (now < pageSettlingUntil && !hasPendingNotificationFor(pkg, now)) {
            scheduleSnapshotAfterSettle();
            return;
        }

        AccessibilityNodeInfo root = getTrustedWeChatRoot(true);
        if (root == null) return;

        if (!snapshotReady) {
            buildSnapshot(root, "首次进入聊天，仅登记已有红包，不点击");
            return;
        }

        AccessibilityNodeInfo source = safeEventSource(event);
        boolean eventMentionsPacket = source != null && subtreeContains(source, "红包", 8);
        detectAndHandleNewPacket(root, eventMentionsPacket, hasPendingNotificationFor(pkg, now));
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacksAndMessages(null);
        visiblePacketCounts.clear();
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
        ReliableRedPacketAccessibilityService service = instance.get();
        if (service != null) {
            if (enabled) service.startMonitoringSession();
            else service.stopMonitoringSession();
        }
    }

    public static void requestImmediateCheck() {
        ReliableRedPacketAccessibilityService service = instance.get();
        if (service != null && isAutomationEnabled(service)) {
            service.handler.post(service::refreshIdleState);
        }
    }

    public static void noteRedPacketNotification(Context context, String pkg) {
        if (!isAutomationEnabled(context) || !isSafeClonePackage(context, pkg)) return;
        trustPackageFromRedPacketNotification(context, pkg);
        ReliableRedPacketAccessibilityService service = instance.get();
        if (service == null) return;

        service.pendingNotificationPackage = pkg;
        service.pendingNotificationUntil = SystemClock.uptimeMillis() + 7000L;
        service.writeAction("收到监控开始后的明确红包通知，等待新红包进入微信窗口");
        long[] delays = {80L, 160L, 280L, 450L, 700L, 1100L, 1700L, 2600L};
        for (long delay : delays) {
            service.handler.postDelayed(service::processPendingNotification, delay);
        }
    }

    public static String getTrustedClonePackage(Context context) {
        return prefs(context).getString(KEY_TRUSTED_CLONE, "");
    }

    public static String getLastSeenPackage(Context context) {
        return prefs(context).getString(KEY_LAST_WECHAT_PACKAGE, "");
    }

    public static boolean trustLastSeenAsClone(Context context) {
        String pkg = getLastSeenPackage(context);
        if (!isSafeClonePackage(context, pkg)) return false;
        prefs(context).edit().putString(KEY_TRUSTED_CLONE, pkg).apply();
        ReliableRedPacketAccessibilityService service = instance.get();
        if (service != null) {
            service.currentWeChatPackage = pkg;
            service.snapshotReady = false;
            service.writeIdentity("已设为可信微信分身：" + pkg);
        }
        return true;
    }

    public static void trustPackageFromRedPacketNotification(Context context, String pkg) {
        if (!isSafeClonePackage(context, pkg)) return;
        if (isMainWeChatIdentity(pkg, "")) return;
        prefs(context).edit().putString(KEY_TRUSTED_CLONE, pkg).apply();
    }

    public static String getDiagnostics(Context context) {
        SharedPreferences p = prefs(context);
        long start = p.getLong(KEY_MONITOR_START, 0L);
        String age = start <= 0L ? "未开始"
                : Math.max(0L, (System.currentTimeMillis() - start) / 1000L) + "秒";
        long diag = p.getLong(KEY_LAST_DIAG_TIME, 0L);
        String diagAge = diag <= 0L ? "无"
                : Math.max(0L, (System.currentTimeMillis() - diag) / 1000L) + "秒前";

        return "监控模式：只处理点击开始监控之后的新红包"
                + "\n本次监控已运行：" + age
                + "\n历史基线红包数：" + p.getInt(KEY_BASELINE_COUNT, 0)
                + "\n本次发现新红包数：" + p.getInt(KEY_NEW_PACKET_COUNT, 0)
                + "\n已选中的微信窗口包名：" + p.getString(KEY_LAST_WECHAT_PACKAGE, "未找到")
                + "\n身份判断：" + p.getString(KEY_LAST_IDENTITY, "未检测")
                + "\n可信分身包名：" + p.getString(KEY_TRUSTED_CLONE, "未设置")
                + "\n微信窗口状态：" + p.getString(KEY_LAST_ROOT, "未检测")
                + "\n覆盖层/系统窗口：" + p.getString(KEY_LAST_OVERLAY, "未记录")
                + "\n当前可见红包候选数：" + p.getInt(KEY_LAST_PACKET_COUNT, -1)
                + "\n‘开/開’节点数：" + p.getInt(KEY_LAST_OPEN_COUNT, -1)
                + "\n最后动作：" + p.getString(KEY_LAST_ACTION, "暂无动作")
                + "\n诊断更新时间：" + diagAge;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void startMonitoringSession() {
        handler.removeCallbacksAndMessages(null);
        sequence++;
        resetRuntime();
        monitorStartedElapsed = SystemClock.uptimeMillis();
        pageSettlingUntil = monitorStartedElapsed + 350L;
        prefs(this).edit()
                .putLong(KEY_MONITOR_START, System.currentTimeMillis())
                .putInt(KEY_BASELINE_COUNT, 0)
                .putInt(KEY_NEW_PACKET_COUNT, 0)
                .apply();
        writeAction("监控已从现在开始；旧红包只建立基线，不领取");
        handler.postDelayed(this::refreshIdleState, 350L);
        handler.postDelayed(heartbeat, 900L);
    }

    private void stopMonitoringSession() {
        handler.removeCallbacksAndMessages(null);
        sequence++;
        resetRuntime();
        writeAction("监控已停止");
    }

    private void resetRuntime() {
        state = WAIT_PACKET;
        stateSince = SystemClock.uptimeMillis();
        snapshotReady = false;
        visiblePacketCounts.clear();
        lastBottomPacketKey = "";
        pendingNotificationUntil = 0L;
        pendingNotificationPackage = "";
        currentWeChatPackage = "";
    }

    private void beginPageSettle(long now) {
        pageSettlingUntil = now + 420L;
        snapshotReady = false;
        visiblePacketCounts.clear();
        lastBottomPacketKey = "";
        writeAction("进入/切换/滚动微信页面：重新登记历史基线，不点击旧红包");
        scheduleSnapshotAfterSettle();
    }

    private void scheduleSnapshotAfterSettle() {
        int token = ++sequence;
        long delay = Math.max(80L, pageSettlingUntil - SystemClock.uptimeMillis() + 60L);
        handler.postDelayed(() -> {
            if (token != sequence || state != WAIT_PACKET) return;
            AccessibilityNodeInfo root = getTrustedWeChatRoot(true);
            if (root != null) buildSnapshot(root, "历史基线已建立，只等待之后新出现的红包");
        }, delay);
    }

    private void refreshIdleState() {
        if (!isAutomationEnabled(this) || state != WAIT_PACKET) return;
        AccessibilityNodeInfo root = getTrustedWeChatRoot(true);
        if (root == null) return;
        updateDiagnostics(root);

        long now = SystemClock.uptimeMillis();
        String pkg = nodePackage(root);
        if (hasPendingNotificationFor(pkg, now)) {
            processPendingNotification();
            return;
        }
        if (!snapshotReady && now >= pageSettlingUntil) {
            buildSnapshot(root, "历史基线已建立，只等待之后新出现的红包");
        }
    }

    private void buildSnapshot(AccessibilityNodeInfo root, String action) {
        List<PacketCandidate> packets = findPacketCandidates(root);
        replaceSnapshot(packets);
        prefs(this).edit()
                .putInt(KEY_BASELINE_COUNT, packets.size())
                .putInt(KEY_LAST_PACKET_COUNT, packets.size())
                .apply();
        snapshotReady = true;
        writeAction(action + "（" + packets.size() + "个）");
    }

    private void replaceSnapshot(List<PacketCandidate> packets) {
        visiblePacketCounts.clear();
        PacketCandidate bottom = null;
        for (PacketCandidate p : packets) {
            visiblePacketCounts.put(p.semanticKey,
                    visiblePacketCounts.getOrDefault(p.semanticKey, 0) + 1);
            if (bottom == null || p.rect.bottom > bottom.rect.bottom) bottom = p;
        }
        lastBottomPacketKey = bottom == null ? "" : bottom.semanticKey;
    }

    private boolean detectAndHandleNewPacket(
            AccessibilityNodeInfo root, boolean eventMentionsPacket, boolean notificationDriven) {
        List<PacketCandidate> current = findPacketCandidates(root);
        updateDiagnostics(root);

        if (!snapshotReady) {
            buildSnapshot(root, "首次快照建立，旧红包不处理");
            return false;
        }

        Map<String, Integer> nowCounts = countByKey(current);
        PacketCandidate newest = null;

        for (PacketCandidate p : current) {
            int before = visiblePacketCounts.getOrDefault(p.semanticKey, 0);
            int now = nowCounts.getOrDefault(p.semanticKey, 0);
            if (now > before) {
                if (newest == null || p.rect.bottom > newest.rect.bottom) newest = p;
            }
        }

        PacketCandidate bottom = bottommost(current);
        boolean bottomChanged = bottom != null && !bottom.semanticKey.equals(lastBottomPacketKey);
        boolean bottomNearNewMessageArea = bottom != null && isNearBottomOfRoot(bottom.rect, root);

        if (newest == null && eventMentionsPacket && bottomChanged && bottomNearNewMessageArea) {
            newest = bottom;
        }
        if (newest == null && notificationDriven && bottomNearNewMessageArea) {
            newest = bottom;
        }

        replaceSnapshot(current);
        if (newest == null) return false;

        if (!notificationDriven && !eventMentionsPacket) return false;
        return clickNewPacket(newest);
    }

    private void processPendingNotification() {
        if (!isAutomationEnabled(this) || state != WAIT_PACKET) return;
        long now = SystemClock.uptimeMillis();
        if (now > pendingNotificationUntil) return;

        AccessibilityNodeInfo root = getTrustedWeChatRoot(true);
        if (root == null) return;
        String pkg = nodePackage(root);
        if (!hasPendingNotificationFor(pkg, now)) return;

        List<PacketCandidate> current = findPacketCandidates(root);
        if (current.isEmpty()) return;

        if (!snapshotReady) {
            PacketCandidate bottom = bottommost(current);
            if (bottom != null && isNearBottomOfRoot(bottom.rect, root)) {
                replaceSnapshot(current);
                clickNewPacket(bottom);
            }
            return;
        }
        detectAndHandleNewPacket(root, true, true);
    }

    private boolean clickNewPacket(PacketCandidate packet) {
        if (packet == null || !isTrustedWeChatWindowPresent()) return false;
        boolean clicked = false;
        try {
            if (packet.node.isClickable()) {
                clicked = packet.node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        } catch (Throwable ignored) {}
        if (!clicked) clicked = clickAt(packet.rect.exactCenterX(), packet.rect.exactCenterY());
        if (!clicked) return false;

        pendingNotificationUntil = 0L;
        pendingNotificationPackage = "";
        int count = prefs(this).getInt(KEY_NEW_PACKET_COUNT, 0) + 1;
        prefs(this).edit().putInt(KEY_NEW_PACKET_COUNT, count).apply();
        state = WAIT_OPEN;
        stateSince = SystemClock.uptimeMillis();
        writeAction("检测到启动监控后的新红包，已点击1次，等待开按钮");

        int token = ++sequence;
        long[] delays = {15L, 35L, 65L, 100L, 150L, 230L, 360L, 560L, 900L, 1500L};
        for (long d : delays) {
            handler.postDelayed(() -> {
                if (token == sequence && state == WAIT_OPEN) processOpenResultState();
            }, d);
        }
        return true;
    }

    private void processOpenResultState() {
        if (!isAutomationEnabled(this) || state == WAIT_PACKET) return;
        AccessibilityNodeInfo root = getTrustedWeChatRoot(true);
        if (root == null) return;
        long now = SystemClock.uptimeMillis();
        updateDiagnostics(root);

        if (processOpenOrResult(root, now, false)) return;

        if (state == WAIT_OPEN) {
            long elapsed = now - stateSince;
            if (elapsed >= 130L && elapsed < 460L && clickExpectedOpenCenterOnce(root)) {
                writeAction("未暴露开按钮文字，已点击微信红包弹窗中心1次");
                enterWaitResult();
                return;
            }
            if (elapsed > 1900L) {
                if (backOnce()) writeAction("未找到开按钮，已退出红包弹窗");
                enterWaitClear();
            }
            return;
        }

        if (state == WAIT_RESULT) {
            long elapsed = now - stateSince;
            if (isRedPacketResultVisible(root) || elapsed >= 950L) {
                if (backOnce()) writeAction("领取流程完成，已返回微信聊天");
                enterWaitClear();
            }
            return;
        }

        if (state == WAIT_CLEAR && now - stateSince >= 220L) {
            state = WAIT_PACKET;
            stateSince = now;
            pageSettlingUntil = now + 300L;
            snapshotReady = false;
            visiblePacketCounts.clear();
            handler.postDelayed(() -> {
                AccessibilityNodeInfo chatRoot = getTrustedWeChatRoot(true);
                if (chatRoot != null && state == WAIT_PACKET) {
                    buildSnapshot(chatRoot, "领取后聊天基线已重建");
                }
            }, 360L);
        }
    }

    private boolean processOpenOrResult(AccessibilityNodeInfo scope, long now, boolean fromEvent) {
        if (state == WAIT_OPEN) {
            if (subtreeContainsAny(scope, FINISHED_TERMS, 6)) {
                if (!fromEvent && backOnce()) {
                    writeAction("红包已领取/已过期，已返回聊天");
                    enterWaitClear();
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
                if (!clicked && r != null) clicked = clickAt(r.exactCenterX(), r.exactCenterY());
                if (clicked) {
                    writeAction("已点击‘开/開’1次");
                    enterWaitResult();
                    return true;
                }
            }
        }
        return false;
    }

    private void enterWaitResult() {
        state = WAIT_RESULT;
        stateSince = SystemClock.uptimeMillis();
        int token = ++sequence;
        long[] delays = {40L, 80L, 140L, 240L, 400L, 650L, 950L};
        for (long d : delays) {
            handler.postDelayed(() -> {
                if (token == sequence && state == WAIT_RESULT) processOpenResultState();
            }, d);
        }
    }

    private void enterWaitClear() {
        state = WAIT_CLEAR;
        stateSince = SystemClock.uptimeMillis();
        sequence++;
    }

    private boolean hasPendingNotificationFor(String pkg, long now) {
        if (now > pendingNotificationUntil) return false;
        if (pendingNotificationPackage.isEmpty()) return true;
        if (pendingNotificationPackage.equals(pkg)) return true;
        return isMainWeChatIdentity(pendingNotificationPackage, "")
                && isMainWeChatIdentity(pkg, "");
    }

    private AccessibilityNodeInfo getTrustedWeChatRoot(boolean writeDiag) {
        AccessibilityNodeInfo active = null;
        try { active = getRootInActiveWindow(); } catch (Throwable ignored) {}
        if (isAcceptedRoot(active)) {
            String pkg = nodePackage(active);
            currentWeChatPackage = pkg;
            if (writeDiag) writeRootStatus("已取得微信活动窗口：" + pkg);
            return active;
        }

        AccessibilityNodeInfo bestRoot = null;
        int bestScore = Integer.MIN_VALUE;
        int inspected = 0;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null) continue;
                    inspected++;
                    AccessibilityNodeInfo root = null;
                    try { root = w.getRoot(); } catch (Throwable ignored) {}
                    if (!isAcceptedRoot(root)) continue;

                    int score = 0;
                    try {
                        if (w.isActive()) score += 1000;
                        if (w.isFocused()) score += 700;
                        if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) score += 300;
                        score += w.getLayer();
                    } catch (Throwable ignored) {}
                    if (score > bestScore) {
                        bestScore = score;
                        bestRoot = root;
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (bestRoot != null) {
            String pkg = nodePackage(bestRoot);
            currentWeChatPackage = pkg;
            if (writeDiag) {
                writeRootStatus("ColorOS覆盖层下已找到真实微信窗口：" + pkg
                        + "（枚举窗口" + inspected + "个）");
            }
            return bestRoot;
        }

        if (writeDiag) writeRootStatus("未找到可操作的微信窗口（已检查活动窗口和全部交互窗口）");
        return null;
    }

    private boolean isAcceptedRoot(AccessibilityNodeInfo root) {
        if (root == null) return false;
        String pkg = nodePackage(root);
        String cls = stringOf(root.getClassName());
        return !pkg.equals(getPackageName()) && isAcceptedIdentity(pkg, cls);
    }

    private boolean isTrustedWeChatWindowPresent() {
        return getTrustedWeChatRoot(false) != null;
    }

    private boolean clickAt(float x, float y) {
        if (!isTrustedWeChatWindowPresent()) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 2))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean backOnce() {
        return isTrustedWeChatWindowPresent() && performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private boolean clickExpectedOpenCenterOnce(AccessibilityNodeInfo root) {
        Rect r = bounds(root);
        if (r == null) return false;
        return clickAt(r.exactCenterX(), r.top + r.height() * 0.525f);
    }

    private boolean isRedPacketResultVisible(AccessibilityNodeInfo root) {
        return subtreeContainsAny(root, FINISHED_TERMS, 7);
    }

    private List<PacketCandidate> findPacketCandidates(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> labels = collectNodesByText(root, "红包", 40);
        List<PacketCandidate> out = new ArrayList<>();
        Rect rootRect = bounds(root);

        for (AccessibilityNodeInfo label : labels) {
            if (label == null || !isVisible(label)) continue;
            String text = compact(nodeText(label));
            if (!text.contains("红包") || containsFinishedTerm(text)) continue;

            AccessibilityNodeInfo card = chooseNearestPacketContainer(label, rootRect);
            if (card == null) card = label;
            if (subtreeContainsAny(card, FINISHED_TERMS, 2)) continue;

            Rect r = bounds(card);
            if (r == null) r = bounds(label);
            if (!isMessageLikeRect(r, rootRect)) continue;

            String local = compact(collectLocalText(card, 3, 180));
            boolean plausible = text.length() <= 24
                    || text.contains("微信红包")
                    || text.contains("领取红包")
                    || text.contains("恭喜发财")
                    || local.contains("红包");
            if (!plausible) continue;

            String semantic = semanticPacketKey(label, card, r, text, local);
            out.add(new PacketCandidate(card, r, semantic));
        }
        return out;
    }

    private static String semanticPacketKey(
            AccessibilityNodeInfo label, AccessibilityNodeInfo card, Rect r, String text, String local) {
        String cls = stringOf(card == null ? null : card.getClassName());
        String id = card == null || card.getViewIdResourceName() == null
                ? "" : card.getViewIdResourceName();
        int qw = r == null ? 0 : Math.max(1, r.width() / 16);
        int qh = r == null ? 0 : Math.max(1, r.height() / 16);
        String labelDesc = compact(nodeText(label));
        String normalizedLocal = local.length() > 120 ? local.substring(0, 120) : local;
        return nodePackage(label) + "|" + cls + "|" + id + "|" + qw + "x" + qh
                + "|" + text + "|" + labelDesc + "|" + normalizedLocal;
    }

    private static Map<String, Integer> countByKey(List<PacketCandidate> packets) {
        Map<String, Integer> map = new HashMap<>();
        for (PacketCandidate p : packets) {
            map.put(p.semanticKey, map.getOrDefault(p.semanticKey, 0) + 1);
        }
        return map;
    }

    private static PacketCandidate bottommost(List<PacketCandidate> packets) {
        PacketCandidate best = null;
        for (PacketCandidate p : packets) {
            if (best == null || p.rect.bottom > best.rect.bottom) best = p;
        }
        return best;
    }

    private static boolean isNearBottomOfRoot(Rect packet, AccessibilityNodeInfo root) {
        Rect rr = boundsStatic(root);
        if (packet == null || rr == null || rr.height() <= 0) return false;
        float relative = (packet.centerY() - rr.top) / (float) rr.height();
        return relative >= 0.45f && packet.bottom <= rr.bottom + 4;
    }

    private AccessibilityNodeInfo findBestOpenNode(AccessibilityNodeInfo scope) {
        AccessibilityNodeInfo best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        Rect reference = bounds(scope);
        for (String term : new String[]{"開", "开"}) {
            for (AccessibilityNodeInfo n : collectNodesByText(scope, term, 16)) {
                if (n == null || !isVisible(n)) continue;
                if (!isExactOpen(n.getText()) && !isExactOpen(n.getContentDescription())) continue;
                AccessibilityNodeInfo c = findClickableAncestor(n, 6);
                if (c == null) c = n;
                Rect r = bounds(c);
                if (r == null) continue;
                double score = 0.0;
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

    private boolean nodeBelongsToAcceptedWeChat(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String pkg = nodePackage(node);
        return isAcceptedIdentity(pkg, "");
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
        return !isOverlayOrSystemNoise(pkg);
    }

    private static boolean isOverlayOrSystemNoise(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.equals("android")
                || p.startsWith("com.android.")
                || p.startsWith("android.")
                || p.contains("systemui")
                || p.contains("launcher")
                || p.contains("coloros.assistantscreen")
                || p.contains("oplus.assistantscreen")
                || p.contains("smartsidebar")
                || p.contains("floatassistant");
    }

    private static AccessibilityNodeInfo safeEventSource(AccessibilityEvent event) {
        try { return event.getSource(); }
        catch (Throwable ignored) { return null; }
    }

    private static AccessibilityNodeInfo chooseNearestPacketContainer(
            AccessibilityNodeInfo label, Rect rootRect) {
        AccessibilityNodeInfo cur = label;
        AccessibilityNodeInfo nearest = label;
        for (int i = 0; cur != null && i <= 6; i++) {
            Rect r = boundsStatic(cur);
            if (isMessageLikeRect(r, rootRect) && r.width() >= 60 && r.height() >= 28) {
                nearest = cur;
                try {
                    if (cur.isVisibleToUser() && cur.isClickable()) return cur;
                } catch (Throwable ignored) {}
            }
            cur = cur.getParent();
        }
        return nearest;
    }

    private static boolean isMessageLikeRect(Rect r, Rect rootRect) {
        if (r == null || r.isEmpty() || r.width() < 20 || r.height() < 18 || r.height() > 460) {
            return false;
        }
        return rootRect == null || rootRect.isEmpty()
                || !(r.width() > rootRect.width() * 0.96f
                && r.height() > rootRect.height() * 0.58f);
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
        collectContaining(root, term, out, 0, new int[]{0}, maxResults);
        return out;
    }

    private static void collectContaining(
            AccessibilityNodeInfo node, String term, List<AccessibilityNodeInfo> out,
            int depth, int[] visited, int maxResults) {
        if (node == null || depth > 14 || visited[0]++ > 650 || out.size() >= maxResults) return;
        try {
            if (node.isVisibleToUser() && nodeText(node).contains(term)) out.add(node);
            int count = Math.min(node.getChildCount(), 32);
            for (int i = 0; i < count; i++) {
                collectContaining(node.getChild(i), term, out,
                        depth + 1, visited, maxResults);
            }
        } catch (Throwable ignored) {}
    }

    private static String collectLocalText(AccessibilityNodeInfo node, int depth, int maxChars) {
        StringBuilder sb = new StringBuilder();
        appendLocalText(node, depth, sb, maxChars, new int[]{0});
        return sb.toString();
    }

    private static void appendLocalText(
            AccessibilityNodeInfo node, int depth, StringBuilder sb, int maxChars, int[] visited) {
        if (node == null || depth < 0 || sb.length() >= maxChars || visited[0]++ > 90) return;
        String t = nodeText(node);
        if (!t.isEmpty()) {
            sb.append(t).append('|');
            if (sb.length() >= maxChars) return;
        }
        try {
            int count = Math.min(node.getChildCount(), 20);
            for (int i = 0; i < count; i++) {
                appendLocalText(node.getChild(i), depth - 1, sb, maxChars, visited);
                if (sb.length() >= maxChars) return;
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

    private static boolean subtreeContains(AccessibilityNodeInfo node, String term, int depth) {
        if (node == null || term == null) return false;
        try {
            if (nodeText(node).contains(term)) return true;
            if (depth <= 0) return false;
            int count = Math.min(node.getChildCount(), 26);
            for (int i = 0; i < count; i++) {
                if (subtreeContains(node.getChild(i), term, depth - 1)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean subtreeContainsAny(
            AccessibilityNodeInfo node, String[] terms, int depth) {
        if (node == null || terms == null) return false;
        try {
            String text = nodeText(node);
            for (String term : terms) if (term != null && text.contains(term)) return true;
            if (depth <= 0) return false;
            int count = Math.min(node.getChildCount(), 26);
            for (int i = 0; i < count; i++) {
                if (subtreeContainsAny(node.getChild(i), terms, depth - 1)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean containsFinishedTerm(String text) {
        if (text == null) return false;
        for (String term : FINISHED_TERMS) if (text.contains(term)) return true;
        return false;
    }

    private static boolean isVisible(AccessibilityNodeInfo node) {
        try { return node != null && node.isVisibleToUser(); }
        catch (Throwable ignored) { return false; }
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

    private static String nodeText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        String t = node.getText() == null ? "" : node.getText().toString();
        String d = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        return (t + " " + d).trim();
    }

    private static String nodePackage(AccessibilityNodeInfo node) {
        return node == null || node.getPackageName() == null ? "" : node.getPackageName().toString();
    }

    private static String compact(String s) {
        return s == null ? "" : s.replace(" ", "").replace("\n", "").trim();
    }

    private static String stringOf(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    private static boolean isExactOpen(CharSequence value) {
        if (value == null) return false;
        String s = value.toString().trim();
        return "開".equals(s) || "开".equals(s);
    }

    private void updateDiagnostics(AccessibilityNodeInfo root) {
        prefs(this).edit()
                .putInt(KEY_LAST_PACKET_COUNT, findPacketCandidates(root).size())
                .putInt(KEY_LAST_OPEN_COUNT, countOpen(root))
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis())
                .apply();
    }

    private static int countOpen(AccessibilityNodeInfo root) {
        int count = 0;
        for (String term : new String[]{"開", "开"}) {
            for (AccessibilityNodeInfo n : collectNodesByText(root, term, 16)) {
                if (isExactOpen(n.getText()) || isExactOpen(n.getContentDescription())) count++;
            }
        }
        return count;
    }

    private void writeIdentity(String value) {
        prefs(this).edit().putString(KEY_LAST_IDENTITY, value)
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis()).apply();
    }

    private void writeRootStatus(String value) {
        prefs(this).edit().putString(KEY_LAST_ROOT, value)
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis()).apply();
    }

    private void writeOverlay(String value) {
        prefs(this).edit().putString(KEY_LAST_OVERLAY, value == null ? "" : value)
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis()).apply();
    }

    private void writeAction(String value) {
        prefs(this).edit().putString(KEY_LAST_ACTION, value)
                .putLong(KEY_LAST_DIAG_TIME, System.currentTimeMillis()).apply();
    }

    private static final class PacketCandidate {
        final AccessibilityNodeInfo node;
        final Rect rect;
        final String semanticKey;

        PacketCandidate(AccessibilityNodeInfo node, Rect rect, String semanticKey) {
            this.node = node;
            this.rect = rect;
            this.semanticKey = semanticKey;
        }
    }
}
