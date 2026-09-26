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
import cn.mhook.activity.DeShellActivity;
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
            case PAGE_SHELL: title = "动态分析"; subtitle = "Dump 与沙箱运行"; break;
            case PAGE_HOOK: title = "Hook"; subtitle = "注入与行为监控"; break;
            case PAGE_PATCH: title = "应用修复"; subtitle = "热修复与自动改包"; break;
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
                list.add(new GlassItem("动态调试", "托管 frida-server / 运行时调试",
                        R.drawable.ic_bug, cyan, "需Root", orange, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, cn.mhook.activity.debug.FridaDebugActivity.class); }
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
                list.add(new GlassItem("加固强度检测", "选APK自动加固检测+免root动态分析重打包+签名强度检测", R.drawable.ic_fix, blue, "脱修", orange, GlassItem.TYPE_NAV, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                    // 加固强度检测需授权（离线一机一码）
                    if (cn.mhook.license.LicenseManager.isActivated(context)) {
                        RxActivityTool.skipActivity(context, DeShellActivity.class);
                    } else {
                        cn.mhook.widget.GlassToast.warning(context, "该功能需要授权");
                        RxActivityTool.skipActivity(context, cn.mhook.license.LicenseActivity.class);
                    }
                }
                }));
                break;
            case PAGE_SHELL:
            default:
                list.add(new GlassItem("内存分析", "纯Java内存分析，dump加固后的dex",
                        R.drawable.ic_shield, cyan, "需Xposed", orange, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, DumpActivity.class); }
                        }));
                list.add(new GlassItem("eBPF深度调试", "内核态 uprobe 被动抓取已执行的 dex 并回填抽取方法，适合抽取壳",
                        R.drawable.ic_bug, violet, "需root", orange, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                android.content.Intent i = new android.content.Intent(context, SandboxDumpActivity.class);
                                i.putExtra("ebpf", true);
                                context.startActivity(i);
                            }
                        }));
                list.add(new GlassItem("沙箱分析", "选APK自动装入沙箱运行并dump，主动加载+补码回收，全程免root",
                        R.drawable.ic_sandbox, green, "免root", green, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, SandboxDumpActivity.class); }
                        }));
                list.add(new GlassItem("重打包分析", "NPatch注入动态分析模块并重签名，安装运行即自动动态分析",
                        R.drawable.ic_sandbox, orange, "免root", orange, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, RootlessDumpActivity.class); }
                        }));
                list.add(new GlassItem("签名强度检测", "选APK选模式签名强度检测并重签名，输出Download/mhook_dump",
                        R.drawable.ic_shield, cyan, "免root", cyan, GlassItem.TYPE_NAV, new View.OnClickListener() {
                            @Override public void onClick(View v) { RxActivityTool.skipActivity(context, cn.mhook.activity.SignBypassActivity.class); }
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
        list.add(new GlassItem("推介-智盾加固", "为你的app保驾护航", R.drawable.ic_shield, violet,
                null, 0, GlassItem.TYPE_NAV, new View.OnClickListener() {
                    @Override public void onClick(View v) { openUrl(context, "https://zdcod.com"); }
                }));
        list.add(new GlassItem("打赏支持", null, R.drawable.ic_gift, orange,
                null, 0, GlassItem.TYPE_NAV, new View.OnClickListener() {
                    @Override public void onClick(View v) { RxActivityTool.skipActivity(context, DonateActivity.class); }
                }));
        return list;
    }

    /** 用浏览器打开链接。 */
    public static void openUrl(Context context, String url) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable t) {
            cn.mhook.widget.GlassToast.error(context, "无法打开链接：" + url);
        }
    }
}
