package cn.mhook.debug;

import android.content.Context;
import android.content.SharedPreferences;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

/**
 * 把 frida 动态调试能力注册为 AI 内置工具（function calling）。
 * AI 可：查看状态 → 注入 → 写/改脚本并执行 → 重启目标 → 读输出 → 迭代。
 */
public class FridaTools {

    private static final String PREFS = "frida_target";
    private static final String K_PKG = "pkg";
    private static final String K_NAME = "name";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void setTarget(Context c, String pkg, String name) {
        sp(c).edit().putString(K_PKG, pkg == null ? "" : pkg)
                .putString(K_NAME, name == null ? "" : name).apply();
    }

    public static String targetPkg(Context c) {
        return sp(c).getString(K_PKG, "");
    }

    public static String targetName(Context c) {
        return sp(c).getString(K_NAME, "");
    }

    private static final String[] NAMES = {
            "frida_status", "frida_inject", "frida_run_script", "frida_restart_app",
            "frida_get_output", "frida_clear_log", "frida_list_modules", "frida_dump_dex"
    };

    public static boolean isFridaTool(String name) {
        if (name == null) {
            return false;
        }
        for (String n : NAMES) {
            if (n.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public static JSONArray buildTools() {
        JSONArray arr = new JSONArray();
        arr.add(fn("frida_status", "查看当前调试目标与注入状态（目标包名/名称、是否已注入、gadget 是否就绪）", null, null));
        arr.add(fn("frida_inject", "对目标 App 注入 frida-gadget（免改包，需 root）。目标默认用 frida_status 里的当前目标，也可传 pkg 指定。",
                props("pkg", "string", "目标包名（可省略，用当前目标）"), null));
        arr.add(fn("frida_run_script", "部署一段 frida JS 脚本到目标 App（写入 /data/local/tmp/mhook_agent.js 并配置 gadget 加载）。之后需 frida_restart_app 让目标 App 重启才会执行。脚本用 console/Java Log(tag MHKDBG) 或写文件输出。",
                props("script", "string", "完整 frida JS 脚本内容"), new String[]{"script"}));
        arr.add(fn("frida_restart_app", "重启目标 App（force-stop + 启动），触发注入的脚本执行。",
                props("pkg", "string", "目标包名（可省略）"), null));
        arr.add(fn("frida_get_output", "读取目标 App 脚本输出（logcat tag MHKDBG）。",
                props("filter", "string", "关键字过滤（可省略）"), null));
        arr.add(fn("frida_clear_log", "清空脚本输出日志缓冲。", null, null));
        arr.add(fn("frida_list_modules", "列出目标 App 已加载的 so 模块（名/基址/大小）。会部署脚本，需再 frida_restart_app。", null, null));
        arr.add(fn("frida_dump_dex", "对目标 App 一键动态分析：内存扫描 dump 所有 dex 并打包 zip 到 Download/mhook_dump/（自动重启 App + 等待）。", null, null));
        return arr;
    }

    private static JSONObject props(String k, String type, String desc) {
        JSONObject p = new JSONObject(true);
        JSONObject o = new JSONObject(true);
        o.put("type", type);
        o.put("description", desc);
        p.put(k, o);
        return p;
    }

    private static JSONObject fn(String name, String desc, JSONObject props, String[] required) {
        JSONObject params = new JSONObject(true);
        params.put("type", "object");
        params.put("properties", props == null ? new JSONObject(true) : props);
        JSONArray req = new JSONArray();
        if (required != null) {
            for (String r : required) {
                req.add(r);
            }
        }
        params.put("required", req);
        JSONObject f = new JSONObject(true);
        f.put("name", name);
        f.put("description", desc);
        f.put("parameters", params);
        JSONObject w = new JSONObject(true);
        w.put("type", "function");
        w.put("function", f);
        return w;
    }

    /** 同步执行（AiSession 在后台线程调用）。 */
    public static String execute(Context ctx, String name, JSONObject args) {
        try {
            String pkg = targetPkg(ctx);
            if (args != null && args.getString("pkg") != null && !args.getString("pkg").trim().isEmpty()) {
                pkg = args.getString("pkg").trim();
            }
            final StringBuilder log = new StringBuilder();
            FridaServerManager.Progress prog = new FridaServerManager.Progress() {
                @Override
                public void onLog(String s) {
                    log.append(s).append('\n');
                }
            };

            if ("frida_status".equals(name)) {
                String p = targetPkg(ctx);
                return "当前目标: " + (p.isEmpty() ? "(未设置，请先 frida_inject 并传 pkg)" : (targetName(ctx) + " · " + p))
                        + "\n已注入: " + (!p.isEmpty() && GadgetManager.isInjected(p))
                        + "\nroot: " + FridaServerManager.hasRoot()
                        + "\ngadget 已解压: " + GadgetManager.isExtracted(ctx);
            }
            if ("frida_inject".equals(name)) {
                if (pkg.isEmpty()) {
                    return "缺少目标包名 pkg";
                }
                setTarget(ctx, pkg, targetName(ctx));
                if (!GadgetManager.isExtracted(ctx)) {
                    if (GadgetManager.extractGadget(ctx, prog) == null) {
                        return "解压 gadget 失败\n" + log;
                    }
                }
                if (!GadgetManager.deploy(ctx, null, prog)) {
                    return "部署失败\n" + log;
                }
                GadgetManager.inject(ctx, pkg, prog);
                return "注入完成（重启目标 App 生效）\n" + log;
            }
            if ("frida_run_script".equals(name)) {
                String script = args == null ? null : args.getString("script");
                if (script == null || script.trim().isEmpty()) {
                    return "缺少 script";
                }
                if (pkg.isEmpty()) {
                    return "缺少目标（先 frida_inject 传 pkg）";
                }
                if (!GadgetManager.isExtracted(ctx) && GadgetManager.extractGadget(ctx, prog) == null) {
                    return "解压 gadget 失败\n" + log;
                }
                if (!GadgetManager.deploy(ctx, script, prog)) {
                    return "部署脚本失败\n" + log;
                }
                GadgetManager.inject(ctx, pkg, prog);
                return "脚本已部署，请调用 frida_restart_app 重启目标后 frida_get_output 查看输出\n" + log;
            }
            if ("frida_restart_app".equals(name)) {
                if (pkg.isEmpty()) {
                    return "缺少目标";
                }
                GadgetManager.restartApp(pkg);
                return "已重启 " + pkg;
            }
            if ("frida_get_output".equals(name)) {
                String filter = args == null ? "" : args.getString("filter");
                String out = GadgetManager.readLogcatOutput(filter == null ? "" : filter);
                return out == null || out.trim().isEmpty() ? "(无输出)" : out;
            }
            if ("frida_clear_log".equals(name)) {
                cn.mhook.msu.su.getOutput("logcat -c");
                return "已清空日志";
            }
            if ("frida_list_modules".equals(name)) {
                if (pkg.isEmpty()) {
                    return "缺少目标（先 frida_inject 传 pkg）";
                }
                if (!GadgetManager.isExtracted(ctx) && GadgetManager.extractGadget(ctx, prog) == null) {
                    return "解压 gadget 失败\n" + log;
                }
                GadgetManager.deploy(ctx, NativeScriptBuilder.listModules(), prog);
                GadgetManager.inject(ctx, pkg, prog);
                return "已部署列模块脚本，请 frida_restart_app 后 frida_get_output\n" + log;
            }
            if ("frida_dump_dex".equals(name)) {
                if (pkg.isEmpty()) {
                    return "缺少目标";
                }
                GadgetManager.cleanDexDump(pkg);
                if (!GadgetManager.isExtracted(ctx) && GadgetManager.extractGadget(ctx, prog) == null) {
                    return "解压 gadget 失败\n" + log;
                }
                GadgetManager.deploy(ctx, NativeScriptBuilder.dumpDex(pkg), prog);
                GadgetManager.inject(ctx, pkg, prog);
                GadgetManager.restartApp(pkg);
                int last = -1, stable = 0;
                for (int i = 0; i < 45; i++) {
                    Thread.sleep(2000);
                    int cnt = GadgetManager.countDexDump(pkg);
                    if (cnt > 0 && cnt == last) {
                        stable++;
                        if (stable >= 2) {
                            break;
                        }
                    } else {
                        stable = 0;
                    }
                    last = cnt;
                }
                java.io.File zip = GadgetManager.packageDexDump(ctx, pkg, prog);
                return zip == null ? ("动态分析打包失败\n" + log)
                        : ("动态分析完成 -> " + zip.getAbsolutePath() + "\n" + log);
            }
            return "[未知 frida 工具] " + name;
        } catch (Throwable t) {
            return "[frida 工具异常] " + name + ": " + t;
        }
    }
}
