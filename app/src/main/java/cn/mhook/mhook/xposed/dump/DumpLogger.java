package cn.mhook.mhook.xposed.dump;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

import cn.mhook.mhook.xposed.dump.util.FileUtils;
import de.robv.android.xposed.XposedBridge;

import static cn.mhook.mhook.xposed.utils.mHookCfg.dumpDir;

/**
 * 沙盒脱壳诊断日志：事件流 + 命中统计 + 产物体检，落盘到 dumpDir/dump_log.txt。
 * 双通道：XposedBridge.log（logcat 可看）+ 追加写文件（可复制）。
 */
public class DumpLogger {

    private static final StringBuilder BUF = new StringBuilder();
    private static final long START = System.currentTimeMillis();
    private static long lastTs = START;
    private static String pkg = "";

    // 命中统计
    private static int hookInMemory = 0, hookInMemoryValid = 0;
    private static int hookDexClassLoader = 0, hookDexClassLoaderValid = 0;
    private static int hookDexFile = 0, hookDexFileValid = 0;
    private static int goEnum = 0, enumDumped = 0;
    private static int dumpedTotal = 0, skeletonCount = 0;

    public static void setPkg(String p) { pkg = p == null ? "" : p; }

    public static synchronized void event(String tag, String msg) {
        long now = System.currentTimeMillis();
        long delta = now - START;
        String line = String.format(Locale.US, "[+%6dms] %s: %s%n", delta, tag, msg);
        BUF.append(line);
        XposedBridge.log("DumpLog " + line.trim());
    }

    public static void hookHit(String hookName) {
        if ("InMemoryDexClassLoader".equals(hookName)) hookInMemory++;
        else if ("DexClassLoader".equals(hookName)) hookDexClassLoader++;
        else if ("DexFile".equals(hookName)) hookDexFile++;
    }

    public static void hookValid(String hookName) {
        if ("InMemoryDexClassLoader".equals(hookName)) hookInMemoryValid++;
        else if ("DexClassLoader".equals(hookName)) hookDexClassLoaderValid++;
        else if ("DexFile".equals(hookName)) hookDexFileValid++;
    }

    public static synchronized int dumpedCount() {
        return dumpedTotal;
    }

    public static void registerDump(boolean skeleton, String detail) {
        dumpedTotal++;
        if (skeleton) skeletonCount++;
        lastTs = System.currentTimeMillis();
        event("产出", "保存 dex, 明细=" + detail);
    }

    public static void enumHit(boolean dumped) {
        goEnum++;
        if (dumped) enumDumped++;
    }

    public static void enumRun() { goEnum++; }

    /** 写总结段到文件。 */
    public static synchronized void writeSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== 脱壳总结 ==========\n");
        sb.append(String.format(Locale.US,
                "hook命中: InMemoryDex=%d(有效%d) DexClassLoader=%d(有效%d) DexFile=%d(有效%d)%n",
                hookInMemory, hookInMemoryValid,
                hookDexClassLoader, hookDexClassLoaderValid,
                hookDexFile, hookDexFileValid));
        sb.append(String.format(Locale.US, "定时枚举: 运行%d次, 新增dex=%d%n", goEnum, enumDumped));
        sb.append(String.format(Locale.US, "总dump: %d, 其中骨架(疑似抽取)=%d%n", dumpedTotal, skeletonCount));
        sb.append("loadClass hook: 未启用(防加固检测, 注释见 MemoryDexDumper)\n");
        BUF.append(sb);
        flush();
    }

    /** 追加写入文件。 */
    private static void flush() {
        try {
            File dir = new File(dumpDir);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "dump_log.txt");
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write(BUF.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();
            BUF.setLength(0);
        } catch (Throwable t) {
            XposedBridge.log("DumpLog write failed: " + t);
        }
    }

    /** 写一条到日志（带 flush）。 */
    public static void logNow(String tag, String msg) {
        event(tag, msg);
        flush();
    }
}