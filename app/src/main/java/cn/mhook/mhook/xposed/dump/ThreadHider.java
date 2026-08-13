package cn.mhook.mhook.xposed.dump;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;

import top.niunaijun.blackbox.core.NativeCore;

/**
 * Thread name disguise: periodically scan /proc/self/task comm entries and
 * rename threads with sandbox markers or default Thread-N to system-like names.
 */
public class ThreadHider {

    private static final String TAG = "ThreadHider";
    private static volatile boolean sStarted;

    private static final String[] POOL = {
            "FinalizerDaemon", "HeapTaskDaemon", "ReferenceQueueDaemon",
            "Binder:1", "Binder:2", "RenderThread", "Jit thread pool",
            "Signal Catcher", "hwuiTask1", "hwuiTask2", "OkHttp", "AsyncTask #1",
            "ThreadPoolWorker1", "ThreadPoolWorker2", "SharedPreferences",
    };

    public static void start() {
        if (sStarted) return;
        sStarted = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                int idx = 0;
                while (true) {
                    try {
                        File dir = new File("/proc/self/task");
                        File[] tids = dir.listFiles();
                        if (tids != null) {
                            for (File t : tids) {
                                try {
                                    String tid = t.getName();
                                    String comm = readComm(tid);
                                    if (comm == null || comm.isEmpty()) continue;
                                    if (isSuspicious(comm)) {
                                        String nn = POOL[idx % POOL.length];
                                        idx++;
                                        NativeCore.setThreadName(Integer.parseInt(tid), nn);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(3000);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }).start();
        Log.i(TAG, "ThreadHider started");
    }

    private static boolean isSuspicious(String comm) {
        String c = comm.trim();
        if (c.isEmpty()) return false;
        String l = c.toLowerCase();
        if (l.contains("sandbox") || l.contains("blackbox") || l.contains("va_")
                || l.contains("xposed") || l.contains("dumper") || l.contains("crashmonitor")
                || l.contains("niunaijun") || l.contains("mhook") || l.contains("applaunch")
                || l.contains("hook")) {
            return true;
        }
        return c.startsWith("Thread-");
    }

    private static String readComm(String tid) {
        try {
            FileInputStream in = new FileInputStream("/proc/self/task/" + tid + "/comm");
            byte[] b = new byte[32];
            int n;
            try {
                n = in.read(b);
            } finally {
                in.close();
            }
            if (n <= 0) return null;
            return new String(b, 0, n);
        } catch (Throwable t) {
            return null;
        }
    }
}
