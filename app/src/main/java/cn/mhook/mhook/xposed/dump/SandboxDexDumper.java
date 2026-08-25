package cn.mhook.mhook.xposed.dump;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Xposed-free memory dex dumper for the embedded BlackBox sandbox.
 * Runs inside the virtual app process (triggered via AppLifecycleCallback),
 * enumerates the loaded ClassLoader chain, reads each DexFile.mCookie and
 * scans the ArtDexFile object for dex magic (same approach as MemoryDexDumper
 * but without any de.robv.android.xposed dependency).
 */
public class SandboxDexDumper {

    private static final String TAG = "SandboxDexDumper";
    private static final byte[] DEX_MAGIC = {0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00};

    private static final Set<String> sDumped = new HashSet<>();
    private static final Set<Long> sSeenCookies = new HashSet<>();
    // 骨架 dex 的 cookie：已触发主动加载，等待壳回填方法体后由定时轮次重读回收
    private static final Set<Long> sSkeletonCookies = new HashSet<>();
    // cookie -> 上次落盘内容的 crc hex（用于对比是否发生变化/避免重复保存）
    private static final java.util.HashMap<Long, String> sSkeletonSaved = new java.util.HashMap<>();
    // cookie -> 连续读取失败次数：读取失败绝不能当"已见过"处理，否则之后永远为 0
    private static final java.util.HashMap<Long, Integer> sFailCounts = new java.util.HashMap<>();

    private static volatile File sOutDir;
    private static volatile boolean sStarted;
    private static volatile ClassLoader sStartCl;
    private static volatile android.app.Application sApp;

    /** 触发延迟 dump 轮次。可安全多次调用。 */
    public static void start(File outDir, ClassLoader appClassLoader) {
        start(outDir, appClassLoader, null);
    }

    public static void start(File outDir, ClassLoader appClassLoader, android.app.Application application) {
        sApp = application;
        final ClassLoader startCl = appClassLoader != null ? appClassLoader
                : Thread.currentThread().getContextClassLoader();
        sOutDir = outDir;
        // 每次调用都更新最新 classloader：壳可能在 onCreate 期间替换/重建应用 classloader，
        // 轮询线程每轮读取最新引用，才能抓到后续加载的 dex。
        sStartCl = startCl;
        boolean first = !sStarted;
        sStarted = true;
        try {
            if (!outDir.exists()) outDir.mkdirs();
        } catch (Throwable ignored) {
        }
        // 记录脱壳日志（虚拟进程内写宿主 outDir 下的 dump_log.txt，供 UI/zip 汇总）
        try {
            java.util.Locale loc = java.util.Locale.US;
            writeLog(outDir, String.format(loc, "[+%5dms] 入口: 沙箱脱壳装载 pkg/out=%s cl=%s%n",
                    System.currentTimeMillis() & 0xFFFFFFFFL, outDir.getAbsolutePath(), startCl));
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "SandboxDexDumper.start out=" + outDir + " cl=" + startCl);
        // 立即同步 dump 一次：部分加固应用进程在 beforeApplicationOnCreate 后极短时间（<1s）内
        // 就会因壳检测/加载失败崩溃，等定时轮次（1s 起）来不及，需趁进程存活立刻抓取已加载的 dex。
        try {
            EnumStats st = dumpAllLoadedDexes(startCl);
            scanMapsForDex(st);
            Log.i(TAG, "immediate dump " + st.summary());
            hookHitLog(outDir, "立即枚举", st.summary());
        } catch (Throwable ignored) {
        }
        if (!first) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 定时轮次 + 尾部周期轮（65s~115s 每 10s 一轮）：覆盖更晚的延迟解密场景；
                // 全部跑完自动退出线程，不再无限空转。
                java.util.ArrayList<Long> sch = new java.util.ArrayList<>();
                for (long v : new long[]{1000, 3000, 6000, 10000, 15000, 25000, 40000, 55000}) sch.add(v);
                for (long v = 65000; v <= 120000; v += 10000) sch.add(v);
                long start = System.currentTimeMillis();
                int round = 0;
                while (round < sch.size()) {
                    try {
                        long el = System.currentTimeMillis() - start;
                        if (el >= sch.get(round)) {
                            round++;
                            try {
                                // 每轮都取最新引用：壳可能在 onCreate 之后才替换/重建应用 classloader
                                ClassLoader cl = sStartCl;
                                android.app.Application app = sApp;
                                if (app != null) {
                                    try {
                                        ClassLoader acl = app.getClassLoader();
                                        if (acl != null) cl = acl;
                                    } catch (Throwable ignored) {
                                    }
                                }
                                File dir = sOutDir;
                                EnumStats st = dumpAllLoadedDexes(cl);
                                scanMapsForDex(st);
                                hookHitLog(dir, "定时枚举第" + round + "轮", st.summary());
                                Log.i(TAG, "dump round " + round + " " + st.summary());
                            } catch (Throwable ignored) {
                            }
                            // 补码回收：重读骨架 dex，壳已回填方法体的另存为 *-filled.dex
                            try {
                                int r = redumpFilled();
                                if (r > 0) Log.i(TAG, "redump round " + round + " filled=" + r);
                            } catch (Throwable ignored) {
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(500);
                    } catch (Throwable ignored) {
                    }
                }
                Log.i(TAG, "dump loop finished, exit");
            }
        }).start();
    }

    /** 单轮枚举统计：区分「没有新 cookie」「有 cookie 但读内存失败」「实际落盘」。 */
    private static final class EnumStats {
        int newCookies;
        int readFail;
        int saved;
        int loaders;
        int mapsScanned;
        int mapsSaved;

        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("新cookie=").append(newCookies)
                    .append(" 读失败=").append(readFail)
                    .append(" 落盘=").append(saved)
                    .append(" 遍历CL=").append(loaders);
            if (mapsScanned > 0) {
                sb.append(" maps扫描=").append(mapsScanned).append(" maps落盘=").append(mapsSaved);
            }
            return sb.toString();
        }
    }

    /**
     * 多根枚举：除传入根外，再收集 Application 当前 classloader 与所有线程的
     * contextClassLoader 作为额外根——加固壳常用独立 InMemoryDexClassLoader /
     * 新建 PathClassLoader 动态加载 dex，这些 loader 不在主链 parent 上，
     * 只走单一根会永远枚举不到（表现为定时轮次一直为 0）。
     */
    private static EnumStats dumpAllLoadedDexes(ClassLoader primary) {
        EnumStats st = new EnumStats();
        java.util.HashSet<ClassLoader> visited = new java.util.HashSet<>();
        for (ClassLoader root : collectRoots(primary)) {
            ClassLoader cl = root;
            int guard = 0;
            while (cl != null && guard++ < 16 && visited.add(cl)) {
                st.loaders++;
                enumerateLoader(cl, st);
                cl = cl.getParent();
            }
        }
        return st;
    }

    private static java.util.ArrayList<ClassLoader> collectRoots(ClassLoader primary) {
        java.util.ArrayList<ClassLoader> roots = new java.util.ArrayList<>();
        if (primary != null) roots.add(primary);
        try {
            android.app.Application a = sApp;
            if (a != null) {
                ClassLoader c = a.getClassLoader();
                if (c != null && !roots.contains(c)) roots.add(c);
            }
        } catch (Throwable ignored) {
        }
        try {
            ThreadGroup g = Thread.currentThread().getThreadGroup();
            while (g != null && g.getParent() != null) g = g.getParent();
            if (g != null) {
                int n = g.activeCount();
                if (n > 0) {
                    Thread[] ts = new Thread[n + 8];
                    int m = g.enumerate(ts);
                    for (int i = 0; i < m; i++) {
                        try {
                            ClassLoader c = ts[i].getContextClassLoader();
                            if (c != null && !roots.contains(c)) roots.add(c);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return roots;
    }

    private static void enumerateLoader(ClassLoader cl, EnumStats st) {
        try {
            Object pathList = getField(cl, "pathList");
            if (pathList != null) {
                Object[] elements = (Object[]) getField(pathList, "dexElements");
                if (elements != null) {
                    for (Object el : elements) {
                        try {
                            Object dexFile = getField(el, "dexFile");
                            if (dexFile == null) continue;
                            Object cookieObj = getField(dexFile, "mCookie");
                            if (cookieObj instanceof long[]) {
                                long[] cookies = (long[]) cookieObj;
                                for (long c : cookies) {
                                    dumpCookie(c, st);
                                }
                            } else if (cookieObj instanceof Number) {
                                dumpCookie(((Number) cookieObj).longValue(), st);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void dumpCookie(long c, EnumStats st) {
        if (c == 0) return;
        synchronized (sSeenCookies) {
            if (sSeenCookies.contains(c)) return;
        }
        byte[] data = UnsafeAccess.readArtDex(c);
        if (data == null) {
            // 读取失败不能永久拉黑：壳刚启动时映射可能未就绪（这正是"定时枚举
            // 一直为 0"的主因）。登记失败次数，下轮重试；连续 10 次仍失败才放弃。
            boolean giveUp;
            synchronized (sFailCounts) {
                Integer old = sFailCounts.get(c);
                int n = (old == null ? 0 : old) + 1;
                sFailCounts.put(c, n);
                giveUp = n >= 10;
            }
            if (giveUp) {
                synchronized (sSeenCookies) {
                    sSeenCookies.add(c);
                }
                synchronized (sFailCounts) {
                    sFailCounts.remove(c);
                }
            }
            st.readFail++;
            return;
        }
        synchronized (sSeenCookies) {
            sSeenCookies.add(c);
        }
        synchronized (sFailCounts) {
            sFailCounts.remove(c);
        }
        st.newCookies++;
        if (saveDex(data, c)) st.saved++;
    }

    /** 落盘一个 dex（按 len+crc 去重）；骨架(抽取壳)时触发主动加载并登记补码回收。 */
    private static boolean saveDex(byte[] data, long cookie) {
        if (data == null || data.length < 0x70) return false;
        if (data[0] != 0x64 || data[1] != 0x65 || data[2] != 0x78 || data[3] != 0x0A) return false;
        CRC32 crc = new CRC32();
        crc.update(data);
        String key = data.length + "-" + crc.getValue();
        synchronized (sDumped) {
            if (sDumped.contains(key)) return false;
            sDumped.add(key);
        }
        File out = sOutDir;
        if (out == null) return false;
        try {
            if (!out.exists()) out.mkdirs();
            File file = new File(out, "source-" + data.length + "-" + Long.toHexString(crc.getValue()) + ".dex");
            FileOutputStream fos = new FileOutputStream(file);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
            try {
                file.setReadable(true, false);
                file.setWritable(true, false);
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "dump: " + file.getName() + " size=" + data.length);
            // 方法体体检 + 写脱壳日志
            String detail = file.getName() + " size=" + data.length;
            boolean skeleton = false;
            try {
                DexInspector.Result ins = DexInspector.inspect(data);
                if (ins != null) {
                    detail += " " + ins.summary;
                    Log.i(TAG, "inspect: " + ins.summary);
                    skeleton = ins.isSkeleton;
                }
            } catch (Throwable ignored) {
            }
            hookHitLog(out, "产出dex", detail);
            // 骨架(抽取壳) → 主动加载类触发补码，并登记 cookie 供后续轮次回收
            if (skeleton) {
                try {
                    ActiveClassLoader.dumpAndTrigger(data, sStartCl, out, file.getName(), sApp);
                    if (cookie != 0) {
                        synchronized (sSkeletonSaved) {
                            if (!sSkeletonSaved.containsKey(cookie)) {
                                sSkeletonSaved.put(cookie, Long.toHexString(crc.getValue()));
                            }
                        }
                        synchronized (sSkeletonCookies) {
                            sSkeletonCookies.add(cookie);
                        }
                        hookHitLog(out, "主动加载", file.getName() + " 已触发补码(定时轮次将尝试补码回收)");
                    } else {
                        hookHitLog(out, "主动加载", file.getName() + " 已触发补码(cookie无效,无法回收)");
                    }
                } catch (Throwable t) {
                    hookHitLog(out, "主动加载", file.getName() + " 触发异常: " + t);
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 兜底：扫描 /proc/self/maps 各可读映射区起始处的 dex 魔数。
     * 覆盖绕过 classloader 链的场景（InMemoryDexClassLoader 匿名映射、独立 loader 等）。
     * 只探测每个映射区首 8 字节，开销极低；统一走 /proc/self/mem 读，
     * 避免 Unsafe 读到未映射页直接 SIGSEGV 崩掉虚拟进程。
     */
    private static void scanMapsForDex(EnumStats st) {
        java.io.RandomAccessFile raf;
        try {
            raf = new java.io.RandomAccessFile("/proc/self/mem", "r");
        } catch (Throwable t) {
            return;
        }
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/maps"), 32 * 1024);
            String line;
            while ((line = br.readLine()) != null) {
                try {
                    int sp = line.indexOf(' ');
                    if (sp <= 0) continue;
                    int dash = line.indexOf('-');
                    if (dash <= 0 || dash >= sp) continue;
                    long s = Long.parseLong(line.substring(0, dash), 16);
                    long e = Long.parseLong(line.substring(dash + 1, sp), 16);
                    long len = e - s;
                    if (len < 0x70 || len > (128L << 20)) continue;
                    String rest = line.substring(sp + 1);
                    if (!rest.startsWith("r")) continue;
                    // 排除系统/启动映像：boot dex、系统库映射数量庞大且无脱壳价值
                    if (rest.contains("/system") || rest.contains("/apex") || rest.contains("/vendor")
                            || rest.contains("/product") || rest.contains("/system_ext")
                            || rest.contains("/data/dalvik-cache") || rest.startsWith("[v")) {
                        continue;
                    }
                    byte[] head = memRead(raf, s, 8);
                    if (head == null || head[0] != 0x64 || head[1] != 0x65
                            || head[2] != 0x78 || head[3] != 0x0A) {
                        continue;
                    }
                    byte[] szb = memRead(raf, s + 0x20, 4);
                    if (szb == null) continue;
                    long fs = ((szb[3] & 0xFFL) << 24) | ((szb[2] & 0xFFL) << 16)
                            | ((szb[1] & 0xFFL) << 8) | (szb[0] & 0xFFL);
                    if (fs < 0x70 || fs > len || fs > (128L << 20)) continue;
                    byte[] data = memRead(raf, s, (int) fs);
                    if (data == null) continue;
                    st.mapsScanned++;
                    if (saveDex(data, 0)) {
                        st.mapsSaved++;
                        st.saved++;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                raf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] memRead(java.io.RandomAccessFile raf, long addr, int len) {
        try {
            raf.seek(addr);
            byte[] b = new byte[len];
            raf.readFully(b);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 补码回收：对已触发主动加载的骨架 dex 重读内存。
     * 壳在类加载时把真实方法体写回 dex 映射区的话，重读即可拿到完整 codeItem：
     * - 已回填完整 → 另存 source-<len>-<crc>-filled.dex，停止跟踪
     * - 内容有变化但仍疑似空壳 → 保存中间快照(更新基线)，继续等待
     * - 无变化 → 跳过
     * @return 本次回收/快照的文件数
     */
    private static int redumpFilled() {
        Long[] keys;
        synchronized (sSkeletonCookies) {
            if (sSkeletonCookies.isEmpty()) return 0;
            keys = sSkeletonCookies.toArray(new Long[0]);
        }
        File out = sOutDir;
        if (out == null) return 0;
        int n = 0;
        for (Long ck : keys) {
            long c = ck.longValue();
            try {
                byte[] data = UnsafeAccess.readArtDex(c);
                if (data == null || data.length < 0x70) continue;
                CRC32 crc = new CRC32();
                crc.update(data);
                String crcHex = Long.toHexString(crc.getValue());
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
                String savedCrc;
                synchronized (sSkeletonSaved) {
                    savedCrc = sSkeletonSaved.get(ck);
                }
                if (crcHex.equals(savedCrc)) continue; // 与上次落盘一致，无变化
                File f = new File(out, "source-" + data.length + "-" + crcHex + "-filled.dex");
                FileOutputStream fos = new FileOutputStream(f);
                try {
                    fos.write(data);
                } finally {
                    fos.close();
                }
                try {
                    f.setReadable(true, false);
                    f.setWritable(true, false);
                } catch (Throwable ignored) {
                }
                synchronized (sSkeletonSaved) {
                    sSkeletonSaved.put(ck, crcHex); // 更新基线，避免重复保存
                }
                n++;
                if (stillSkel) {
                    hookHitLog(out, "补码回收", f.getName() + " 内容有变化但仍疑似空壳(继续等待) " + sum);
                } else {
                    hookHitLog(out, "补码回收", f.getName() + " 方法体已回填完整 ✓ " + sum);
                    synchronized (sSkeletonCookies) {
                        sSkeletonCookies.remove(ck); // 完成，停止跟踪
                    }
                }
            } catch (Throwable t) {
                hookHitLog(out, "补码回收", "cookie@" + Long.toHexString(c) + " 重读异常: " + t);
            }
        }
        return n;
    }

    private static Object getField(Object o, String name) {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable t) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** 追加一条事件到 outDir/dump_log.txt（虚拟进程内写宿主 outDir，供 UI 与 zip 汇总）。 */
    static void hookHitLog(File outDir, String tag, Object msg) {
        if (outDir == null) return;
        try {
            writeLog(outDir, String.format(java.util.Locale.US, "[+%5dms] %s: %s%n",
                    System.currentTimeMillis() & 0xFFFFFFFFL, tag, String.valueOf(msg)));
        } catch (Throwable ignored) {
        }
    }

    private static void writeLog(File outDir, String line) {
        try {
            if (!outDir.exists()) outDir.mkdirs();
            File f = new File(outDir, "dump_log.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
            try {
                fos.write(line.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (Throwable t) {
            Log.i(TAG, "writeLog fail: " + t);
        }
    }

    /** Memory access wrapper: sun.misc.Unsafe, falling back to /proc/self/mem. */
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
            } catch (Throwable t) {
                unsafe = null;
            }
        }

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

        /** cookie points at an ArtDexFile; scan the first 0x80 bytes for dex magic. */
        static byte[] readArtDex(long cookie) {
            try {
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
