package cn.mhook.mhook.xposed.dump;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashSet;
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
 * 纯 Java 内存脱壳
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
        if (b == null || b.length < 8) return false;
        for (int i = 0; i < 8; i++) {
            if (b[i] != DEX_MAGIC[i]) return false;
        }
        return true;
    }

    private static final Set<String> sDumped = new HashSet<>();
    private static final Set<String> sSeenPaths = new HashSet<>();
    private static final Set<Long> sSeenCookies = new HashSet<>();
    private static final Set<String> sProbedLayout = new HashSet<>();
    private static boolean sDiagnosedNative;

    public static void init(final XC_LoadPackage.LoadPackageParam lpparam) {
        probeLayout("DexCache");
        probeLayout("DexFile");
        hookInMemoryDexClassLoader(lpparam);
        hookDexClassLoader(lpparam);
        hookDexFile(lpparam);
        // 不 hook loadClass：会频繁触发原生内存读取，易被加固（如阿里 Ashield）检测崩溃。
        // 改为延迟后台一次性枚举已加载 dex，用 cookie 的 begin/size 精确定位读取（BlackDex 思路）。
        sAppClassLoader = lpparam.classLoader;
        scheduleDumpAll();
    }

    private static ClassLoader sAppClassLoader;

    /**
     * 多批次 + 手动触发：后台线程持续运行，
     * 在 3/8/15/25/40/60 秒各枚举一次（抓延迟解密的壳），
     * 并轮询 dump_now 标志文件实现 UI 的「立即脱壳」。
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
                            continue;
                        }
                        long el = System.currentTimeMillis() - start;
                        if (round < at.length && el >= at[round]) {
                            round++;
                            try { dumpAllLoadedDexes(); } catch (Throwable ignored) {
                            }
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

    private static boolean dumpCookie(long c) {
        if (c == 0) return false;
        synchronized (sSeenCookies) {
            if (!sSeenCookies.add(c)) return false;
        }
        byte[] data = UnsafeAccess.readArtDex(c);
        if (data != null) {
            dumpDex(data);
            return true;
        }
        return false;
    }

    /** 第一层：内存 dex */
    private static void hookInMemoryDexClassLoader(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookConstructor("dalvik.system.InMemoryDexClassLoader", lpparam.classLoader,
                    ByteBuffer.class, ClassLoader.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            dumpByteBuffer((ByteBuffer) param.args[0]);
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookConstructor("dalvik.system.InMemoryDexClassLoader", lpparam.classLoader,
                    ByteBuffer[].class, ClassLoader.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            ByteBuffer[] buffers = (ByteBuffer[]) param.args[0];
                            if (buffers == null) return;
                            for (ByteBuffer buffer : buffers) {
                                dumpByteBuffer(buffer);
                            }
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
                            dumpDexPath((String) param.args[0]);
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
                            dumpDexPath((String) param.args[0]);
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookConstructor("dalvik.system.DexFile", lpparam.classLoader,
                    File.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            File f = (File) param.args[0];
                            if (f != null) dumpDexPath(f.getAbsolutePath());
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

    private static void dumpByteBuffer(ByteBuffer buffer) {
        try {
            if (buffer == null) return;
            ByteBuffer dup = buffer.duplicate();
            dup.position(0);
            int remaining = dup.remaining();
            if (remaining < 0x70 || remaining > 256L * 1024 * 1024) return;
            if (!isDexBuffer(dup)) return;
            byte[] data = new byte[remaining];
            dup.get(data);
            dumpDex(data);
        } catch (Throwable t) {
            XposedBridge.log("MemoryDexDumper dumpByteBuffer failed: " + t);
        }
    }

    private static boolean isDexBuffer(ByteBuffer dup) {
        int limit = Math.min(dup.remaining(), 8);
        for (int i = 0; i < limit; i++) {
            if (dup.get(i) != DEX_MAGIC[i]) return false;
        }
        return true;
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
        XposedBridge.log("MemoryDexDumper dump: " + file.getName() + " size=" + data.length);
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

        private static byte[] readMem(long addr, int len) {
            if (unsafe != null && getLong != null) {
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
                    raf.seek(addr);
                    byte[] buf = new byte[len];
                    raf.readFully(buf);
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
            return v;
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
                if (fileSize < 0x70 || fileSize > 256L * 1024 * 1024) return null;
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
                    if (fileSize < 0x70 || fileSize > 256L * 1024 * 1024) continue;
                    return readMem(p, (int) fileSize);
                }
            } catch (Throwable t) {
            }
            return null;
        }
    }
}
