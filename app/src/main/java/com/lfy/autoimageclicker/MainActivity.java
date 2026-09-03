package com.lfy.autoimageclicker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private MediaProjectionManager projectionManager;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("微信红包前台极速高准确版");
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView explain = new TextView(this);
        explain.setText("固定流程：严格识别真正红包 → 点击1次 → 识别中央“开/開” → 点击1次 → 领取完成后自动返回聊天。\n\n同一个红包完成后不会再次重复处理。重点只做准确率和速度。");
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
        start.setText("2. 开始极速识别");
        start.setOnClickListener(v -> startRecognition());
        root.addView(start, buttonParams());

        Button stop = new Button(this);
        stop.setText("停止识别");
        stop.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptureService.class);
            i.setAction(CaptureService.ACTION_STOP);
            startService(i);
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
        note.setText("使用方法：开启无障碍 → 点“开始极速识别” → 允许屏幕共享/录制 → 切回微信目标聊天界面。\n\n本版不再监听微信后台通知，也不会自动跳转聊天。第一步使用严格视觉结构识别避免把表情当红包；高置信度红包第一帧即可点击，普通置信度只多确认1帧。中央“开/開”只点击1次，随后自动返回。");
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

    private void startRecognition() {
        if (!AutoClickAccessibilityService.isConnected()) {
            Toast.makeText(this, "请先开启本软件的无障碍权限", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        try {
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this, "无法请求屏幕捕获权限：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, CaptureService.class);
                service.setAction(CaptureService.ACTION_START);
                service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
                service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
                Toast.makeText(this, "极速识别已启动，请切回微信聊天界面", Toast.LENGTH_LONG).show();
                statusView.postDelayed(this::updateStatus, 500);
            } else {
                Toast.makeText(this, "没有允许屏幕捕获，识别未启动", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) updateStatus();
    }

    private void updateStatus() {
        String click = AutoClickAccessibilityService.isConnected() ? "已开启" : "未开启";
        String run = CaptureService.isRunning() ? "正在极速识别" : "已停止";
        statusView.setText("无障碍权限：" + click + "\n识别状态：" + run);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
