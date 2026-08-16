package cn.mhook.activity.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.tamsiree.rxkit.view.RxToast;

import java.util.regex.Pattern;

import cn.mhook.ai.McpClient;
import cn.mhook.ai.McpManager;
import cn.mhook.ai.McpSetting;
import cn.mhook.mhook.R;

public class McpSettingActivity extends Activity {

    private LinearLayout serverList;
    private LinearLayout opList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mcp_setting);
        serverList = findViewById(R.id.server_list);
        opList = findViewById(R.id.op_list);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        refresh();
    }

    private void refresh() {
        serverList.removeAllViews();
        opList.removeAllViews();
        final JSONArray servers = McpSetting.getServers(this);
        for (Object o : servers) {
            final JSONObject s = (JSONObject) o;
            serverList.addView(buildServerRow(servers, s));
        }
        buildOpItems();
    }

    private View buildServerRow(final JSONArray servers, final JSONObject s) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_mcp_server, serverList, false);
        ((TextView) row.findViewById(R.id.server_name)).setText(labelOf(s));
        ((TextView) row.findViewById(R.id.server_url)).setText(s.getString("url"));
        applyToggle(row.findViewById(R.id.server_toggle), row.findViewById(R.id.server_toggle_knob),
                s.getBooleanValue("enable"));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleEnable(servers, s);
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showServerMenu(servers, s);
                return true;
            }
        });
        return row;
    }

    private void buildOpItems() {
        addOp("添加自定义服务器", "新增任意 MCP 后端，支持自定义地址与 Token",
                R.drawable.ic_add, R.color.glass_accent_green, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        addServer();
                    }
                });
        addOp("一键探测并启用", "自动匹配 MT/玄星逆核/ProxyPin 候选端口",
                R.drawable.ic_analyze, R.color.glass_accent_cyan, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        probeAll();
                    }
                });
        addOp("使用说明", "需先在 MT管理器/玄星逆核/ProxyPin 内启动 MCP 服务",
                R.drawable.ic_help, R.color.glass_text_secondary, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        cn.mhook.widget.GlassToast.info(McpSettingActivity.this, "请先在对应 App 内启动 MCP 服务并保持运行");
                    }
                });
    }

    private void addOp(String title, String desc, int iconRes, int colorRes, View.OnClickListener listener) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_mcp_op, opList, false);
        ((TextView) row.findViewById(R.id.op_title)).setText(title);
        ((TextView) row.findViewById(R.id.op_desc)).setText(desc);
        ImageView ic = (ImageView) ((FrameLayout) row.findViewById(R.id.op_icon)).getChildAt(0);
        ic.setImageResource(iconRes);
        ic.setColorFilter(getResources().getColor(colorRes));
        row.setOnClickListener(listener);
        opList.addView(row);
    }

    private String labelOf(JSONObject s) {
        String label = s.getString("label");
        String name = s.getString("name");
        return (label == null || label.isEmpty()) ? (name == null ? "" : name) : label;
    }

    private void toggleEnable(final JSONArray servers, final JSONObject s) {
        boolean en = !s.getBooleanValue("enable");
        s.put("enable", en);
        McpSetting.saveServers(this, servers);
        McpManager.resetClients();
        refresh();
        cn.mhook.widget.GlassToast.info(McpSettingActivity.this, (en ? "已启用 " : "已禁用 ") + labelOf(s));
    }

    private void applyToggle(View toggle, View knob, boolean on) {
        if (toggle == null) {
            return;
        }
        float travel = 20 * getResources().getDisplayMetrics().density;
        if (on) {
            toggle.getBackground().setTint(getResources().getColor(R.color.glass_accent_green));
            if (knob != null) {
                knob.setTranslationX(travel);
            }
        } else {
            toggle.getBackground().setTint(0x80B4AA9B);
            if (knob != null) {
                knob.setTranslationX(0f);
            }
        }
    }

    private void showServerMenu(final JSONArray servers, final JSONObject s) {
        new AlertDialog.Builder(this)
                .setTitle(labelOf(s))
                .setItems(new String[]{"编辑", "测试连接", "删除"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == 0) {
                            editServer(servers, s);
                        } else if (which == 1) {
                            testServer(servers, s);
                        } else {
                            deleteServer(servers, s);
                        }
                    }
                })
                .create().show();
    }

    private void editServer(final JSONArray servers, final JSONObject s) {
        final JSONObject draft = new JSONObject(true);
        draft.put("name", s.getString("name"));
        draft.put("url", s.getString("url"));
        draft.put("token", s.getString("token"));
        promptField(0, draft, s);
    }

    private void addServer() {
        final JSONObject draft = new JSONObject(true);
        draft.put("enable", false);
        promptField(0, draft, null);
    }

    private void promptField(final int step, final JSONObject draft, final JSONObject target) {
        switch (step) {
            case 0:
                showInput("服务器名称", "字母/数字/下划线，用于工具前缀",
                        target == null ? "" : target.getString("name"), new InputCallback() {
                            @Override
                            public void onResult(String v) {
                                String val = v.trim();
                                if (val.isEmpty()) {
                                    return;
                                }
                                if (!Pattern.matches("[A-Za-z0-9_]+", val)) {
                                    cn.mhook.widget.GlassToast.error(McpSettingActivity.this, "名称只能包含字母/数字/下划线");
                                    promptField(0, draft, target);
                                    return;
                                }
                                draft.put("name", val);
                                promptField(1, draft, target);
                            }
                        });
                break;
            case 1:
                showInput("服务器地址", "如 http://127.0.0.1:8000/mcp",
                        target == null ? "" : target.getString("url"), new InputCallback() {
                            @Override
                            public void onResult(String v) {
                                String val = v.trim();
                                if (val.isEmpty()) {
                                    cn.mhook.widget.GlassToast.error(McpSettingActivity.this, "地址不能为空");
                                    promptField(1, draft, target);
                                    return;
                                }
                                draft.put("url", val);
                                promptField(2, draft, target);
                            }
                        });
                break;
            case 2:
                showInput("Token（可留空）", "Bearer 令牌",
                        target == null ? "" : target.getString("token"), new InputCallback() {
                            @Override
                            public void onResult(String v) {
                                draft.put("token", v.trim());
                                finishAdd(draft, target);
                            }
                        });
                break;
        }
    }

    private void finishAdd(final JSONObject draft, final JSONObject target) {
        JSONArray servers = McpSetting.getServers(this);
        if (target != null) {
            String oldName = target.getString("name");
            String newName = draft.getString("name");
            JSONObject stored = findInArray(servers, oldName);
            if (stored == null) {
                cn.mhook.widget.GlassToast.error(McpSettingActivity.this, "目标服务器不存在，请返回重试");
                return;
            }
            if (!newName.equals(oldName) && findInArray(servers, newName) != null) {
                cn.mhook.widget.GlassToast.error(McpSettingActivity.this, "已存在同名服务器");
                return;
            }
            stored.put("name", newName);
            stored.put("url", draft.getString("url"));
            stored.put("token", draft.getString("token"));
            if (stored.getString("label") == null || stored.getString("label").isEmpty()) {
                stored.put("label", newName);
            }
        } else {
            if (McpSetting.findServer(this, draft.getString("name")) != null) {
                cn.mhook.widget.GlassToast.error(McpSettingActivity.this, "已存在同名服务器");
                return;
            }
            if (draft.getString("label") == null || draft.getString("label").isEmpty()) {
                draft.put("label", draft.getString("name"));
            }
            servers.add(draft);
        }
        McpSetting.saveServers(this, servers);
        McpManager.resetClients();
        refresh();
        cn.mhook.widget.GlassToast.success(McpSettingActivity.this, target != null ? "已保存" : "已添加 " + draft.getString("name"));
    }

    private static JSONObject findInArray(JSONArray arr, String name) {
        for (Object o : arr) {
            JSONObject j = (JSONObject) o;
            if (name != null && name.equals(j.getString("name"))) {
                return j;
            }
        }
        return null;
    }

    private void deleteServer(final JSONArray servers, final JSONObject s) {
        new AlertDialog.Builder(this)
                .setTitle("删除服务器")
                .setMessage("确定要删除 " + labelOf(s) + " 吗？")
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        servers.remove(s);
                        McpSetting.saveServers(McpSettingActivity.this, servers);
                        McpManager.resetClients();
                        refresh();
                        dialog.dismiss();
                    }
                })
                .create().show();
    }

    private void testServer(final JSONArray servers, final JSONObject s) {
        final String name = s.getString("name");
        final String url = s.getString("url");
        final String token = s.getString("token");
        cn.mhook.widget.GlassToast.info(McpSettingActivity.this, "正在测试 " + name + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String msg = doTest(name, url, token);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (msg.startsWith("连接成功")) {
                            cn.mhook.widget.GlassToast.success(McpSettingActivity.this, msg);
                        } else {
                            cn.mhook.widget.GlassToast.error(McpSettingActivity.this, msg);
                        }
                    }
                });
            }
        }).start();
    }

    private String doTest(String name, String url, String token) {
        try {
            McpClient c = McpManager.getClient(name, url, token);
            int count = c.listTools().size();
            return "连接成功，" + name + " 提供 " + count + " 个工具";
        } catch (Throwable t) {
            McpManager.invalidate(name);
            return "连接失败：" + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    private void probeAll() {
        cn.mhook.widget.GlassToast.info(McpSettingActivity.this, "探测中…");
        final JSONArray servers = McpSetting.getServers(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                McpManager.probeAndEnable(McpSettingActivity.this, servers);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        McpSetting.saveServers(McpSettingActivity.this, servers);
                        McpManager.resetClients();
                        refresh();
                        cn.mhook.widget.GlassToast.success(McpSettingActivity.this, "探测完成，可用后端已启用");
                    }
                });
            }
        }).start();
    }

    private void showInput(String title, String placeholder, String def, final InputCallback cb) {
        final EditText et = new EditText(this);
        et.setHint(placeholder);
        et.setHintTextColor(getResources().getColor(R.color.glass_text_tertiary));
        et.setTextColor(getResources().getColor(R.color.glass_text_primary));
        et.setText(def == null ? "" : def);
        et.setBackgroundResource(R.drawable.bg_input_glass);
        int density = (int) getResources().getDisplayMetrics().density;
        et.setPadding(density * 16, density * 12, density * 16, density * 12);
        if (title != null && title.contains("Token")) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(density * 20, density * 8, density * 20, 0);
        wrap.addView(et);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cb.onResult(et.getText() == null ? "" : et.getText().toString());
                    }
                })
                .create().show();
    }

    private interface InputCallback {
        void onResult(String v);
    }
}
