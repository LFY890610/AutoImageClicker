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
        title.setText("微信红包纯无障碍低功耗版");
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView explain = new TextView(this);
        explain.setText("本版已经完全删除视觉识别和屏幕录制。\n\n流程：微信无障碍节点发现“微信红包” → 点击1次 → 查找“开/開”或中央可点击控件 → 点击1次 → 领取完成后自动返回聊天。\n\n平时不截图、不扫描屏幕，只在微信界面发生变化时检查节点，重点降低耗电并提高响应速度。");
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
        start.setText("2. 开始纯无障碍自动领取");
        start.setOnClickListener(v -> {
            if (!AutoClickAccessibilityService.isConnected()) {
                Toast.makeText(this, "请先开启本软件的无障碍权限", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            AutoClickAccessibilityService.setAutomationEnabled(this, true);
            Toast.makeText(this, "已启动，请切回微信聊天界面", Toast.LENGTH_LONG).show();
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
        note.setText("使用方法：开启无障碍 → 点“开始纯无障碍自动领取” → 切回微信目标聊天界面。\n\n本版不再申请屏幕共享/录制权限，也没有持续前台截图服务。若某个微信版本没有把“微信红包”或“开/開”相关控件暴露给 Android 无障碍系统，对应步骤就无法靠本版识别。");
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
                ? "正在等待微信红包" : "已停止";
        statusView.setText("无障碍权限：" + access + "\n自动领取状态：" + run);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
