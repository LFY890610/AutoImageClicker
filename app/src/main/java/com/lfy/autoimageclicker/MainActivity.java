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
        title.setText("微信红包新消息监控版");
        title.setTextSize(23f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView explain = new TextView(this);
        explain.setText("只处理你点“开始监控”以后新出现的红包。\n\n进入微信聊天、切换群聊或滚动聊天时，当前屏幕已经存在的红包只建立历史基线，不点击。之后无障碍事件中新增加的红包才会立即处理。\n\n系统通知如果在监控开始后明确包含红包，会把对应微信/分身的新红包标记为本次新消息。仍然完全无视觉、无录屏。");
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
            if (!AutoClickAccessibilityService.isConnected()) {
                Toast.makeText(this, "请先开启本软件的无障碍权限", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            AutoClickAccessibilityService.setAutomationEnabled(this, true);
            Toast.makeText(this,
                    "监控已从现在开始。之后进入聊天时，已有红包只做基线，不会领取旧红包。",
                    Toast.LENGTH_LONG).show();
            updateAll();
        });
        root.addView(start, buttonParams());

        Button pairClone = new Button(this);
        pairClone.setText("将最近打开的应用设为微信分身");
        pairClone.setOnClickListener(v -> {
            if (AutoClickAccessibilityService.trustLastSeenAsClone(this)) {
                Toast.makeText(this, "已把最近应用设为可信微信分身", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "还没有记录到可设置的微信分身候选", Toast.LENGTH_LONG).show();
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
        stop.setText("停止监控");
        stop.setOnClickListener(v -> {
            AutoClickAccessibilityService.setAutomationEnabled(this, false);
            Toast.makeText(this, "已停止监控", Toast.LENGTH_SHORT).show();
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
        note.setText("正确测试方法：先进入软件点“从现在开始监控新红包”，然后进入微信目标群。此时群里原来就有的红包不会被点击，只会被记为历史基线。保持在群聊中，再让别人新发一个红包，新出现的红包才应该触发自动点击和领取。\n\n切换群聊或向上滚动查看历史消息时也不会补领旧红包。若使用微信分身且未识别，先进入分身再返回本软件，将最近应用设为微信分身。");
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
                ? "正在监控启动后的新红包" : "已停止";
        statusView.setText("通知读取：" + notify
                + "\n无障碍权限：" + access
                + "\n监控状态：" + run);
        diagnosticView.setText(AutoClickAccessibilityService.getDiagnostics(this));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
