package cn.mhook.mhook.xposed.dump;

import java.io.File;
import java.util.List;
import java.util.Locale;

import cn.mhook.mhook.xposed.dump.util.FileUtils;
import android.util.Log;

/**
 * FART 式「主动加载类」：骨架 dex → 枚举全部类描述符 → 逐个 Class.forName 主动加载，
 * 触发抽取壳在运行时把真实 codeItem 补回 ArtMethod，为后续 re-dump 完整 dex 创造条件。
 * 输出类似 FART 的脱壳日志（application / appComponentFactory / 主动加载类列表）。
 */
public class ActiveClassLoader {

    private static final String TAG = "ActiveDump";
    private volatile static boolean sBusy = false;

    /**
     * 对一个骨架 dex 做主动加载。
     * @param dexData  骨架 dex 字节
     * @param appClassLoader 目标应用 classloader（虚拟进程内为虚拟应用的）
     * @param outDir   输出目录（诊断日志）
     * @return 本次主动加载的类总数
     */
    public static int dumpAndTrigger(byte[] dexData, ClassLoader appClassLoader,
                                     File outDir, String dexName) {
        return dumpAndTrigger(dexData, appClassLoader, outDir, dexName, null);
    }

    public static int dumpAndTrigger(byte[] dexData, ClassLoader appClassLoader,
                                     File outDir, String dexName, Object application) {
        if (sBusy) return 0;
        sBusy = true;
        try {
            List<String> classes = DexInspector.listClassDescriptors(dexData);
            if (classes == null || classes.isEmpty()) return 0;
            StringBuilder sb = new StringBuilder();
            sb.append("\n***** dex: ").append(dexName).append(" *****\n");
            // application 段
            sb.append("***** application *****\n");
            try {
                if (application instanceof android.app.Application) {
                    sb.append(((android.app.Application) application).getClass().getName()).append("\n");
                } else {
                    sb.append("(未知, 未捕获 Application)\n");
                }
            } catch (Throwable t) {
                sb.append("(解析失败: ").append(t).append(")\n");
            }
            // appComponentFactory 段
            sb.append("****appComponentFactory****\n");
            try {
                String f = findAppComponentFactory();
                sb.append(f != null ? f : "(无)\n");
            } catch (Throwable t) {
                sb.append("(解析失败: ").append(t).append(")\n");
            }
            sb.append("****** 类总数: ").append(classes.size()).append(" *****\n");
            sb.append("****** 主动加载类 *****\n");
            int ok = 0;
            for (String desc : classes) {
                String cn = toClassName(desc);
                boolean loaded = false;
                if (cn != null && !cn.startsWith("android.") && !cn.startsWith("java.")
                        && !cn.startsWith("javax.") && !cn.startsWith("dalvik.")
                        && !cn.startsWith("kotlin.") && !cn.startsWith("androidx.core.")) {
                    try {
                        // initialize=false 只加载类不跑 <clinit>，避免副作用；静默失败
                        Class.forName(cn, false, appClassLoader);
                        loaded = true;
                        ok++;
                    } catch (Throwable t) {
                        loaded = false;
                    }
                }
                sb.append(loaded ? "  ✓ " : "  . ").append(cn == null ? desc : cn).append("\n");
            }
            sb.append("****** 加载成功: ").append(ok).append("/").append(classes.size())
                    .append(" 触发完毕(等待补码后由后续轮次 re-dump) *****\n");
            String full = sb.toString();
            // 写完整诊断日志文件（含全部类列表，供 zip 导出）
            try {
                if (outDir != null) {
                    if (!outDir.exists()) outDir.mkdirs();
                    File f = new File(outDir, "active_load.txt");
                    FileUtils.writeByteToFile(full.getBytes("UTF-8"), f.getAbsolutePath());
                }
            } catch (Throwable ignored) {
            }
            // 摘要（供 UI/日志精简显示）：application + factory + 类总数 + 加载成功
            StringBuilder sm = new StringBuilder();
            sm.append("***** application *****\n");
            try {
                if (application instanceof android.app.Application) {
                    sm.append(((android.app.Application) application).getClass().getName()).append("\n");
                }
            } catch (Throwable ignored) {
            }
            sm.append("****appComponentFactory****\n");
            try {
                String f = findAppComponentFactory();
                sm.append(f != null ? f : "(无)\n");
            } catch (Throwable ignored) {
            }
            sm.append("****** 类总数: ").append(classes.size())
                    .append(" ********** 加载成功: ").append(ok).append("/").append(classes.size())
                    .append(" 触发完毕 *****\n");
            Log.i(TAG, sm.toString().trim());
            return classes.size();
        } finally {
            sBusy = false;
        }
    }

    /** 通过 ActivityThread.mAppComponentFactory 反射拿到 factory 类名。 */
    private static String findAppComponentFactory() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object instance = null;
            try {
                instance = at.getMethod("currentActivityThread").invoke(null);
            } catch (Throwable ignored) {
            }
            if (instance == null) return null;
            java.lang.reflect.Field f = at.getDeclaredField("mAppComponentFactory");
            f.setAccessible(true);
            Object fac = f.get(instance);
            if (fac != null) return fac.getClass().getName();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Lfoo/Bar; → foo.Bar */
    private static String toClassName(String desc) {
        if (desc == null || desc.length() < 3) return null;
        if (!desc.startsWith("L") || !desc.endsWith(";")) return desc;
        return desc.substring(1, desc.length() - 1).replace('/', '.');
    }
}