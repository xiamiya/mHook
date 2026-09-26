package cn.mhook.debug;

import android.content.Context;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import cn.mhook.msu.su;

/**
 * frida-gadget 注入托管（阶段 0 / P0.3）。
 *
 * 释放 gadget → 部署 config/脚本/wrap.sh 到 /data/local/tmp →
 * setprop wrap.&lt;pkg&gt; 注入 → 目标 App 下次启动即通过 LD_PRELOAD 加载 gadget 并执行脚本。
 * 输出：脚本写 /sdcard/mhook_debug.log + logcat(tag MHKDBG)。
 */
public class GadgetManager {

    public static final String DIR = "/data/local/tmp";
    public static final String GADGET = DIR + "/libgadget.so";
    public static final String GADGET_CFG = DIR + "/libgadget.config.so";
    public static final String SCRIPT = DIR + "/mhook_agent.js";
    public static final String WRAP = DIR + "/mhook_wrap.sh";
    public static final String OUT_LOG = "/sdcard/mhook_debug.log";

    private static void log(FridaServerManager.Progress p, String s) {
        if (p != null) {
            p.onLog(s);
        }
    }

    public static String abiTag() {
        return FridaServerManager.abiTag();
    }

    public static File localGadget(Context ctx) {
        String abi = abiTag();
        if (abi == null) {
            return null;
        }
        return new File(new File(ctx.getFilesDir(), "frida"), "libgadget-" + abi + ".so");
    }

    public static boolean isExtracted(Context ctx) {
        File f = localGadget(ctx);
        return f != null && f.isFile() && f.length() > 0;
    }

    /** 从 assets 解压 gadget（xz）到私有目录。 */
    public static File extractGadget(Context ctx, FridaServerManager.Progress p) {
        String abi = abiTag();
        if (abi == null) {
            log(p, "不支持的 CPU 架构");
            return null;
        }
        File dir = new File(ctx.getFilesDir(), "frida");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File out = new File(dir, "libgadget-" + abi + ".so");
        File tmp = new File(dir, "libgadget-" + abi + ".so.tmp");
        String asset = "frida/frida-gadget-" + abi + ".so.xz";
        InputStream raw;
        try {
            // 优先本地已下载的 xz（FridaAssetDownloader），缺失时回退 assets
            File xz = new File(dir, "frida-gadget-" + abi + ".so.xz");
            if (xz.isFile() && xz.length() > 0) {
                log(p, "解压本地 " + xz.getName() + " ...");
                raw = new FileInputStream(xz);
            } else {
                log(p, "解压 " + asset + " ...");
                raw = ctx.getAssets().open(asset);
            }
        } catch (Throwable t) {
            log(p, "frida-gadget 未内置且未下载，请先点「下载 frida 组件」");
            return null;
        }
        try {
            XZInputStream in = new XZInputStream(new BufferedInputStream(raw, 1 << 16));
            OutputStream os = new FileOutputStream(tmp);
            byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            long last = 0;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
                if (total - last >= (4L << 20)) {
                    last = total;
                    log(p, "  已解压 " + (total >> 20) + " MB");
                }
            }
            os.close();
            in.close();
            raw.close();
            if (out.exists() && !out.delete()) {
                log(p, "覆盖旧 gadget 失败");
            }
            if (!tmp.renameTo(out)) {
                copy(tmp, out);
                tmp.delete();
            }
            log(p, "gadget 解压完成：" + (out.length() >> 20) + " MB");
            return out;
        } catch (Throwable t) {
            log(p, "解压失败：" + t);
            try {
                tmp.delete();
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    private static String configJson() {
        return "{\n  \"interaction\": {\n    \"type\": \"script\",\n    \"path\": \"" + SCRIPT
                + "\",\n    \"on_change\": \"reload\"\n  }\n}\n";
    }

    private static final String WRAP_SH =
            "#!/system/bin/sh\nexport LD_PRELOAD=" + GADGET + "\nexec \"$@\"\n";

    /** 部署 gadget / config / 脚本 / wrap.sh 到 /data/local/tmp。 */
    public static boolean deploy(Context ctx, String scriptContent, FridaServerManager.Progress p) {
        if (!FridaServerManager.hasRoot()) {
            log(p, "需要 root 权限");
            return false;
        }
        File g = isExtracted(ctx) ? localGadget(ctx) : extractGadget(ctx, p);
        if (g == null) {
            log(p, "gadget 未就绪");
            return false;
        }
        log(p, "部署 gadget 到 " + DIR + " ...");
        su.getOutput("cp \"" + g.getAbsolutePath() + "\" " + GADGET + " && chmod 644 " + GADGET);
        pushText(ctx, "libgadget.config.so", configJson(), p);
        pushText(ctx, "mhook_agent.js", scriptContent == null || scriptContent.trim().isEmpty()
                ? defaultScript() : scriptContent, p);
        pushText(ctx, "mhook_wrap.sh", WRAP_SH, p);
        su.getOutput("chmod 755 " + WRAP);
        String chk = su.getOutput("test -f " + GADGET + " -a -f " + GADGET_CFG
                + " -a -f " + SCRIPT + " -a -x " + WRAP + " && echo MHOOK_OK || echo MHOOK_NO");
        boolean ok = chk != null && chk.contains("MHOOK_OK");
        log(p, ok ? "部署完成" : "部署校验失败");
        return ok;
    }

    private static void pushText(Context ctx, String name, String content, FridaServerManager.Progress p) {
        try {
            File dir = new File(ctx.getFilesDir(), "frida");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
            su.getOutput("cp \"" + f.getAbsolutePath() + "\" " + DIR + "/" + name
                    + " && chmod 644 " + DIR + "/" + name);
        } catch (Throwable t) {
            log(p, "写入 " + name + " 失败：" + t);
        }
    }

    /** 通过 wrap 属性注入目标包。 */
    public static boolean inject(Context ctx, String pkg, FridaServerManager.Progress p) {
        if (pkg == null || pkg.trim().isEmpty()) {
            log(p, "包名为空");
            return false;
        }
        pkg = pkg.trim();
        su.getOutput("setprop wrap." + pkg + " " + WRAP);
        String v = su.getOutput("getprop wrap." + pkg);
        boolean ok = v != null && v.contains("mhook_wrap.sh");
        log(p, ok ? ("已注入 " + pkg + "（重启该 App 生效）") : "注入失败");
        return ok;
    }

    public static boolean uninject(String pkg, FridaServerManager.Progress p) {
        if (pkg == null || pkg.trim().isEmpty()) {
            return false;
        }
        pkg = pkg.trim();
        su.getOutput("setprop wrap." + pkg + " ''");
        su.getOutput("am force-stop " + pkg);
        log(p, "已取消注入 " + pkg);
        return true;
    }

    public static boolean isInjected(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) {
            return false;
        }
        String v = su.getOutput("getprop wrap." + pkg.trim());
        return v != null && v.contains("mhook_wrap.sh");
    }

    /** 读取目标 App 的脚本输出（logcat tag MHKDBG，需要 root）。 */
    public static String readLogcatOutput() {
        StringBuilder sb = new StringBuilder();
        try {
            String lc = su.getOutput("logcat -d -s MHKDBG -t 800");
            if (lc != null) {
                for (String line : lc.split("\n")) {
                    int idx = line.indexOf("MHKDBG");
                    if (idx < 0) {
                        continue;
                    }
                    int c = line.indexOf(':', idx);
                    sb.append(c >= 0 ? line.substring(c + 1).trim() : line.trim()).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("logcat 读取失败：").append(t).append('\n');
        }
        return sb.toString();
    }

    /** 读取目标 App 的脚本输出（logcat tag MHKDBG，需要 root），可选关键字过滤。 */
    public static String readLogcatOutput(String filter) {
        String all = readLogcatOutput();
        if (filter == null || filter.trim().isEmpty()) {
            return all;
        }
        String f = filter.trim();
        StringBuilder sb = new StringBuilder();
        for (String line : all.split("\n")) {
            if (line.contains(f)) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** 把目标 App 私有目录里的 mhook_dump_*.bin 导出到 /sdcard/Download/mhook_dumps/<pkg>/。 */
    public static String exportDumps(String pkg, FridaServerManager.Progress p) {
        if (!FridaServerManager.hasRoot()) {
            log(p, "需要 root 权限");
            return null;
        }
        if (pkg == null || pkg.trim().isEmpty()) {
            log(p, "包名为空");
            return null;
        }
        pkg = pkg.trim();
        String src = "/data/data/" + pkg + "/files";
        String dst = "/sdcard/Download/mhook_dumps/" + pkg;
        String listing = su.getOutput("ls " + src);
        if (listing == null || !listing.contains("mhook_dump_")) {
            log(p, "未找到 dump 文件（先在 Native 调试里 Dump 内存）");
            return null;
        }
        StringBuilder toCopy = new StringBuilder();
        for (String f : listing.split("\n")) {
            String name = f.trim();
            if (name.startsWith("mhook_dump_")) {
                toCopy.append(" \"").append(src).append("/").append(name).append("\"");
            }
        }
        su.getOutput("mkdir -p \"" + dst + "\" && cp" + toCopy + " \"" + dst + "/\""
                + " && chmod 644 \"" + dst + "\"/*.bin");
        // 一并导出 DEX 动态分析产物
        su.getOutput("cp -r " + src + "/dexdump \"" + dst + "/\" 2>/dev/null");
        su.getOutput("chmod -R 644 \"" + dst + "\"/dexdump 2>/dev/null");
        String ls = su.getOutput("ls -l \"" + dst + "\"");
        log(p, "已导出到 " + dst);
        log(p, ls);
        return dst;
    }

    /** 默认调试脚本：打印进程/native 模块信息，等 ART 就绪后输出 Java 信息到 logcat。 */
    public static String defaultScript() {
        return "var pending=[];\n"
                + "function out(s){\n"
                + "  console.log(\"[MHOOK] \"+s);\n"
                + "  try{var f=new File(\"" + OUT_LOG + "\",\"a\");f.write(\"[MHOOK] \"+s+\"\\n\");f.flush();f.close();}catch(e){}\n"
                + "  if(Java.available){try{Java.perform(function(){Java.use(\"android.util.Log\").i(\"MHKDBG\",s);});}catch(e){}}\n"
                + "  else{pending.push(s);}\n"
                + "}\n"
                + "function flush(){if(!Java.available)return;try{Java.perform(function(){var L=Java.use(\"android.util.Log\");for(var i=0;i<pending.length;i++){L.i(\"MHKDBG\",pending[i]);}pending=[];});}catch(e){}}\n"
                + "out(\"gadget loaded pid=\"+Process.id+\" arch=\"+Process.arch);\n"
                + "try{out(\"modules=\"+Process.enumerateModules().length);}catch(e){out(\"enumModules err=\"+e);}\n"
                + "var t=0,tm=setInterval(function(){\n"
                + "  t++;\n"
                + "  if(Java.available){clearInterval(tm);flush();out(\"java ready pid=\"+Process.id);}\n"
                + "  else if(t>=60){clearInterval(tm);}\n"
                + "},200);\n";
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
            out.close();
        }
    }

    /** 重启目标 App（force-stop + 启动 launcher activity）。 */
    public static boolean restartApp(String pkg) {
        try {
            String act = su.getOutput("cmd package resolve-activity --brief " + pkg);
            if (act == null) {
                act = "";
            }
            String[] lines = act.trim().split("\n");
            String launcher = lines[lines.length - 1].trim();
            su.getOutput("am force-stop " + pkg);
            sleep(600);
            if (!launcher.isEmpty()) {
                su.getOutput("am start -n " + launcher);
            } else {
                su.getOutput("monkey -p " + pkg + " 1");
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把 &lt;pkg&gt;/files/dexdump 打包成 zip 到 Download/mhook_dump/。 */
        /** 把 &lt;pkg&gt;/files/dexdump 打包成 tar.gz 到 Download/mhook_dump/（设备无 zip 命令，用 tar 一步打包）。 */
        /**
     * 把 &lt;pkg&gt;/files/dexdump 打包成 zip 到 Download/mhook_dump/。
     * 不依赖设备上的 zip/tar（su -c 执行复杂命令不稳定），改为逐个 su cat 读流由 Java 打包。
     */
        /**
     * 把 &lt;pkg&gt;/files/dexdump 打包到 Download/mhook_dump/。
     * 用 ProcessBuilder 直接执行 su tar（不经过文本读取，避免 su.getOutput 的坑）。
     */
        /**
     * 把 &lt;pkg&gt;/files/dexdump 打包成 zip 到 Download/mhook_dump/。
     * 走 /proc/1/root 全局视图逐个 su cat 读取（App 自身 mount namespace 看不到别的 App 数据），
     * 由 Java 写 zip（设备无 zip 命令）。
     */
    public static File packageDexDump(Context ctx, String pkg, FridaServerManager.Progress p) {
        File outDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "mhook_dump");
        if (!outDir.exists()) {
            outDir.mkdirs();
        }
        final String dir = "/proc/1/root/data/data/" + pkg + "/files/dexdump";
        String listing = su.getOutput("ls " + dir);
        if (listing == null || listing.trim().isEmpty()) {
            log(p, "未找到 dexdump（可能尚未动态分析）");
            return null;
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        File out = new File(outDir, "dex_" + pkg + "_" + stamp + ".zip");
        if (out.exists()) {
            out.delete();
        }
        int n = 0;
        long total = 0;
        try {
            java.util.zip.ZipOutputStream zos =
                    new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(out));
            byte[] buf = new byte[1 << 16];
            for (String raw : listing.split("\n")) {
                final String name = raw.trim();
                if (name.isEmpty()) {
                    continue;
                }
                Process proc = new ProcessBuilder("su", "-c", "cat " + dir + "/" + name).start();
                java.io.InputStream in = proc.getInputStream();
                zos.putNextEntry(new java.util.zip.ZipEntry(name));
                int r;
                while ((r = in.read(buf)) > 0) {
                    zos.write(buf, 0, r);
                    total += r;
                }
                in.close();
                proc.waitFor();
                zos.closeEntry();
                n++;
                if (n % 10 == 0) {
                    log(p, "  已打包 " + n + " 个 ...");
                }
            }
            zos.close();
            log(p, "已打包 " + n + " 个 dex / " + (total >> 20) + " MB -> " + out.getAbsolutePath());
            return out;
        } catch (Throwable t) {
            log(p, "打包失败：" + t);
            return null;
        }
    }

    /** 清理目标 App 私有目录里旧的动态分析产物。 */
    public static void cleanDexDump(String pkg) {
        su.getOutput("rm -rf /data/data/" + pkg + "/files/dexdump");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /** 统计目标 App 私有目录里已 dump 的 dex 数量。 */
    public static int countDexDump(String pkg) {
        try {
            // App 的 mount namespace 看不到别的 App 的 /data/data，走 /proc/1/root（init 的全局视图）
            String r = su.getOutput("ls /proc/1/root/data/data/" + pkg + "/files/dexdump 2>&1");
            android.util.Log.i("MHKDBG", "countDexDump raw=[" + r + "]");
            if (r == null) {
                return 0;
            }
            int n = 0;
            for (String line : r.split("\n")) {
                if (!line.trim().isEmpty()) {
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 开启目标 App 的 JDWP 调试（root 下 am set-debug-app，App 正常启动并可被 JDWP 附着）。 */
    public static boolean enableJdwp(String pkg, FridaServerManager.Progress p) {
        if (!FridaServerManager.hasRoot()) {
            log(p, "需要 root 权限");
            return false;
        }
        su.getOutput("am set-debug-app --persistent " + pkg);
        log(p, "已开启 JDWP（" + pkg + "）。重启 App 后可用：");
        log(p, "  adb shell am set-debug-app --persistent " + pkg);
        log(p, "  adb forward tcp:8700 jdwp:<pid> 然后 jdb -attach localhost:8700");
        return true;
    }

    /** 关闭 JDWP 调试应用设置。 */
    public static boolean disableJdwp(FridaServerManager.Progress p) {
        su.getOutput("am clear-debug-app");
        log(p, "已清除调试应用设置");
        return true;
    }

    /** 日志分级读取：mode = all / call / return / error。 */
    public static String readLogcatOutput(String filter, String mode) {
        String all = readLogcatOutput(filter);
        if (mode == null || mode.isEmpty() || "all".equals(mode)) {
            return all;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : all.split("\n")) {
            boolean keep;
            if ("call".equals(mode)) {
                keep = line.contains("-> ");
            } else if ("return".equals(mode)) {
                keep = line.contains("<- ");
            } else if ("error".equals(mode)) {
                keep = line.contains("err") || line.contains("failed") || line.contains("error");
            } else {
                keep = true;
            }
            if (keep) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** 导出调用日志为结构化 JSON 到 Download/mhook_dump/。 */
    public static File exportCallLogJson(String pkg, FridaServerManager.Progress p) {
        File outDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "mhook_dump");
        if (!outDir.exists()) {
            outDir.mkdirs();
        }
        String lc = su.getOutput("logcat -d -s MHKDBG -t 2000");
        if (lc == null) {
            lc = "";
        }
        com.alibaba.fastjson.JSONArray arr = new com.alibaba.fastjson.JSONArray();
        com.alibaba.fastjson.JSONObject cur = null;
        int calls = 0, returns = 0, errors = 0;
        for (String line : lc.split("\n")) {
            int idx = line.indexOf("MHKDBG");
            if (idx < 0) {
                continue;
            }
            int c = line.indexOf(':', idx);
            String msg = c >= 0 ? line.substring(c + 1).trim() : line.trim();
            if (msg.startsWith("-> ")) {
                String body = msg.substring(3);
                int par = body.indexOf('(');
                String target = par >= 0 ? body.substring(0, par) : body;
                String args = par >= 0 && body.endsWith(")")
                        ? body.substring(par + 1, body.length() - 1) : "";
                cur = new com.alibaba.fastjson.JSONObject(true);
                cur.put("target", target);
                cur.put("args", args);
                arr.add(cur);
                calls++;
            } else if (msg.startsWith("<- ")) {
                String body = msg.substring(3);
                int ri = body.indexOf(" ret=");
                String target = ri >= 0 ? body.substring(0, ri) : body;
                String ret = ri >= 0 ? body.substring(ri + 5) : "";
                if (cur != null && target.equals(cur.getString("target"))) {
                    cur.put("ret", ret);
                } else {
                    com.alibaba.fastjson.JSONObject o = new com.alibaba.fastjson.JSONObject(true);
                    o.put("target", target);
                    o.put("ret", ret);
                    arr.add(o);
                }
                cur = null;
                returns++;
            } else if (msg.contains("err") || msg.contains("failed") || msg.contains("error")) {
                com.alibaba.fastjson.JSONObject o = new com.alibaba.fastjson.JSONObject(true);
                o.put("type", "error");
                o.put("msg", msg);
                arr.add(o);
                errors++;
            }
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        File out = new File(outDir, "calllog_" + pkg + "_" + stamp + ".json");
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write(arr.toJSONString().getBytes("UTF-8"));
            fos.close();
            log(p, "已导出 " + arr.size() + " 条（call=" + calls + " ret=" + returns + " err=" + errors + "）");
            log(p, "-> " + out.getAbsolutePath());
            return out;
        } catch (Throwable t) {
            log(p, "导出失败：" + t);
            return null;
        }
    }
}
