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
        title.setText("微信红包后台全自动版");
        title.setTextSize(25f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView explain = new TextView(this);
        explain.setText("后台收到明确包含“红包”的微信通知后，自动进入对应聊天；确认真正红包后点击1次，再识别中央“开/開”并快速点击2次。\n\n第一次使用请完成下面3项授权。");
        explain.setTextSize(16f);
        explain.setPadding(0, dp(16), 0, dp(14));
        root.addView(explain, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button notification = new Button(this);
        notification.setText("1. 开启微信通知读取权限");
        notification.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Throwable e) {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        });
        root.addView(notification, buttonParams());

        Button accessibility = new Button(this);
        accessibility.setText("2. 开启无障碍自动点击权限");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, buttonParams());

        Button start = new Button(this);
        start.setText("3. 启动后台全自动识别");
        start.setOnClickListener(v -> startRecognition());
        root.addView(start, buttonParams());

        Button stop = new Button(this);
        stop.setText("停止后台识别");
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
        statusView.setPadding(0, dp(20), 0, 0);
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("使用方法：开启通知读取 → 开启无障碍 → 启动后台全自动识别 → 允许屏幕共享/录制。之后可以停留在桌面或其他应用。\n\n注意：为避免普通消息强行跳转，本版只对通知文本明确包含“红包/微信红包/恭喜发财”等特征的微信通知自动进入聊天。若微信关闭了通知内容预览，后台无法从通知中判断是否有红包。安全锁屏不会被本软件绕过。");
        note.setTextSize(13.5f);
        note.setPadding(0, dp(20), 0, 0);
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

    private void startRecognition() {
        if (!WeChatNotificationListener.isNotificationAccessGranted(this)) {
            Toast.makeText(this, "请先开启本软件的“通知使用权”", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            return;
        }
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
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(service);
                } else {
                    startService(service);
                }
                Toast.makeText(this,
                        "后台全自动识别已启动，可以离开微信或停留在其他应用",
                        Toast.LENGTH_LONG).show();
                statusView.postDelayed(this::updateStatus, 500);
            } else {
                Toast.makeText(this, "没有允许屏幕捕获，后台识别未启动", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) updateStatus();
    }

    private void updateStatus() {
        String notify = WeChatNotificationListener.isNotificationAccessGranted(this)
                ? "已开启" : "未开启";
        String click = AutoClickAccessibilityService.isConnected() ? "已开启" : "未开启";
        String run = CaptureService.isRunning() ? "后台监测中" : "已停止";
        statusView.setText("通知读取权限：" + notify
                + "\n无障碍权限：" + click
                + "\n后台识别状态：" + run);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
