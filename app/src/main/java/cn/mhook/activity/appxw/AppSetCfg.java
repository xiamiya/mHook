package cn.mhook.activity.appxw;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.lzf.easyfloat.permission.PermissionUtils;
import com.tamsiree.rxkit.RxAppTool;

import cn.mhook.floatprint.FloatActivity;
import cn.mhook.mhook.R;
import cn.mhook.widget.GlassToast;

import static cn.mhook.mhook.contentprovider.appCfg.setAppCfg;

public class AppSetCfg extends Activity {

    private LinearLayout container;
    private String pkg;
    private TextView probeValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_xw_set);
        container = findViewById(R.id.cfg_container);
        pkg = getIntent().getStringExtra("pkg");
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        initView();
    }

    private void initView() {
        // 基本
        addSectionTitle("基本");
        addAppRow();
        addToggleRow("总开关", "appCfgEnable");
        // UI
        addSectionTitle("UI");
        addToggleRow("对话框", "dialog");
        addToggleRow("Toast", "toast");
        addToggleRow("弹窗", "show_view");
        addToggleRow("界面跳转", "activity_goto");
        addToggleRow("界面关闭", "activity_finish");
        addToggleRow("点击事件", "button");
        // 数据
        addSectionTitle("数据");
        addToggleRow("访问存储操作", "file");
        addToggleRow("JSON添加", "putJson");
        // 网络
        addSectionTitle("网络");
        addToggleRow("代理检测及屏蔽", "cProperty");
        // 自定义
        addSectionTitle("自定义");
        addProbeRow();
    }

    private void addSectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(getResources().getColor(R.color.glass_text_secondary));
        t.setTextSize(13);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(4), dp(14), 0, dp(4));
        container.addView(t, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_glass_card);
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setForeground(androidx.core.content.res.ResourcesCompat.getDrawable(getResources(), outValue.resourceId, getTheme()));
        row.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView addRowTitle(LinearLayout row, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getResources().getColor(R.color.glass_text_primary));
        t.setTextSize(15);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return t;
    }

    private TextView addRowValue(LinearLayout row) {
        TextView v = new TextView(this);
        v.setTextColor(getResources().getColor(R.color.glass_text_tertiary));
        v.setTextSize(13);
        row.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private void addAppRow() {
        LinearLayout row = newRow();
        addRowTitle(row, "应用（点击启动）");
        TextView v = addRowValue(row);
        v.setText(RxAppTool.getAppName(this, pkg));
        v.setTextColor(getResources().getColor(R.color.glass_text_secondary));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (RxAppTool.isInstallApp(AppSetCfg.this, pkg)) {
                    new FloatActivity(AppSetCfg.this, AppSetCfg.this);
                    if (PermissionUtils.checkPermission(AppSetCfg.this)) {
                        RxAppTool.launchApp(AppSetCfg.this, pkg);
                    }
                } else {
                    GlassToast.error(AppSetCfg.this, "未安装该应用");
                }
            }
        });
        container.addView(row);
    }

    private void addToggleRow(final String name, final String tag) {
        final LinearLayout row = newRow();
        addRowTitle(row, name);
        final TextView valueTv = addRowValue(row);
        refreshStatus(valueTv, tag);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !getEnable(tag);
                setAppCfg(pkg, tag, on);
                refreshStatus(valueTv, tag);
            }
        });
        container.addView(row);
    }

    private void refreshStatus(TextView v, String tag) {
        boolean on = getEnable(tag);
        v.setText(on ? "已开启" : "未开启");
        v.setTextColor(getResources().getColor(on ? R.color.glass_accent_green : R.color.glass_text_tertiary));
    }

    private void addProbeRow() {
        LinearLayout row = newRow();
        addRowTitle(row, "方法返回值探测");
        probeValue = addRowValue(row);
        refreshProbeDetail();
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMethodReturnDialog();
            }
        });
        container.addView(row);
    }

    private void refreshProbeDetail() {
        if (probeValue == null) return;
        JSONArray arr = getMethodReturnConfig();
        boolean empty = arr == null || arr.isEmpty();
        probeValue.setText(empty ? "未配置" : ("已配置 " + arr.size() + " 个方法"));
        probeValue.setTextColor(getResources().getColor(empty ? R.color.glass_text_tertiary : R.color.glass_accent_green));
    }

    private JSONArray getMethodReturnConfig() {
        try {
            JSONObject cfg = cn.mhook.mhook.contentprovider.appCfg.getAppCfg(pkg);
            if (cfg != null && cfg.getJSONArray("methodReturn") != null) {
                return cfg.getJSONArray("methodReturn");
            }
        } catch (Throwable ignored) {
        }
        return new JSONArray();
    }

    /** 配置类名/方法名：hook 后打印方法返回值（毫秒/秒时间戳、布尔真假等）。 */
    private void showMethodReturnDialog() {
        final JSONArray data = getMethodReturnConfig();
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText cls = new EditText(this);
        cls.setHint("全限定类名，如 com.example.Foo");
        cls.setHintTextColor(getResources().getColor(R.color.glass_text_tertiary));
        cls.setTextColor(getResources().getColor(R.color.glass_text_primary));
        cls.setTextSize(14);
        container.addView(cls);

        final EditText mth = new EditText(this);
        mth.setHint("方法名，如 getTime / isVip");
        mth.setHintTextColor(getResources().getColor(R.color.glass_text_tertiary));
        mth.setTextColor(getResources().getColor(R.color.glass_text_primary));
        mth.setTextSize(14);
        container.addView(mth);

        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        TextView addBtn = new TextView(this);
        addBtn.setText("+ 添加");
        addBtn.setTextColor(getResources().getColor(R.color.glass_accent_orange));
        addBtn.setTextSize(15);
        addBtn.setPadding(0, dp(8), dp(24), dp(8));
        TextView clearBtn = new TextView(this);
        clearBtn.setText("清空");
        clearBtn.setTextColor(getResources().getColor(R.color.glass_accent_red));
        clearBtn.setTextSize(15);
        clearBtn.setPadding(0, dp(8), 0, dp(8));
        ops.addView(addBtn);
        ops.addView(clearBtn);
        container.addView(ops);

        final TextView list = new TextView(this);
        list.setTextSize(13);
        list.setTextColor(getResources().getColor(R.color.glass_text_secondary));
        list.setText(formatProbeList(data));
        container.addView(list);

        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String c = cls.getText().toString().trim();
                String m = mth.getText().toString().trim();
                if (c.isEmpty() || m.isEmpty()) {
                    GlassToast.warning(AppSetCfg.this, "请填写类名和方法名");
                    return;
                }
                JSONObject h = new JSONObject(true);
                h.put("className", c);
                h.put("methodName", m);
                data.add(h);
                cls.setText("");
                mth.setText("");
                list.setText(formatProbeList(data));
            }
        });
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                data.clear();
                list.setText(formatProbeList(data));
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("方法返回值探测")
                .setMessage("hook 后每次调用都会把返回值记录到行为日志（含时间戳毫秒/秒判断）")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        setAppCfg(pkg, "methodReturn", data);
                        refreshProbeDetail();
                        GlassToast.success(AppSetCfg.this, "已保存，重启目标应用后生效");
                    }
                })
                .show();
    }

    private String formatProbeList(JSONArray data) {
        if (data == null || data.isEmpty()) return "（暂无）";
        StringBuilder sb = new StringBuilder();
        for (Object o : data) {
            JSONObject h = (JSONObject) o;
            sb.append(h.getString("className")).append("#").append(h.getString("methodName")).append('\n');
        }
        return sb.toString();
    }

    public JSONObject getAppCfg() {
        return cn.mhook.mhook.contentprovider.appCfg.getAppCfg(pkg);
    }

    public Boolean getEnable(String key) {
        if (getAppCfg() != null && getAppCfg().containsKey(key) && getAppCfg().getBoolean(key)) {
            return true;
        }
        return false;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
