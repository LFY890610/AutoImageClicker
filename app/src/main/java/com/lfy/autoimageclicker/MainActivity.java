package com.lfy.autoimageclicker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("微信红包纯无障碍事件极速版");
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView explain = new TextView(this);
        explain.setText("完全无视觉、无录屏。主微信和系统微信分身都按事件直达处理。\n\n流程：微信节点发生变化 → 事件节点直接查“微信红包”并立即点1次 → 红包弹窗事件直接查“开/開”或中央控件并点1次 → 领取结果出现后立即返回聊天。\n\n只有事件信息不足时才做少量整窗口兜底查询。");
        explain.setTextSize(16f);
        explain.setPadding(0, dp(18), 0, dp(18));
        root.addView(explain, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button accessibility = new Button(this);
        accessibility.setText("1. 开启无障碍自动点击权限");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, buttonParams());

        Button start = new Button(this);
        start.setText("2. 开始事件极速自动领取");
        start.setOnClickListener(v -> {
            if (!AutoClickAccessibilityService.isConnected()) {
                Toast.makeText(this, "请先开启本软件的无障碍权限", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            AutoClickAccessibilityService.setAutomationEnabled(this, true);
            Toast.makeText(this, "已启动，可切回主微信或微信分身聊天界面", Toast.LENGTH_LONG).show();
            updateStatus();
        });
        root.addView(start, buttonParams());

        Button stop = new Button(this);
        stop.setText("停止自动领取");
        stop.setOnClickListener(v -> {
            AutoClickAccessibilityService.setAutomationEnabled(this, false);
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
            updateStatus();
        });
        root.addView(stop, buttonParams());

        statusView = new TextView(this);
        statusView.setTextSize(16f);
        statusView.setPadding(0, dp(22), 0, 0);
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("使用方法：开启无障碍 → 点“开始事件极速自动领取” → 切回主微信或微信分身的目标聊天。\n\n本版不使用屏幕共享、录制或视觉识别。系统级微信分身通常可直接兼容；如果某个第三方分身把微信完全重新打包并隐藏无障碍节点，则需要针对该分身包单独适配。锁屏且存在安全解锁时不能绕过系统锁屏自动领取。");
        note.setTextSize(13.5f);
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        updateStatus();
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        p.setMargins(0, dp(8), 0, dp(8));
        return p;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) updateStatus();
    }

    private void updateStatus() {
        String access = AutoClickAccessibilityService.isConnected() ? "已开启" : "未开启";
        String run = AutoClickAccessibilityService.isAutomationEnabled(this)
                ? "事件极速模式正在等待红包" : "已停止";
        statusView.setText("无障碍权限：" + access + "\n自动领取状态：" + run);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
