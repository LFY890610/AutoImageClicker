package com.lfy.autoimageclicker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView statusView;
    private TextView diagnosticView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(22);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("微信红包新红包监控版");
        title.setTextSize(23f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView explain = new TextView(this);
        explain.setText("本版仍然完全无视觉、无录屏。\n\n核心已改为多窗口模式：即使 ColorOS 侧边栏/悬浮助手抢占当前活动窗口，也会从全部无障碍窗口中找到真正的微信/微信分身。\n\n点击开始监控以后，进入聊天时只登记当前已有红包为历史基线；之后新出现的红包才会自动点击、开红包并返回。");
        explain.setTextSize(15.5f);
        explain.setPadding(0, dp(16), 0, dp(14));
        root.addView(explain, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button notification = new Button(this);
        notification.setText("1. 开启微信通知读取权限");
        notification.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(notification, buttonParams());

        Button accessibility = new Button(this);
        accessibility.setText("2. 开启无障碍自动点击权限");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, buttonParams());

        Button start = new Button(this);
        start.setText("3. 从现在开始监控新红包");
        start.setOnClickListener(v -> {
            if (!ReliableRedPacketAccessibilityService.isConnected()) {
                Toast.makeText(this, "请先开启本软件的无障碍权限", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            ReliableRedPacketAccessibilityService.setAutomationEnabled(this, true);
            Toast.makeText(this, "监控已从现在开始；以前的红包不会补领", Toast.LENGTH_LONG).show();
            updateAll();
        });
        root.addView(start, buttonParams());

        Button pairClone = new Button(this);
        pairClone.setText("将最近识别到的微信应用设为分身");
        pairClone.setOnClickListener(v -> {
            if (ReliableRedPacketAccessibilityService.trustLastSeenAsClone(this)) {
                Toast.makeText(this, "已设置可信微信分身", Toast.LENGTH_LONG).show();
                ReliableRedPacketAccessibilityService.requestImmediateCheck();
            } else {
                Toast.makeText(this, "尚未记录到可设置的微信/分身包名", Toast.LENGTH_LONG).show();
            }
            updateAll();
        });
        root.addView(pairClone, buttonParams());

        Button refresh = new Button(this);
        refresh.setText("刷新诊断结果");
        refresh.setOnClickListener(v -> {
            ReliableRedPacketAccessibilityService.requestImmediateCheck();
            diagnosticView.postDelayed(this::updateAll, 300L);
        });
        root.addView(refresh, buttonParams());

        Button stop = new Button(this);
        stop.setText("停止监控");
        stop.setOnClickListener(v -> {
            ReliableRedPacketAccessibilityService.setAutomationEnabled(this, false);
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
            updateAll();
        });
        root.addView(stop, buttonParams());

        statusView = new TextView(this);
        statusView.setTextSize(16f);
        statusView.setPadding(0, dp(18), 0, dp(10));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView diagTitle = new TextView(this);
        diagTitle.setText("诊断信息");
        diagTitle.setTextSize(18f);
        root.addView(diagTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        diagnosticView = new TextView(this);
        diagnosticView.setTextSize(13.5f);
        diagnosticView.setTextIsSelectable(true);
        diagnosticView.setPadding(0, dp(8), 0, dp(14));
        root.addView(diagnosticView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("正确测试：先点‘从现在开始监控新红包’，再进入目标微信群。群里原来已经存在的红包只会登记为历史基线，不会点击。保持群聊在前台，再让别人新发一个红包。\n\n诊断里的‘微信窗口状态’如果显示‘ColorOS覆盖层下已找到真实微信窗口’，说明多窗口修复已经生效。若收到明确红包通知，本版也会把它作为新红包信号使用。安全锁屏不会被绕过。");
        note.setTextSize(13.5f);
        root.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
        updateAll();
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        p.setMargins(0, dp(6), 0, dp(6));
        return p;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) updateAll();
    }

    private void updateAll() {
        String access = ReliableRedPacketAccessibilityService.isConnected() ? "已开启" : "未开启";
        String notify = WeChatNotificationListener.isNotificationAccessGranted(this)
                ? "已开启" : "未开启";
        String run = ReliableRedPacketAccessibilityService.isAutomationEnabled(this)
                ? "正在监控启动后的新红包" : "已停止";
        statusView.setText("通知读取：" + notify
                + "\n无障碍权限：" + access
                + "\n监控状态：" + run);
        diagnosticView.setText(ReliableRedPacketAccessibilityService.getDiagnostics(this));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
