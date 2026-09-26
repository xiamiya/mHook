package cn.mhook.mhook.xposed.dump;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

import cn.mhook.mhook.xposed.dump.util.FileUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static cn.mhook.mhook.xposed.utils.mHookCfg.dumpDir;
import static cn.mhook.mhook.xposed.utils.mHookCfg.mDir;

/**
 * 纯 Java 内存分析
 *
 * 三层方案：
 * 1. hook InMemoryDexClassLoader 构造方法，直接拷贝 ByteBuffer/ByteBuffer[] 中的 dex
 * 2. hook DexClassLoader / DexFile 构造方法，拷贝磁盘上的 dex 文件
 * 3. hook ClassLoader.loadClass 兜底，通过 Class->DexCache->DexFile->mCookie
 *    拿到 ArtDexFile 内存地址，用 sun.misc.Unsafe 读出完整 dex
 */
public class MemoryDexDumper {

    private static final byte[] DEX_MAGIC = {0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00};

    private static boolean startsWithMagic(byte[] b) {
        // 兼容各 dex 版本：dex\n035\0 / 036 / 037 / 038 / 039 ...
        if (b == null || b.length < 8) return false;
        if (b[0] != 0x64 || b[1] != 0x65 || b[2] != 0x78 || b[3] != 0x0A) return false;
        if (b[4] != 0x30 || b[7] != 0x00) return false;
        return b[5] >= 0x33 && b[5] <= 0x39;
    }

    private static final Set<String> sDumped = new HashSet<>();
    private static final Set<String> sSeenPaths = new HashSet<>();
    private static final Set<Long> sSeenCookies = new HashSet<>();
    private static final Set<String> sProbedLayout = new HashSet<>();
    private static boolean sDiagnosedNative;

    /** 补码回收：骨架 dex 的 cookie→最近一次落盘 CRC，及用于主动调用的类描述符源 */
    private static final Set<Long> sSkeletonCookies = new HashSet<>();
    private static final Map<Long, String> sSkeletonBase = new HashMap<>();
    private static final Map<Long, List<String>> sSkeletonClasses = new HashMap<>();
    private static long sActiveCookie;
    private static long sActiveIdx;

    public static void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        DumpLogger.setPkg(lpparam.packageName);
        DumpLogger.event("入口", "Application.attachBaseContext / 动态分析模块装载, pkg=" + lpparam.packageName);
        probeLayout("DexCache");
        probeLayout("DexFile");
        hookInMemoryDexClassLoader(lpparam);
        hookDexClassLoader(lpparam);
        hookDexFile(lpparam);
        // 不 hook loadClass：会频繁触发原生内存读取，易被加固（如阿里 Ashield）检测崩溃。
        // 改为延迟后台一次性枚举已加载 dex，用 cookie 的 begin/size 精确定位读取（BlackDex 思路）。
        DumpLogger.event("入口", "hook 注册完成: InMemoryDex/DexClassLoader/DexFile");
        sAppClassLoader = lpparam.classLoader;
        scheduleDumpAll();
    }

    private static ClassLoader sAppClassLoader;

    /**
     * 多批次 + 手动触发：后台线程持续运行，
     * 在 3/8/15/25/40/60 秒各枚举一次（抓延迟解密的壳），
     * 并轮询 dump_now 标志文件实现 UI 的「立即动态分析」。
     */
    private static void scheduleDumpAll() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long[] at = {3000, 8000, 15000, 25000, 40000, 60000};
                long start = System.currentTimeMillis();
                int round = 0;
                while (true) {
                    try {
                        File flag = new File(mDir + "dump_now");
                        if (flag.exists()) {
                            try { flag.delete(); } catch (Throwable ignored) {
                            }
                            try { dumpAllLoadedDexes(); } catch (Throwable ignored) {
                            }
                            String cons = DexConsolidator.consolidate(new File(dumpDir));
                            if (cons != null) DumpLogger.event("整理", cons);
                            scanAndWriteRealApp();
                            try { activeCallOnce(); } catch (Throwable ignored) {
                            }
                            try { redumpFilled(); } catch (Throwable ignored) {
                            }
                            continue;
                        }
                        long el = System.currentTimeMillis() - start;
                        if (round < at.length && el >= at[round]) {
                            round++;
                            DumpLogger.event("枚举", "第" + round + "轮定时枚举开始");
                            try { dumpAllLoadedDexes(); } catch (Throwable ignored) {
                            }
                            String cons = DexConsolidator.consolidate(new File(dumpDir));
                            if (cons != null) DumpLogger.event("整理", cons);
                            scanAndWriteRealApp();
                            try { activeCallOnce(); } catch (Throwable ignored) {
                            }
                            try { redumpFilled(); } catch (Throwable ignored) {
                            }
                            DumpLogger.writeSummary();
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }).start();
    }

    /** 遍历 ClassLoader 链，读各 dexElements 的 DexFile.mCookie，用 begin/size 读取。 */
    private static void dumpAllLoadedDexes() {
        ClassLoader start = sAppClassLoader;
        if (start == null) start = Thread.currentThread().getContextClassLoader();
        ClassLoader cl = start;
        int guard = 0;
        int totalCookie = 0;
        while (cl != null && guard++ < 16) {
            try {
                Object pathList = XposedHelpers.getObjectField(cl, "pathList");
                if (pathList != null) {
                    Object[] elements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
                    if (elements != null) {
                        for (Object el : elements) {
                            try {
                                Object dexFile = XposedHelpers.getObjectField(el, "dexFile");
                                if (dexFile == null) continue;
                                Object cookieObj = XposedHelpers.getObjectField(dexFile, "mCookie");
                                if (cookieObj instanceof long[]) {
                                    long[] cookies = (long[]) cookieObj;
                                    for (long c : cookies) {
                                        if (dumpCookie(c)) totalCookie++;
                                    }
                                } else if (cookieObj instanceof Number) {
                                    if (dumpCookie(((Number) cookieObj).longValue())) totalCookie++;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            cl = cl.getParent();
        }
        XposedBridge.log("MemoryDexDumper enumerate done, newDex=" + totalCookie);
    }

    /** 命中骨架(抽取壳)则登记 cookie + 类描述符源，供主动调用与补码回收。 */
    private static void recordSkeleton(long c, byte[] data) {
        try {
            DexInspector.Result ins = DexInspector.inspect(data);
            if (ins == null || !ins.isSkeleton) return;
            synchronized (sSkeletonCookies) {
                if (!sSkeletonCookies.add(c)) return;
                CRC32 crc = new CRC32();
                crc.update(data);
                sSkeletonBase.put(c, Long.toHexString(crc.getValue()));
                List<String> classes = DexInspector.listClassDescriptors(data);
                if (classes != null) sSkeletonClasses.put(c, classes);
            }
            XposedBridge.log("MemoryDexDumper 登记骨架 dex cookie=0x" + Long.toHexString(c)
                    + " classes=" + (sSkeletonClasses.get(c) == null ? "?" : sSkeletonClasses.get(c).size()));
        } catch (Throwable ignored) {
        }
    }

    /** FART 式主动调用：对最大骨架 dex 的类逐轮调方法，跨轮次续跑，触发壳回填 codeItem。 */
    private static void activeCallOnce() {
        long c;
        List<String> classes;
        synchronized (sSkeletonCookies) {
            if (sSkeletonCookies.isEmpty()) return;
            if (sActiveCookie == 0) {
                // 选方法体最多的骨架 dex 作为主目标
                long best = 0;
                int bestN = 0;
                for (Long k : sSkeletonCookies) {
                    List<String> l = sSkeletonClasses.get(k);
                    if (l != null && l.size() > bestN) {
                        bestN = l.size();
                        best = k;
                    }
                }
                sActiveCookie = best;
                sActiveIdx = 0;
            }
            c = sActiveCookie;
            classes = sSkeletonClasses.get(c);
        }
        if (c == 0 || classes == null || classes.isEmpty()) return;
        File done = new File(dumpDir, ".active_done");
        if (done.exists()) return;
        try {
            FileOutputStream fos = new FileOutputStream(done);
            try {
                fos.write(("pid=" + android.os.Process.myPid() + "\n").getBytes("UTF-8"));
            } finally {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
            long from = sActiveIdx;
            long next = MethodActiveCaller.run(sAppClassLoader, classes, 4000, from);
            sActiveIdx = next;
            if (next >= classes.size()) {
                DumpLogger.event("主动调用", "完成一轮全部类调用 " + classes.size());
                sActiveIdx = 0;
            }
            int rec = redumpFilled();
            if (rec > 0) {
                DumpLogger.event("主动调用", "调用后立即回收快照 " + rec + " 份");
            }
        } catch (Throwable t) {
            XposedBridge.log("MemoryDexDumper activeCallOnce err " + t);
        }
    }

    /** 补码回收：重读骨架 cookie 的 ArtDexFile，内容变化则另存 *-filled.dex。 */
    private static int redumpFilled() {
        Long[] keys;
        synchronized (sSkeletonCookies) {
            if (sSkeletonCookies.isEmpty()) return 0;
            keys = sSkeletonCookies.toArray(new Long[0]);
        }
        int n = 0;
        for (Long ck : keys) {
            long c = ck.longValue();
            try {
                byte[] data = UnsafeAccess.readArtDex(c);
                if (data == null || data.length < 0x70) continue;
                CRC32 crc = new CRC32();
                crc.update(data);
                String crcHex = Long.toHexString(crc.getValue());
                String base;
                synchronized (sSkeletonCookies) {
                    base = sSkeletonBase.get(c);
                }
                if (crcHex.equals(base)) continue;
                boolean stillSkel = true;
                String sum = "";
                try {
                    DexInspector.Result ins = DexInspector.inspect(data);
                    if (ins != null) {
                        stillSkel = ins.isSkeleton;
                        sum = ins.summary;
                    }
                } catch (Throwable ignored) {
                }
                File f = new File(dumpDir, "source-" + data.length + "-" + crcHex + "-filled.dex");
                FileUtils.writeByteToFile(data, f.getAbsolutePath());
                try {
                    f.setReadable(true, false);
                    f.setWritable(true, false);
                } catch (Throwable ignored) {
                }
                synchronized (sSkeletonCookies) {
                    sSkeletonBase.put(c, crcHex);
                }
                n++;
                XposedBridge.log("MemoryDexDumper 补码回收 " + f.getName()
                        + (stillSkel ? " 仍有空壳(继续等待) " + sum : " 方法体已回填完整 ✓ " + sum));
                DumpLogger.event("补码回收", f.getName() + (stillSkel ? " 待回填 " + sum : " 已回填完整 ✓"));
            } catch (Throwable t) {
                XposedBridge.log("MemoryDexDumper 补码回收 cookie=0x" + Long.toHexString(c) + " 异常 " + t);
            }
        }
        return n;
    }

    private static boolean dumpCookie(long c) {
        if (c == 0) return false;
        synchronized (sSeenCookies) {
            if (!sSeenCookies.add(c)) return false;
        }
        byte[] data = UnsafeAccess.readArtDex(c);
        if (data != null) {
            dumpDex(data);
            recordSkeleton(c, data);
            DumpLogger.enumHit(true);
            return true;
        }
        DumpLogger.enumHit(false);
        return false;
    }

    /** 第一层：内存 dex */
    private static void hookInMemoryDexClassLoader(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookConstructor("dalvik.system.InMemoryDexClassLoader", lpparam.classLoader,
                    ByteBuffer.class, ClassLoader.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            DumpLogger.hookHit("InMemoryDexClassLoader");
                            boolean ok = dumpByteBuffer((ByteBuffer) param.args[0]);
                            if (ok) DumpLogger.hookValid("InMemoryDexClassLoader");
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookConstructor("dalvik.system.InMemoryDexClassLoader", lpparam.classLoader,
                    ByteBuffer[].class, ClassLoader.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            DumpLogger.hookHit("InMemoryDexClassLoader");
                            ByteBuffer[] buffers = (ByteBuffer[]) param.args[0];
                            if (buffers == null) return;
                            boolean ok = false;
                            for (ByteBuffer buffer : buffers) {
                                if (dumpByteBuffer(buffer)) ok = true;
                            }
                            if (ok) DumpLogger.hookValid("InMemoryDexClassLoader");
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /** 第二层：磁盘 dex */
    private static void hookDexClassLoader(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookConstructor("dalvik.system.DexClassLoader", lpparam.classLoader,
                    String.class, String.class, String.class, ClassLoader.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            DumpLogger.hookHit("DexClassLoader");
                            int before = DumpLogger.dumpedCount();
                            dumpDexPath((String) param.args[0]);
                            if (DumpLogger.dumpedCount() > before) DumpLogger.hookValid("DexClassLoader");
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static void hookDexFile(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookConstructor("dalvik.system.DexFile", lpparam.classLoader,
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            DumpLogger.hookHit("DexFile");
                            int before = DumpLogger.dumpedCount();
                            dumpDexPath((String) param.args[0]);
                            if (DumpLogger.dumpedCount() > before) DumpLogger.hookValid("DexFile");
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookConstructor("dalvik.system.DexFile", lpparam.classLoader,
                    File.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            DumpLogger.hookHit("DexFile");
                            File f = (File) param.args[0];
                            if (f != null) {
                                int before = DumpLogger.dumpedCount();
                                dumpDexPath(f.getAbsolutePath());
                                if (DumpLogger.dumpedCount() > before) DumpLogger.hookValid("DexFile");
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod("dalvik.system.DexFile", lpparam.classLoader,
                    "loadDex", String.class, String.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            dumpDexPath((String) param.args[0]);
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /** 第三层：loadClass 兜底，从内存读出 ArtDexFile */
    private static void hookLoadClass(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Class clazz = (Class) param.getResult();
                            if (clazz == null || clazz.getClassLoader() == null) return;
                            // 跳过框架/系统/第三方库类，避免启动期对每个类做反射+整份dex内存读取导致卡死
                            String n = clazz.getName();
                            if (n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("android.")
                                    || n.startsWith("com.android.") || n.startsWith("dalvik.")
                                    || n.startsWith("sun.") || n.startsWith("org.apache.")
                                    || n.startsWith("androidx.") || n.startsWith("kotlin.")
                                    || n.startsWith("org.jetbrains.") || n.startsWith("com.google.")
                                    || n.startsWith("okhttp3.") || n.startsWith("okio.")) {
                                return;
                            }
                            dumpClassDex(clazz);
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private static boolean dumpByteBuffer(ByteBuffer buffer) {
        try {
            if (buffer == null) return false;
            ByteBuffer dup = buffer.duplicate();
            dup.position(0);
            int remaining = dup.remaining();
            if (remaining < 0x70 || remaining > 256L * 1024 * 1024) return false;
            if (!isDexBuffer(dup)) return false;
            byte[] data = new byte[remaining];
            dup.get(data);
            dumpDex(data);
            return true;
        } catch (Throwable t) {
            XposedBridge.log("MemoryDexDumper dumpByteBuffer failed: " + t);
            return false;
        }
    }

    private static boolean isDexBuffer(ByteBuffer dup) {
        int limit = Math.min(dup.remaining(), 8);
        byte[] b = new byte[limit];
        for (int i = 0; i < limit; i++) b[i] = dup.get(i);
        return startsWithMagic(b);
    }

    private static void dumpDexPath(String dexPath) {
        if (dexPath == null) return;
        String[] paths = dexPath.split(":");
        for (String path : paths) {
            if (path == null || path.isEmpty()) continue;
            try {
                File file = new File(path);
                if (!file.isFile() || !file.canRead()) continue;
                long length = file.length();
                if (length <= 0 || length > 256L * 1024 * 1024) continue;
                String pathKey = path + "@" + length;
                synchronized (sSeenPaths) {
                    if (sSeenPaths.contains(pathKey)) continue;
                    sSeenPaths.add(pathKey);
                }
                FileInputStream in = new FileInputStream(file);
                byte[] header = new byte[8];
                int hn = 0;
                try {
                    while (hn < header.length) {
                        int r = in.read(header, hn, header.length - hn);
                        if (r < 0) break;
                        hn += r;
                    }
                } finally {
                    in.close();
                }
                if (hn < 8 || !startsWithMagic(header)) continue;
                byte[] data = new byte[(int) length];
                FileInputStream in2 = new FileInputStream(file);
                try {
                    int offset = 0;
                    while (offset < data.length) {
                        int read = in2.read(data, offset, data.length - offset);
                        if (read < 0) break;
                        offset += read;
                    }
                } finally {
                    in2.close();
                }
                dumpDex(data);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void dumpClassDex(Class<?> clazz) {
        try {
            Object dexCache = XposedHelpers.getObjectField(clazz, "dexCache");
            if (dexCache == null) {
                probeLayout(clazz.getClass().getName());
                return;
            }
            long dexFilePtr = 0;
            Object dexFileObj = null;
            try {
                dexFilePtr = XposedHelpers.getLongField(dexCache, "dexFile");
            } catch (Throwable t) {
                try {
                    dexFileObj = XposedHelpers.getObjectField(dexCache, "dexFile");
                } catch (Throwable t2) {
                    probeLayout("DexCache");
                    return;
                }
            }
            if (dexFilePtr != 0) {
                synchronized (sSeenCookies) {
                    if (!sSeenCookies.add(dexFilePtr)) return;
                }
                byte[] data = UnsafeAccess.readDexFile(dexFilePtr);
                if (data != null) {
                    dumpDex(data);
                    return;
                }
                if (!sDiagnosedNative && clazz.getClassLoader() != null
                        && !clazz.getName().startsWith("java.") && !clazz.getName().startsWith("android.")) {
                    sDiagnosedNative = true;
                    XposedBridge.log("MemoryDexDumper long-branch fail: dexFilePtr=0x" + Long.toHexString(dexFilePtr)
                            + " dexCache=" + dexCache.getClass().getName() + " clazz=" + clazz.getName()
                            + " hex=" + UnsafeAccess.hexDump(dexFilePtr, 24));
                }
            } else if (dexFileObj != null) {
                if (dexFileObj instanceof Long) {
                    dexFilePtr = (Long) dexFileObj;
                    synchronized (sSeenCookies) {
                        if (!sSeenCookies.add(dexFilePtr)) return;
                    }
                    byte[] data = UnsafeAccess.readDexFile(dexFilePtr);
                    if (data != null) {
                        dumpDex(data);
                        return;
                    }
                } else if (!sDiagnosedNative && clazz.getClassLoader() != null
                        && !clazz.getName().startsWith("java.") && !clazz.getName().startsWith("android.")) {
                    sDiagnosedNative = true;
                    try {
                        Object cookie = XposedHelpers.getObjectField(dexFileObj, "mCookie");
                        XposedBridge.log("MemoryDexDumper obj-branch: dexCache=" + dexCache.getClass().getName()
                                + " dexFileObj=" + dexFileObj.getClass().getName() + " mCookie=" + cookie
                                + " clazz=" + clazz.getName());
                    } catch (Throwable t) {
                        XposedBridge.log("MemoryDexDumper obj-branch: dexFileObj=" + dexFileObj.getClass().getName()
                                + " no-mCookie(" + t + ") clazz=" + clazz.getName());
                    }
                }
                try {
                    Object cookie = XposedHelpers.getObjectField(dexFileObj, "mCookie");
                    if (cookie != null) {
                        long ptr = cookie instanceof Number ? ((Number) cookie).longValue() : 0;
                        if (ptr != 0) {
                            synchronized (sSeenCookies) {
                                if (!sSeenCookies.add(ptr)) return;
                            }
                            byte[] data = UnsafeAccess.readArtDex(ptr);
                            if (data != null) dumpDex(data);
                        }
                    }
                } catch (Throwable ignored) {
                }
            } else {
                probeLayout("DexCache");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void probeLayout(String what) {
        if (!sProbedLayout.add(what)) return;
        try {
            StringBuilder sb = new StringBuilder("MemoryDexDumper layout probe: " + what);
            Class<?> cls;
            if (what.equals("DexCache")) {
                cls = Class.forName("dalvik.system.DexCache");
            } else if (what.equals("DexFile")) {
                cls = Class.forName("dalvik.system.DexFile");
            } else {
                cls = Class.class;
            }
            Field[] fields = cls.getDeclaredFields();
            for (Field f : fields) {
                String n = f.getName();
                if (n.contains("dex") || n.contains("Dex") || n.contains("cookie") || n.contains("Cookie") || n.contains("cache") || n.contains("Cache")) {
                    sb.append("\n  ").append(f.getType().getName()).append(" ").append(n);
                }
            }
            XposedBridge.log(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private static void dumpDex(byte[] data) {
        if (data == null || data.length < 0x70) return;
        if (data[0] != 0x64 || data[1] != 0x65 || data[2] != 0x78 || data[3] != 0x0A) return;
        CRC32 crc = new CRC32();
        crc.update(data);
        String key = data.length + "-" + crc.getValue();
        synchronized (sDumped) {
            if (sDumped.contains(key)) return;
            sDumped.add(key);
        }
        File file = new File(dumpDir, "source-" + data.length + "-" + Long.toHexString(crc.getValue()) + ".dex");
        FileUtils.writeByteToFile(data, file.getAbsolutePath());
        try {
            file.setReadable(true, false);
            file.setWritable(true, false);
        } catch (Throwable ignored) {
        }
        // 方法体体检：区分「完整 dex」与「方法抽取壳骨架」
        try {
            DexInspector.Result ins = DexInspector.inspect(data);
            if (ins != null) {
                XposedBridge.log("MemoryDexDumper dump: " + file.getName() + " size=" + data.length
                        + " 体检=(" + ins.summary + ")");
                DumpLogger.registerDump(ins.isSkeleton, file.getName() + " " + ins.summary);
            } else {
                XposedBridge.log("MemoryDexDumper dump: " + file.getName() + " size=" + data.length);
                DumpLogger.registerDump(false, file.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log("MemoryDexDumper dump: " + file.getName() + " size=" + data.length
                    + " 体检异常=" + t);
            DumpLogger.registerDump(false, file.getName());
        }
    }

    /** 扫描 dump 目录已脱出的 dex，输出真实 Application 到 real_app.txt（与沙箱分析一致，供 UI/导出使用）。 */
    private static void scanAndWriteRealApp() {
        try {
            File dir = new File(dumpDir);
            java.util.ArrayList<File> dexes = new java.util.ArrayList<>();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".dex")) dexes.add(f);
                }
            }
            if (dexes.isEmpty()) return;
            String real = null;
            try {
                java.util.List<String[]> apps = cn.mhook.npatch.DexScanner.findApplicationHierarchy(dexes);
                real = cn.mhook.npatch.DexScanner.pickRealApplication(apps, null);
            } catch (Throwable ignored) {
            }
            String content = "真实 Application: " + (real != null ? real : "(未知)") + "\n";
            FileUtils.writeByteToFile(content.getBytes("UTF-8"), new File(dir, "real_app.txt").getAbsolutePath());
            DumpLogger.event("真实Application", real != null ? real : "(未知)");
        } catch (Throwable ignored) {
        }
    }

    /** 内存读取封装：优先 sun.misc.Unsafe，失败回退 /proc/self/mem，避免隐藏 API 限制 */
    private static final class UnsafeAccess {
        private static Object unsafe;
        private static Method getLong;
        private static Method getInt;
        private static Method copyMemory;
        private static long byteArrayBaseOffset;

        static {
            try {
                Class<?> cls = Class.forName("sun.misc.Unsafe");
                Field field = cls.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                unsafe = field.get(null);
                getLong = cls.getMethod("getLong", long.class);
                getInt = cls.getMethod("getInt", long.class);
                try {
                    copyMemory = cls.getMethod("copyMemory", Object.class, long.class, Object.class, long.class, long.class);
                } catch (Throwable ignored) {
                    copyMemory = null;
                }
                try {
                    byteArrayBaseOffset = cls.getField("ARRAY_BYTE_BASE_OFFSET").getInt(null);
                } catch (Throwable ignored) {
                }
                XposedBridge.log("MemoryDexDumper MemAccess Unsafe OK getLong=" + (getLong != null)
                        + " copyMemory=" + (copyMemory != null));
            } catch (Throwable t) {
                unsafe = null;
                XposedBridge.log("MemoryDexDumper MemAccess Unsafe failed: " + t);
            }
        }

        private static boolean sMemDiag;

        /** 可读内存区间缓存（来自 /proc/self/maps），用于越界校验，避免 Unsafe 读野指针 SIGSEGV。 */
        private static volatile long[][] sReadable;
        private static volatile long sMapsAt;

        private static void loadMaps() {
            java.util.List<long[]> list = new java.util.ArrayList<>();
            java.io.BufferedReader r = null;
            try {
                r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"), 32 * 1024);
                String line;
                while ((line = r.readLine()) != null) {
                    int sp = line.indexOf(' ');
                    if (sp <= 0) continue;
                    String range = line.substring(0, sp);
                    int dash = range.indexOf('-');
                    if (dash <= 0) continue;
                    String perm = line.length() > sp + 1 ? line.substring(sp + 1, Math.min(sp + 5, line.length())) : "";
                    if (perm.isEmpty() || perm.charAt(0) != 'r') continue;
                    try {
                        long a = Long.parseLong(range.substring(0, dash), 16);
                        long b = Long.parseLong(range.substring(dash + 1), 16);
                        list.add(new long[]{a, b});
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    if (r != null) r.close();
                } catch (Throwable ignored) {
                }
            }
            sReadable = list.toArray(new long[0][]);
            sMapsAt = System.currentTimeMillis();
        }

        private static boolean isReadable(long addr, int len) {
            if (addr <= 0 || len < 0) return false;
            long[][] m = sReadable;
            if (m == null || System.currentTimeMillis() - sMapsAt > 5000) {
                loadMaps();
                m = sReadable;
            }
            if (m == null) return false;
            long end = addr + len;
            for (long[] rg : m) {
                if (addr >= rg[0] && end <= rg[1]) return true;
            }
            return false;
        }

        /** 去 TBI 高位标签：Android 11+ arm64 堆指针高字节为标签(如 0xb4)，读内存前须抹掉。 */
        private static long fix(long a) {
            return a & 0x00FFFFFFFFFFFFL;
        }

        /** 包含 addr 的可读映射区结束地址；无则 0。用于跨映射安全按块读。 */
        private static long mapEnd(long addr) {
            long[][] m = sReadable;
            if (m == null || System.currentTimeMillis() - sMapsAt > 5000) {
                loadMaps();
                m = sReadable;
            }
            if (m == null) return 0;
            for (long[] rg : m) {
                if (addr >= rg[0] && addr < rg[1]) return rg[1];
            }
            return 0;
        }
        private static byte[] readMem(long addr, int len) {
            if (addr <= 0 || len <= 0) return null;
            addr = fix(addr);
            // 仅当落在可读映射区时才用 Unsafe（防 SIGSEGV）；否则仍走安全的 /proc/self/mem 兜底
            if (isReadable(addr, len) && unsafe != null && getLong != null) {
                try {
                    byte[] buf = new byte[len];
                    long off = addr;
                    int i = 0;
                    for (; i + 8 <= len; i += 8) {
                        long v = (Long) getLong.invoke(unsafe, off);
                        putLongLE(buf, i, v);
                        off += 8;
                    }
                    if (i < len) {
                        long v = (Long) getLong.invoke(unsafe, off);
                        for (int k = 0; k < len - i; k++) {
                            buf[i + k] = (byte) (v >>> (8 * k));
                        }
                    }
                    return buf;
                } catch (Throwable t) {
                    unsafe = null;
                    if (!sMemDiag) {
                        sMemDiag = true;
                        XposedBridge.log("MemoryDexDumper getLong invoke failed: " + t);
                    }
                }
            }
            try {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile("/proc/self/mem", "r");
                try {
                    byte[] buf = new byte[len];
                    int done = 0;
                    while (done < len) {
                        long a = addr + done;
                        long e = mapEnd(a);
                        if (e <= a) return null;
                        int chunk = (int) Math.min((long) (len - done), e - a);
                        raf.seek(a);
                        raf.readFully(buf, done, chunk);
                        done += chunk;
                    }
                    return buf;
                } finally {
                    raf.close();
                }
            } catch (Throwable t) {
                if (!sMemDiag) {
                    sMemDiag = true;
                    XposedBridge.log("MemoryDexDumper /proc/self/mem failed: " + t);
                }
                return null;
            }
        }

        private static void putLongLE(byte[] buf, int off, long v) {
            for (int i = 0; i < 8; i++) {
                buf[off + i] = (byte) (v >>> (8 * i));
            }
        }

        private static long readPtr(long addr) {
            byte[] b = readMem(addr, 8);
            if (b == null) return 0;
            long v = 0;
            for (int i = 7; i >= 0; i--) {
                v = (v << 8) | (b[i] & 0xFFL);
            }
            return fix(v);
        }

        private static int readInt(long addr) {
            byte[] b = readMem(addr, 4);
            if (b == null) return 0;
            return (b[3] & 0xFF) << 24 | (b[2] & 0xFF) << 16 | (b[1] & 0xFF) << 8 | (b[0] & 0xFF);
        }

        static String hexDump(long addr, int words) {
            StringBuilder sb = new StringBuilder();
            try {
                for (int i = 0; i < words; i++) {
                    sb.append(String.format("%08x ", readPtr(addr + i * 8)));
                }
            } catch (Throwable t) {
                sb.append("err:").append(t);
            }
            return sb.toString();
        }

        static byte[] readDexFile(long dexFilePtr) {
            try {
                for (int off = 0; off < 0x200; off += 8) {
                    long p = readPtr(dexFilePtr + off);
                    if (p == 0 || (p & 3) != 0) continue;
                    byte[] hit = tryReadDex(p);
                    if (hit != null) return hit;
                    long p2 = readPtr(p);
                    if (p2 != 0 && (p2 & 3) == 0) {
                        hit = tryReadDex(p2);
                        if (hit != null) return hit;
                    }
                }
            } catch (Throwable t) {
            }
            return null;
        }

        private static byte[] tryReadDex(long p) {
            try {
                if (readInt(p) != 0x0A786564) return null;
                long fileSize = readInt(p + 0x20) & 0xFFFFFFFFL;
                if (fileSize < 0x70 || fileSize > 256L * 1024 * 1024) {
                    long e = mapEnd(p);
                    fileSize = e > p ? Math.min(e - p, 256L * 1024 * 1024) : 0;
                }
                if (fileSize < 0x70) return null;
                return readMem(p, (int) fileSize);
            } catch (Throwable t) {
                return null;
            }
        }

        static byte[] readArtDex(long cookie) {
            try {
                // cookie 指向 ArtDexFile 对象；begin_/size_ 偏移随 ART 版本变化，
                // 扫对象前 0x80 字节内的指针，命中 dex magic 即按 file_size 读取。
                for (int off = 0; off < 0x80; off += 8) {
                    long p = readPtr(cookie + off);
                    if (p == 0 || (p & 3) != 0) continue;
                    if (readInt(p) != 0x0A786564) continue;
                    long fileSize = readInt(p + 0x20) & 0xFFFFFFFFL;
                    if (fileSize < 0x70 || fileSize > 256L * 1024 * 1024) {
                    long e = mapEnd(p);
                    fileSize = e > p ? Math.min(e - p, 256L * 1024 * 1024) : 0;
                }
                if (fileSize < 0x70) continue;
                    return readMem(p, (int) fileSize);
                }
            } catch (Throwable t) {
            }
            return null;
        }
    }
}
