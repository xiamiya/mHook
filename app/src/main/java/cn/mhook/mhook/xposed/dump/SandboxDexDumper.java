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

    private static volatile File sOutDir;
    private static volatile boolean sStarted;
    private static volatile ClassLoader sStartCl;

    /** Trigger the delayed dump rounds. Safe to call multiple times. */
    public static void start(File outDir, ClassLoader appClassLoader) {
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
        Log.i(TAG, "SandboxDexDumper.start out=" + outDir + " cl=" + startCl);
        // 立即同步 dump 一次：部分加固应用进程在 beforeApplicationOnCreate 后极短时间（<1s）内
        // 就会因壳检测/加载失败崩溃，等定时轮次（1s 起）来不及，需趁进程存活立刻抓取已加载的 dex。
        try {
            int n = dumpAllLoadedDexes(startCl);
            Log.i(TAG, "immediate dump newDex=" + n);
        } catch (Throwable ignored) {
        }
        if (!first) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                long[] at = {1000, 3000, 6000, 10000, 15000, 25000, 40000, 55000};
                long start = System.currentTimeMillis();
                int round = 0;
                while (true) {
                    try {
                        long el = System.currentTimeMillis() - start;
                        if (round < at.length && el >= at[round]) {
                            round++;
                            try {
                                ClassLoader cl = sStartCl;
                                int n = dumpAllLoadedDexes(cl);
                                Log.i(TAG, "dump round " + round + " newDex=" + n);
                            } catch (Throwable ignored) {
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

    private static int dumpAllLoadedDexes(ClassLoader start) {
        ClassLoader cl = start;
        int guard = 0;
        int totalCookie = 0;
        while (cl != null && guard++ < 16) {
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
        return totalCookie;
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
        File out = sOutDir;
        if (out == null) return;
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
        } catch (Throwable ignored) {
        }
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
