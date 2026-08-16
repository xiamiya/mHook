package cn.mhook.activity.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.viewholder.BaseViewHolder;
import com.tamsiree.rxkit.RxActivityTool;
import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.RxClipboardTool;
import com.tamsiree.rxkit.RxTool;
import com.lzf.easyfloat.permission.PermissionUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import cn.mhook.activity.editcfg.EditHookActivity;
import cn.mhook.floatprint.FloatActivity;
import cn.mhook.mhook.R;
import cn.mhook.mhook.contentprovider.jsonCfg;
import cn.mhook.widget.GlassToast;


public class HookActivityAdapter extends BaseQuickAdapter<HookActivityItem, BaseViewHolder> {

    private Activity activity;
    private boolean selectMode = false;
    private Set<String> selected = new HashSet<>();
    private OnSelectListener selectListener;

    public HookActivityAdapter(@LayoutRes int layoutResId, @Nullable List<HookActivityItem> data, Activity activity) {
        super(layoutResId, data);
        this.activity = activity;
    }

    public interface OnSelectListener {
        void onSelectToggle(HookActivityItem item);

        void onLongPress(HookActivityItem item);
    }

    @Override
    protected void convert(final BaseViewHolder helper, final HookActivityItem item) {
        helper.setText(R.id.appName, item.getAppName())
                .setText(R.id.ver, item.getVer())
                .setText(R.id.pkg, item.getPkg())
                .setText(R.id.detail, item.getDetail())
                .setText(R.id.author, item.getAuthor())
                .setText(R.id.time, item.getTime());

        if (item.getCfgId() != null && !item.getCfgId().isEmpty()) {
            helper.setVisible(R.id.num_backgroud, true);
        }

        final CheckBox selectBox = helper.getView(R.id.selectBox);
        selectBox.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        selectBox.setChecked(selectMode && selected.contains(item.getCfgKey()));
        helper.getView(R.id.cfgInfoItem).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectMode) {
                    if (selectListener != null) {
                        selectListener.onSelectToggle(item);
                    }
                } else {
                    showItemMenu(item);
                }
            }
        });
        helper.getView(R.id.cfgInfoItem).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (selectListener == null) {
                    return true;
                }
                if (selectMode) {
                    selectListener.onSelectToggle(item);
                } else {
                    selectListener.onLongPress(item);
                }
                return true;
            }
        });
        final TextView enableTip = helper.getView(R.id.enableTip);
        if (item.getEnable()) {
            enableTip.setText("已启用");
            enableTip.setTextColor(getContext().getResources().getColor(R.color.glass_accent_green));
        } else {
            enableTip.setText("已禁用");
            enableTip.setTextColor(getContext().getResources().getColor(R.color.glass_text_tertiary));
        }
        helper.getView(R.id.enableLayout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (enableTip.getText().equals("已启用")) {
                    enableTip.setText("已禁用");
                    enableTip.setTextColor(getContext().getResources().getColor(R.color.glass_text_tertiary));
                    jsonCfg.setEnable(false, item.getCfgKey());
                } else {
                    enableTip.setText("已启用");
                    enableTip.setTextColor(getContext().getResources().getColor(R.color.glass_accent_green));
                    jsonCfg.setEnable(true, item.getCfgKey());
                }
            }
        });
        final String appver = RxAppTool.getAppVersionName(getContext(), item.getPkg());
        int err = 1;
        if (appver != null && appver.equals(item.getVer())) {
            err--;
        }
        final TextView errView = helper.getView(R.id.err);
        errView.setText(" " + err + " ");
        errView.setTextColor(getContext().getResources().getColor(err == 0 ? R.color.glass_accent_green : R.color.glass_accent_red));
        helper.getView(R.id.errLayout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(getContext())
                        .setTitle("版本对比")
                        .setMessage("配置检测：" + (item.getVer().equals(appver) ? "通过" : "不通过")
                                + "\n\n配置版本：" + item.getVer()
                                + "\n应用版本：" + (appver == null ? "未安装" : appver))
                        .setNegativeButton("关闭", null)
                        .show();
            }
        });
    }

    private void showItemMenu(final HookActivityItem item) {
        final String[] items = {"打开应用", "修改配置", "以调试模式启动", "分享配置"};
        new AlertDialog.Builder(getContext())
                .setTitle(item.getAppName())
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            if (RxAppTool.isInstallApp(getContext(), item.getPkg())) {
                                RxAppTool.launchApp(getContext(), item.getPkg());
                            } else {
                                GlassToast.error(getContext(), "未安装该应用");
                            }
                        } else if (which == 1) {
                            Bundle bundle = new Bundle();
                            bundle.putString("KeyStr", item.getCfgKey());
                            RxActivityTool.skipActivity(getContext(), EditHookActivity.class, bundle);
                        } else if (which == 2) {
                            if (RxAppTool.isInstallApp(getContext(), item.getPkg())) {
                                new FloatActivity(activity, getContext());
                                if (PermissionUtils.checkPermission(getContext())) {
                                    RxAppTool.launchApp(RxTool.getContext(), item.getPkg());
                                }
                            } else {
                                GlassToast.error(getContext(), "未安装该应用");
                            }
                        } else if (which == 3) {
                            JSONObject cfg = jsonCfg.getCfgByKey(item.getCfgKey());
                            if (cfg == null) {
                                GlassToast.error(getContext(), "配置不存在");
                                return;
                            }
                            JSONArray hooks = cfg.getJSONArray("hooks");
                            JSONObject share = HookImport.exportConfig(cfg, hooks);
                            RxClipboardTool.copyText(getContext(), share.toJSONString());
                            GlassToast.success(getContext(), "已复制配置到剪贴板");
                        }
                    }
                })
                .show();
    }

    public void setSelected(Set<String> selected) {
        this.selected = selected;
    }

    public void setSelectListener(OnSelectListener selectListener) {
        this.selectListener = selectListener;
    }

    public boolean isSelectMode() {
        return selectMode;
    }

    public void setSelectMode(boolean selectMode) {
        this.selectMode = selectMode;
        notifyDataSetChanged();
    }

}
