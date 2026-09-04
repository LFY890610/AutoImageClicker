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
        title.setText("微信红包无障碍可靠极速版");
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView explain = new TextView(this);
        explain.setText("本版仍然完全没有视觉识别和屏幕录制。\n\n前台：微信事件出现时立即检查节点，同时增加低频节点保底，避免微信/分身不发预期事件时完全失灵。\n\n后台：系统通知明确出现“红包”提示时，低功耗通知监听会尝试打开对应微信/分身会话。进入聊天后继续完成：红包1次 → 开1次 → 自动返回。");
        explain.setTextSize(16f);
        explain.setPadding(0, dp(18), 0, dp(18));
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
        start.setText("3. 开始可靠极速自动领取");
        start.setOnClickListener(v -> {
            if (!AutoClickAccessibilityService.isConnected()) {
                Toast.makeText(this, "请先开启本软件的无障碍权限", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                return;
            }
            AutoClickAccessibilityService.setAutomationEnabled(this, true);
            AutoClickAccessibilityService.requestImmediateCheck();
            Toast.makeText(this, "已启动，可切回主微信或微信分身", Toast.LENGTH_LONG).show();
            updateStatus();
        });
        root.addView(start, buttonParams());

        Button test = new Button(this);
        test.setText("立即检查当前微信页面");
        test.setOnClickListener(v -> {
            AutoClickAccessibilityService.requestImmediateCheck();
            Toast.makeText(this, "已立即检查当前微信页面", Toast.LENGTH_SHORT).show();
        });
        root.addView(test, buttonParams());

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
        note.setText("使用方法：先开启通知读取和无障碍，再点“开始可靠极速自动领取”。\n\n如果微信通知内容本身被系统隐藏成“你收到一条新消息”，程序无法从后台判断它是不是红包，因此不会盲目打开每一条普通微信通知。即使后台没有跳转，只要你手动进入含红包的微信聊天页面，本版也会用无障碍事件+低频节点保底继续处理。锁屏且存在安全解锁时不会绕过系统锁屏。");
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
        p.setMargins(0, dp(7), 0, dp(7));
        return p;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) updateStatus();
    }

    private void updateStatus() {
        String access = AutoClickAccessibilityService.isConnected() ? "已开启" : "未开启";
        String notify = WeChatNotificationListener.isNotificationAccessGranted(this)
                ? "已开启" : "未开启";
        String run = AutoClickAccessibilityService.isAutomationEnabled(this)
                ? "正在等待红包" : "已停止";
        statusView.setText("通知读取：" + notify
                + "\n无障碍权限：" + access
                + "\n自动领取状态：" + run);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
