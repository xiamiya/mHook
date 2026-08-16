package cn.mhook.activity.editcfg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.tamsiree.rxkit.RxActivityTool;
import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.RxEncryptTool;
import com.tamsiree.rxkit.RxTimeTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import cn.mhook.activity.selectapp.SelectActivity;
import cn.mhook.mhook.R;
import cn.mhook.mhook.contentprovider.appCfg;
import cn.mhook.mhook.contentprovider.jsonCfg;
import cn.mhook.widget.GlassToast;

public class EditHookActivity extends Activity {

    LinearLayout hookContainer;
    List<View> hookList = new ArrayList<>();
    JSONObject config;
    TextView appNameItem;
    TextView hookPlusValue;
    boolean hookPlusOn = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_hook);
        hookContainer = findViewById(R.id.hook_container);
        initEdit();
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCfg();
            }
        });
        findViewById(R.id.btn_add_return).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edit = null;
                Intent intent = new Intent();
                intent.setClass(EditHookActivity.this, EditSetReturn.class);
                startActivityForResult(intent, 1);
            }
        });
        initForm();
        loadHooks();
    }

    private void initEdit() {
        Bundle extras = getIntent().getExtras();
        if (extras != null && extras.containsKey("KeyStr")) {
            config = jsonCfg.getCfgByKey(extras.getString("KeyStr"));
        } else if (extras != null && extras.containsKey("AiCfg")) {
            config = JSONObject.parseObject(extras.getString("AiCfg"));
        } else {
            config = new JSONObject(true);
        }
        if (config == null) {
            config = new JSONObject(true);
        }
    }

    private void initForm() {
        appNameItem = findViewById(R.id.app_select_value);
        findViewById(R.id.app_select_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Bundle bundle = new Bundle();
                bundle.putString("appType", "all");
                RxActivityTool.skipActivityForResult(EditHookActivity.this, SelectActivity.class, bundle, 9008);
            }
        });
        final TextView authorValue = findViewById(R.id.author_value);
        findViewById(R.id.author_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText input = new EditText(EditHookActivity.this);
                String cur = authorValue.getText().toString();
                if (cur.isEmpty() || "匿名作者".equals(cur)) {
                    cur = "";
                }
                input.setText(cur);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                new AlertDialog.Builder(EditHookActivity.this)
                        .setTitle("作者")
                        .setView(input)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                CharSequence text = input.getText();
                                authorValue.setText(text);
                                config.put("author", text);
                            }
                        })
                        .show();
            }
        });
        final TextView detailValue = findViewById(R.id.detail_value);
        findViewById(R.id.detail_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText input = new EditText(EditHookActivity.this);
                String cur = detailValue.getText().toString();
                if (cur.isEmpty() || "点击填写备注".equals(cur)) {
                    cur = "";
                }
                input.setText(cur);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                new AlertDialog.Builder(EditHookActivity.this)
                        .setTitle("备注")
                        .setView(input)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                CharSequence text = input.getText();
                                detailValue.setText(text);
                                config.put("detail", text);
                            }
                        })
                        .show();
            }
        });
        hookPlusValue = findViewById(R.id.hook_plus_value);
        findViewById(R.id.hook_plus_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!config.containsKey("appPkg")) {
                    GlassToast.warning(EditHookActivity.this, "请先选择应用再启用HOOK+");
                    return;
                }
                String pkg = config.getString("appPkg");
                hookPlusOn = !((appCfg.getAppCfg(pkg) != null) && (appCfg.getAppCfg(pkg).containsKey("hook+")) && (appCfg.getAppCfg(pkg).getBoolean("hook+")));
                appCfg.setAppCfg(pkg, "hook+", hookPlusOn);
                updateHookPlusUI();
            }
        });
    }

    private void updateHookPlusUI() {
        if (hookPlusOn) {
            hookPlusValue.setText("已启用");
            hookPlusValue.setTextColor(getResources().getColor(R.color.glass_accent_green));
        } else {
            hookPlusValue.setText("未启用");
            hookPlusValue.setTextColor(getResources().getColor(R.color.glass_text_secondary));
        }
    }

    private void loadHooks() {
        String pkg = config.containsKey("appPkg") ? config.getString("appPkg") : null;
        if (pkg != null) {
            appNameItem.setText(config.getString("appName"));
            TextView authorValue = findViewById(R.id.author_value);
            TextView detailValue = findViewById(R.id.detail_value);
            authorValue.setText(config.containsKey("author") ? config.getString("author") : "匿名作者");
            detailValue.setText(config.containsKey("detail") ? config.getString("detail") : "");
            hookPlusOn = (appCfg.getAppCfg(pkg) != null) && (appCfg.getAppCfg(pkg).containsKey("hook+")) && (appCfg.getAppCfg(pkg).getBoolean("hook+"));
            updateHookPlusUI();
        }
        if (config.containsKey("hooks")) {
            for (Object o : config.getJSONArray("hooks")) {
                addItem(JSONObject.parseObject(o.toString()), createHookItem());
            }
        }
    }

    private View edit;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == 2 && data != null) {
            Bundle b = data.getExtras();
            JSONObject hookCfg = JSONObject.parseObject(b.getString("data"));
            onHookResult(hookCfg);
        }
        if (requestCode == 9008 && resultCode == RESULT_OK) {
            String comment = data.getStringExtra("pkg");
            if (comment != null && !comment.isEmpty()) {
                appNameItem.setText(RxAppTool.getAppName(EditHookActivity.this, comment));
                config.put("appPkg", comment);
                config.put("appName", RxAppTool.getAppName(EditHookActivity.this, comment));
                config.put("appVer", RxAppTool.getAppVersionName(EditHookActivity.this, comment));
            }
        }
    }

    private void onHookResult(final JSONObject hookCfg) {
        final View replaceItem = edit;
        final List<View> dups = findDuplicates(hookCfg, replaceItem);
        if (dups.isEmpty()) {
            doAddHook(hookCfg, replaceItem);
            return;
        }
        String cls = hookCfg.getString("className");
        String mtd = hookCfg.getString("methodName");
        if (dups.size() == 1) {
            new AlertDialog.Builder(EditHookActivity.this)
                    .setTitle("重复配置")
                    .setMessage("已存在相同Hook配置\n类：" + cls + "\n方法：" + mtd + "\n是否覆盖？")
                    .setNegativeButton("跳过", null)
                    .setPositiveButton("覆盖", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            removeHookItems(dups);
                            doAddHook(hookCfg, replaceItem);
                            dialog.dismiss();
                        }
                    })
                    .show();
        } else {
            new AlertDialog.Builder(EditHookActivity.this)
                    .setTitle("存在 " + dups.size() + " 个相同配置，请选择")
                    .setItems(new String[]{"跳过当前", "覆盖当前", "跳过全部", "覆盖全部"}, new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            if (which == 1) {
                                removeHookItems(Collections.singletonList(dups.get(0)));
                                doAddHook(hookCfg, replaceItem);
                            } else if (which == 3) {
                                removeHookItems(dups);
                                doAddHook(hookCfg, replaceItem);
                            }
                            dialog.dismiss();
                        }
                    })
                    .show();
        }
    }

    private List<View> findDuplicates(JSONObject hookCfg, View exclude) {
        List<View> dups = new ArrayList<>();
        String cls = hookCfg.getString("className");
        String mtd = hookCfg.getString("methodName");
        if (cls == null || mtd == null) {
            return dups;
        }
        for (View item : hookList) {
            if (item == exclude) {
                continue;
            }
            Object tag = item.getTag();
            if (tag instanceof JSONObject) {
                JSONObject j = (JSONObject) tag;
                if (cls.equals(j.getString("className")) && mtd.equals(j.getString("methodName"))) {
                    dups.add(item);
                }
            }
        }
        return dups;
    }

    private void removeHookItems(List<View> items) {
        for (View item : items) {
            hookList.remove(item);
            hookContainer.removeView(item);
        }
    }

    private void doAddHook(JSONObject hookCfg, View replaceItem) {
        if (replaceItem == null) {
            addItem(hookCfg, createHookItem());
        } else {
            hookList.remove(replaceItem);
            addItem(hookCfg, replaceItem);
            edit = null;
        }
    }

    private void addItem(final JSONObject cfg, final View item) {
        String hookType = cfg.getString("hookType");
        if ("setRet".equals(hookType)) {
            ((TextView) item.findViewById(R.id.hook_item_title)).setText("修改返回值");
            ((TextView) item.findViewById(R.id.hook_item_detail)).setText("类：" + cfg.getString("className") + "\n方法：" + cfg.getString("methodName"));
        }
        item.setTag(cfg);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edit = item;
                Intent intent = new Intent();
                Bundle bundle = new Bundle();
                bundle.putString("data", cfg.toJSONString());
                intent.putExtras(bundle);
                intent.setClass(EditHookActivity.this, EditSetReturn.class);
                startActivityForResult(intent, 1);
            }
        });
        if (edit == null) {
            hookContainer.addView(item);
        }
        hookList.add(item);
    }

    private View createHookItem() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.bg_glass_card);
        int pad = dp(16);
        int innerPad = dp(12);
        row.setPadding(pad, innerPad, pad, innerPad);
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue outValue = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setForeground(androidx.core.content.res.ResourcesCompat.getDrawable(getResources(), outValue.resourceId, getTheme()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setId(R.id.hook_item_title);
        title.setTextColor(getResources().getColor(R.color.glass_text_primary));
        title.setTextSize(14);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        row.addView(title);

        TextView detail = new TextView(this);
        detail.setId(R.id.hook_item_detail);
        detail.setTextColor(getResources().getColor(R.color.glass_text_secondary));
        detail.setTextSize(12);
        detail.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        row.addView(detail, dlp);

        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                new AlertDialog.Builder(EditHookActivity.this)
                        .setTitle("删除")
                        .setMessage("确定要删除该 Hook 配置吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                hookList.remove(row);
                                hookContainer.removeView(row);
                                dialog.dismiss();
                            }
                        })
                        .show();
                return true;
            }
        });
        return row;
    }

    private void saveCfg() {
        if (config.containsKey("appPkg") && hookList.size() > 0) {
            JSONArray hooks = new JSONArray();
            for (Object o : hookList) {
                hooks.add(JSONObject.parseObject(((View) o).getTag().toString()));
            }
            config.put("hooks", hooks);
            config.put("time", RxTimeTool.getCurTimeString());
            if (config.containsKey("keyStr")) {
                new AlertDialog.Builder(EditHookActivity.this)
                        .setTitle("覆盖")
                        .setMessage("是否覆盖原配置？")
                        .setNegativeButton("否", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                config.put("keyStr", RxEncryptTool.encryptMD5ToString(config.toJSONString()));
                                doAddNewConfig(config.getString("appPkg"), config.getString("keyStr"));
                                dialog.dismiss();
                            }
                        })
                        .setPositiveButton("是", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                jsonCfg.delConfig(config.getString("appPkg"), config.getString("keyStr"));
                                doAddNewConfig(config.getString("appPkg"), config.getString("keyStr"));
                                dialog.dismiss();
                            }
                        })
                        .show();
            } else {
                final String pkg = config.getString("appPkg");
                final String newKey = RxEncryptTool.encryptMD5ToString(config.toJSONString());
                if (jsonCfg.getCfgByKey(newKey) != null) {
                    new AlertDialog.Builder(EditHookActivity.this)
                            .setTitle("重复配置")
                            .setMessage("已存在相同配置，是否覆盖？")
                            .setNegativeButton("跳过", null)
                            .setPositiveButton("覆盖", new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    jsonCfg.delConfig(pkg, newKey);
                                    doAddNewConfig(pkg, newKey);
                                    dialog.dismiss();
                                }
                            })
                            .show();
                } else {
                    final List<JSONObject> samePkg = getCfgByPkg(pkg);
                    if (samePkg.isEmpty()) {
                        doAddNewConfig(pkg, newKey);
                    } else if (samePkg.size() == 1) {
                        new AlertDialog.Builder(EditHookActivity.this)
                                .setTitle("重复配置")
                                .setMessage("该软件已存在 1 个配置，是否覆盖？")
                                .setNegativeButton("跳过", null)
                                .setPositiveButton("覆盖", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog, int which) {
                                        jsonCfg.delConfig(pkg, samePkg.get(0).getString("KeyStr"));
                                        doAddNewConfig(pkg, newKey);
                                        dialog.dismiss();
                                    }
                                })
                                .show();
                    } else {
                        new AlertDialog.Builder(EditHookActivity.this)
                                .setTitle("该软件已存在 " + samePkg.size() + " 个配置，请选择")
                                .setItems(new String[]{"跳过当前", "覆盖当前", "跳过全部", "覆盖全部"}, new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface dialog, int which) {
                                        if (which == 1) {
                                            jsonCfg.delConfig(pkg, samePkg.get(0).getString("KeyStr"));
                                            doAddNewConfig(pkg, newKey);
                                        } else if (which == 3) {
                                            for (JSONObject j : samePkg) {
                                                jsonCfg.delConfig(pkg, j.getString("KeyStr"));
                                            }
                                            doAddNewConfig(pkg, newKey);
                                        }
                                        dialog.dismiss();
                                    }
                                })
                                .show();
                    }
                }
            }
        } else {
            GlassToast.warning(this, "似乎忘了点什么");
        }
    }

    private void doAddNewConfig(String pkg, String newKey) {
        config.put("keyStr", newKey);
        Boolean success = jsonCfg.addCfg(pkg, true, false, newKey, config, false);
        if (success) {
            GlassToast.success(this, "添加成功");
        } else {
            GlassToast.warning(this, "已存在相同配置");
        }
        finish();
    }

    private List<JSONObject> getCfgByPkg(String pkg) {
        List<JSONObject> list = new ArrayList<>();
        JSONArray all = jsonCfg.getAllCfg();
        for (Object o : all) {
            JSONObject j = JSONObject.parseObject(o.toString());
            if (pkg.equals(j.getString("pkg"))) {
                list.add(j);
            }
        }
        return list;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
