package cn.mhook.fragment;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tamsiree.rxkit.RxActivityTool;
import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.RxSPTool;

import java.util.ArrayList;
import java.util.List;

import cn.mhook.activity.DonateActivity;
import cn.mhook.activity.UserAgreementActivity;
import cn.mhook.activity.ThanksActivity;import cn.mhook.activity.SandboxDumpActivity;
import cn.mhook.activity.RootlessDumpActivity;
import cn.mhook.activity.ai.AiActivity;
import cn.mhook.activity.appxw.AppXWActivity;
import cn.mhook.activity.dump.DumpActivity;
import cn.mhook.activity.hook.HookActivity;
import cn.mhook.activity.mkfix.MKFixActivity;
import cn.mhook.activity.xp.XpModuleAiActivity;
import cn.mhook.mhook.R;

public class HomePageBuilder {

    public static final int PAGE_SHELL = 0;
    public static final int PAGE_HOOK = 1;
    public static final int PAGE_PATCH = 2;
    public static final int PAGE_SETTINGS = 3;

    public static View build(Context context, ViewGroup parent, int page) {
        View root = LayoutInflater.from(context).inflate(R.layout.fragment_home, parent, false);

        String title = "", subtitle = "";
        switch (page) {
            case PAGE_SHELL: title = "脱壳"; subtitle = "Dump 与沙箱运行"; break;
            case PAGE_HOOK: title = "Hook"; subtitle = "注入与行为监控"; break;
            case PAGE_PATCH: title = "改包修复"; subtitle = "热修复与自动改包"; break;
            case PAGE_SETTINGS: title = "设置"; subtitle = "偏好与系统信息"; break;
        }
        TextView titleTv = root.findViewById(R.id.page_title);
        TextView subtitleTv = root.findViewById(R.id.page_subtitle);
        if (titleTv != null) {
            titleTv.setText(title);
        }
        if (subtitleTv != null) {
            subtitleTv.setText(subtitle);
        }

        RecyclerView recycler = root.findViewById(R.id.recycler);
        if (page == PAGE_SETTINGS) {
            recycler.setLayoutManager(new LinearLayoutManager(context));
            GlassListAdapter adapter = new GlassListAdapter(R.layout.item_glass_list, buildSettings(context));
            adapter.setToggleState(cn.mhook.App.enable("debug"));
            adapter.setToggleListener(new GlassListAdapter.ToggleListener() {
                @Override
                public boolean onToggle(boolean desired) {
                    cn.mhook.App.setEnable("debug", desired);
                    boolean ok = cn.mhook.App.enable("debug") == desired;
                    if (ok) {
                        cn.mhook.widget.GlassToast.info(context, desired ? "已启用调试" : "已禁用调试");
                    } else {
                        cn.mhook.widget.GlassToast.warning(context, desired ? "调试模式开启失败" : "调试模式关闭失败");
                    }
                    return ok;
                }
            });
            recycler.setAdapter(adapter);
        } else {
            GridLayoutManager grid = new GridLayoutManager(context, 2);
            recycler.setLayoutManager(grid);
            int spacing = (int) (8 * context.getResources().getDisplayMetrics().density);
            recycler.addItemDecoration(new GridSpacingDecoration(2, spacing));
            recycler.setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 96));
            recycler.setAdapter(new GlassCardAdapter(R.layout.item_glass_card, buildGrid(context, page)));
        }
        return root;
    }

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    private static List<GlassItem> buildGrid(Context context, int page) {
        List<GlassItem> list = new ArrayList<>();
        int blue = context.getResources().getColor(R.color.glass_accent_blue);
        int green = context.getResources().getColor(R.color.glass_accent_green);
        int violet = context.getResources().getColor(R.color.glass_accent_violet);
        int indigo = context.getResources().getColor(R.color.glass_accent_indigo);
        int orange = context.getResources().getColor(R.color.glass_accent_orange);
        int pink = context.getResources().getColor(R.color.glass_accent_pink);
        int cyan = context.getResources().getColor(R.color.glass_accent_cyan);

        switch (page) {
            case PAGE_HOOK:
                list.add(new GlassItem("自定义Hook", "添加和管理自定义Hook",
                        R.drawable.ic_hook, blue, "需Xposed", orange, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, HookActivity.class); }
                        }));
                list.add(new GlassItem("应用行为控制", "分析和控制应用的操作行为",
                        R.drawable.ic_control, violet, "需Xposed", orange, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, AppXWActivity.class); }
                        }));
                list.add(new GlassItem("XP模块分析", "分析XP模块APK并导入Hook配置",
                        R.drawable.ic_xp, indigo, "AI", blue, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, XpModuleAiActivity.class); }
                        }));
                break;
            case PAGE_PATCH:
                list.add(new GlassItem("MK热修复", "无感知修复异常",
                        R.drawable.ic_fix, orange, "需Xposed", orange, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, MKFixActivity.class); }
                        }));
                list.add(new GlassItem("应用分析", "AI生成Hook配置 / 自动改包",
                        R.drawable.ic_analyze, pink, "AI", blue, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, AiActivity.class); }
                        }));
                break;
            case PAGE_SHELL:
            default:
                list.add(new GlassItem("内存脱壳", "纯Java内存脱壳，dump加固后的dex",
                        R.drawable.ic_shield, cyan, "需Xposed", orange, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, DumpActivity.class); }
                        }));
                list.add(new GlassItem("沙箱脱壳", "选APK自动装入沙箱运行并dump，全程免root",
                        R.drawable.ic_sandbox, green, "免root", green, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, SandboxDumpActivity.class); }
                        }));
                list.add(new GlassItem("重打包脱壳", "NPatch注入脱壳模块并重签名，安装运行即自动脱壳",
                        R.drawable.ic_sandbox, orange, "免root", orange, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, RootlessDumpActivity.class); }
                        }));
                break;
        }
        return list;
    }

    private static List<GlassItem> buildSettings(Context context) {
        List<GlassItem> list = new ArrayList<>();
        String version = "v" + RxAppTool.getAppVersionName(context, context.getPackageName());
        int red = context.getResources().getColor(R.color.glass_accent_pink);
        int blue = context.getResources().getColor(R.color.glass_accent_blue);
        int violet = context.getResources().getColor(R.color.glass_accent_violet);
        int pink = context.getResources().getColor(R.color.glass_accent_pink);
        int orange = context.getResources().getColor(R.color.glass_accent_orange);

        list.add(new GlassItem("调试模式", null, R.drawable.ic_bug, red,
                null, 0, GlassItem.TYPE_TOGGLE, null));
        list.add(new GlassItem("检测更新", version, R.drawable.ic_fix, blue,
                null, 0, GlassItem.TYPE_NAV, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (context instanceof android.app.Activity) {
                            cn.mhook.update.UpdateManager.checkAndShow((android.app.Activity) context, true);
                        }
                    }
                }));
        list.add(new GlassItem("AI设置", "模型地址 / Key / 参数", R.drawable.ic_setting, blue,
                null, 0, GlassItem.TYPE_NAV, new View.OnClickListener() {
                    @Override public void onClick(View v) { RxActivityTool.skipActivity(context, cn.mhook.activity.ai.AiSettingActivity.class); }
                }));
        list.add(new GlassItem("MCP设置", "MCP 服务器与后端管理", R.drawable.ic_doc, violet,
                null, 0, GlassItem.TYPE_NAV, new View.OnClickListener() {
                    @Override public void onClick(View v) { RxActivityTool.skipActivity(context, cn.mhook.activity.ai.McpSettingActivity.class); }
                }));
        list.add(new GlassItem("用户协议", "重新阅读用户协议与使用需知", R.drawable.ic_doc, violet,
                null, 0, GlassItem.TYPE_NAV, new View.OnClickListener() {
                    @Override public void onClick(View v) { RxActivityTool.skipActivity(context, cn.mhook.activity.UserAgreementActivity.class); }
                }));
        list.add(new GlassItem("感谢项目", "本应用基于以下开源项目构建", R.drawable.ic_heart, pink,
                null, 0, GlassItem.TYPE_NAV, new View.OnClickListener() {
                    @Override public void onClick(View v) { RxActivityTool.skipActivity(context, cn.mhook.activity.ThanksActivity.class); }
                }));
        list.add(new GlassItem("打赏支持", null, R.drawable.ic_gift, orange,
                null, 0, GlassItem.TYPE_NAV, new View.OnClickListener() {
                    @Override public void onClick(View v) { RxActivityTool.skipActivity(context, DonateActivity.class); }
                }));
        return list;
    }
}
