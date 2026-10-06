package cn.mhook.overlay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import cn.mhook.mhook.R;

/**
 * 屏幕叠加辅助（实验）：无障碍截图 + 悬浮窗绘制延长线。
 * 个人自用工具：不改第三方 App，只在屏幕上叠加绘制。
 */
public class OverlayAssistActivity extends Activity {

    private TextView status;
    private TextView log;
    private ScrollView scroll;
    private TextView tvThreshold;
    private TextView tvMaxLen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_overlay_assist);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        status = findViewById(R.id.oa_status);
        log = findViewById(R.id.oa_log);
        scroll = findViewById(R.id.oa_log_scroll);
        tvThreshold = findViewById(R.id.oa_threshold);
        tvMaxLen = findViewById(R.id.oa_maxlen);

        findViewById(R.id.oa_btn_accessibility).setOnClickListener(v -> openAccessibility());
        findViewById(R.id.oa_btn_overlay).setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.oa_btn_start).setOnClickListener(v -> startAssist());
        // 悬浮控制条：显示后就能在游戏里直接开始/标定/调参，不用再回本页
        ((TextView) findViewById(R.id.oa_btn_overlay)).setOnLongClickListener(v -> {
            if (AssistCore.showPanel(OverlayAssistActivity.this)) {
                appendLog("已显示悬浮控制条（开始/母球/调参/✕）");
            } else {
                appendLog("需要先在系统设置里授权「显示在其他应用上层」");
                openOverlaySettings();
            }
            return true;
        });
        findViewById(R.id.oa_btn_stop).setOnClickListener(v -> stopAssist());
        findViewById(R.id.oa_btn_clear).setOnClickListener(v -> {
            OverlayView ov = AssistCore.view();
            if (ov != null) {
                ov.clear();
            }
            appendLog("已清空画面");
        });
        findViewById(R.id.oa_thr_minus).setOnClickListener(v -> {
            LineDetector.threshold = Math.max(8, LineDetector.threshold - 5);
            refreshParams();
        });
        findViewById(R.id.oa_thr_plus).setOnClickListener(v -> {
            LineDetector.threshold = Math.min(120, LineDetector.threshold + 5);
            refreshParams();
        });
        findViewById(R.id.oa_len_minus).setOnClickListener(v -> {
            LineDetector.maxLen = Math.max(200, LineDetector.maxLen - 100);
            refreshParams();
        });
        findViewById(R.id.oa_len_plus).setOnClickListener(v -> {
            LineDetector.maxLen = Math.min(2400, LineDetector.maxLen + 100);
            refreshParams();
        });
        refreshParams();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshParams() {
        if (tvThreshold != null) {
            tvThreshold.setText(String.valueOf(LineDetector.threshold));
        }
        if (tvMaxLen != null) {
            tvMaxLen.setText(String.valueOf(LineDetector.maxLen));
        }
    }

    private void refreshStatus() {
        boolean svc = AssistService.isConnected();
        boolean ovl = AssistCore.overlayAllowed(this);
        status.setText("无障碍服务：" + (svc ? "已开启 ✅" : "未开启 ❌")
                + "\n悬浮窗权限：" + (ovl ? "已授权 ✅" : "未授权 ❌")
                + "\n状态：" + (AssistCore.isRunning() ? "运行中" : "已停止"));
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            appendLog("无法打开无障碍设置：" + t);
        }
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Throwable t) {
                appendLog("无法打开悬浮窗设置：" + t);
            }
        }
    }

    private void startAssist() {
        if (!AssistService.isConnected()) {
            appendLog("请先开启无障碍服务");
            openAccessibility();
            return;
        }
        if (!AssistCore.overlayAllowed(this)) {
            appendLog("请先授权悬浮窗权限");
            openOverlaySettings();
            return;
        }
        if (AssistCore.start(this)) {
            AssistCore.showPanel(this);   // 直接弹出悬浮控制条：之后在游戏里就能开始/调框/调参
            appendLog("已开始，并弹出悬浮控制条（开始/阈值/调框/✕）");
            appendLog("瞄准时自动识别：蓝=虚球圆，洋红=母球，橙=目标球，红=目标球出射线，绿虚线=母球出射线");
        } else {
            appendLog("启动失败");
        }
        refreshStatus();
    }

    private void stopAssist() {
        AssistCore.stop();
        appendLog("已停止");
        refreshStatus();
    }

    private void appendLog(String s) {
        if (log == null) {
            return;
        }
        log.setText(log.getText() + s + "\n");
        if (scroll != null) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }
}
