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
        title.setText("微信红包无障碍诊断修正版");
        title.setTextSize(23f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView explain = new TextView(this);
        explain.setText("本版仍然完全无视觉、无录屏。\n\n除了自动领取，现在会记录微信/分身到底向无障碍暴露了什么：包名、是否识别成微信、是否能读到红包节点、是否能读到‘开/開’，以及最后执行到哪一步。这样如果仍然不动作，可以直接确定根因。\n\n系统通知明确包含红包时，会自动学习对应微信分身包名。");
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
        start.setText("3. 开始自动领取");
        start.setOnClickListener(v -> {
            if (!AutoClickAccessibilityService.isConnected()) {
                Toast.makeText(this, "请先开启本软件的无障碍权限", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            AutoClickAccessibilityService.setAutomationEnabled(this, true);
            AutoClickAccessibilityService.requestImmediateCheck();
            Toast.makeText(this, "已启动，请进入主微信或微信分身红包聊天", Toast.LENGTH_LONG).show();
            updateAll();
        });
        root.addView(start, buttonParams());

        Button pairClone = new Button(this);
        pairClone.setText("将最近打开的应用设为微信分身");
        pairClone.setOnClickListener(v -> {
            if (AutoClickAccessibilityService.trustLastSeenAsClone(this)) {
                Toast.makeText(this, "已把最近应用设为可信微信分身", Toast.LENGTH_LONG).show();
                AutoClickAccessibilityService.requestImmediateCheck();
            } else {
                Toast.makeText(this, "还没有记录到可设置的最近应用", Toast.LENGTH_LONG).show();
            }
            updateAll();
        });
        root.addView(pairClone, buttonParams());

        Button refresh = new Button(this);
        refresh.setText("刷新诊断结果");
        refresh.setOnClickListener(v -> {
            AutoClickAccessibilityService.requestImmediateCheck();
            diagnosticView.postDelayed(this::updateAll, 250L);
        });
        root.addView(refresh, buttonParams());

        Button stop = new Button(this);
        stop.setText("停止自动领取");
        stop.setOnClickListener(v -> {
            AutoClickAccessibilityService.setAutomationEnabled(this, false);
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
        note.setText("测试方法：先点开始，然后进入能看见未领取红包的微信聊天停留2秒，再回到本软件看‘诊断信息’。\n\n如果你用的是分身且诊断显示‘未识别为微信/分身’，进入分身一次后返回本软件，点‘将最近打开的应用设为微信分身’，再重新进入分身。\n\n如果诊断里‘含红包节点数’始终为0，说明当前微信版本没有把红包信息暴露给 Android 无障碍；这种情况下纯无障碍方案本身就无法识别红包，需要换成其他触发方式，而不是继续调点击速度。");
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
        String access = AutoClickAccessibilityService.isConnected() ? "已开启" : "未开启";
        String notify = WeChatNotificationListener.isNotificationAccessGranted(this)
                ? "已开启" : "未开启";
        String run = AutoClickAccessibilityService.isAutomationEnabled(this)
                ? "正在等待红包" : "已停止";
        statusView.setText("通知读取：" + notify
                + "\n无障碍权限：" + access
                + "\n自动领取状态：" + run);
        diagnosticView.setText(AutoClickAccessibilityService.getDiagnostics(this));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
