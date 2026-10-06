package cn.mhook.protect.rt;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;

import java.util.List;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * 运行时防护（RASP）：随 App 启动的 ContentProvider。
 * 检测：root(1) / 模拟器(2) / Frida(4) / 调试(8) / 完整性(16) / 防截屏(32) / 签名校验(64)。
 * 配置从 manifest meta-data 读取： mhook.guard.cfg = "flags:action"；mhook.guard.sig = 签名证书 SHA-256；
 * mhook.guard.dex = classes.dex 的 SHA-256。 action==0 命中即退出，否则仅记录日志。
 */
public class GuardProvider extends ContentProvider {

    public static final String TAG = "mhook.guard";

    @Override
    public boolean onCreate() {
        try {
            Context ctx = getContext();
            int flags = 0, action = 1;
            String expSig = "", expDex = "";
            try {
                String[] p = readAsset(ctx, "mhook_protect/guard.cfg").split(":");
                flags = Integer.parseInt(p[0]);
                action = Integer.parseInt(p[1]);
                if (p.length > 2) expSig = p[2];
                if (p.length > 3) expDex = p[3];
            } catch (Throwable ignored) {
            }

            Log.i(TAG, "init flags=" + flags + " action=" + action
                    + " emu=" + isEmulator() + " frida=" + isFrida()
                    + " dbg=" + (Debug.isDebuggerConnected() || tracerPid() != 0)
                    + " | " + rootDiag(ctx));
            StringBuilder hit = new StringBuilder();
            if ((flags & 1) != 0 && isRooted(ctx)) hit.append("root ");
            if ((flags & 2) != 0 && isEmulator()) hit.append("emulator ");
            if ((flags & 4) != 0 && isFrida()) hit.append("frida ");
            if ((flags & 8) != 0 && (Debug.isDebuggerConnected() || tracerPid() != 0)) hit.append("debug ");
            if ((flags & 16) != 0 && !expDex.isEmpty() && !expDex.equalsIgnoreCase(ownDexSha(ctx))) hit.append("dex-modified ");
            if ((flags & 64) != 0 && !expSig.isEmpty() && !expSig.equalsIgnoreCase(currentSignSha(ctx))) hit.append("sig-mismatch ");
            if ((flags & 128) != 0 && isMultiOpened(ctx)) hit.append("multi-open ");
            if ((flags & 256) != 0 && hasForeignA11y(ctx)) hit.append("a11y ");
            if ((flags & 2048) != 0 && isAccelerated()) hit.append("accel ");

            if ((flags & 32) != 0 || (flags & 512) != 0) {
                final boolean secure = (flags & 32) != 0;
                final boolean clip = (flags & 512) != 0;
                try {
                    Application app = (Application) ctx.getApplicationContext();
                    final Context c = ctx;
                    app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                        public void onActivityResumed(Activity a) {
                            if (!secure) return;
                            try {
                                a.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
                            } catch (Throwable ignored) {
                            }
                        }
                        public void onActivityPaused(Activity a) {
                            if (!clip) return;
                            try {
                                ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (cm != null && cm.hasPrimaryClip()) cm.setPrimaryClip(ClipData.newPlainText("", ""));
                            } catch (Throwable ignored) {
                            }
                        }
                        public void onActivityCreated(Activity a, Bundle b) { }
                        public void onActivityStarted(Activity a) { }
                        public void onActivityStopped(Activity a) { }
                        public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                        public void onActivityDestroyed(Activity a) { }
                    });
                } catch (Throwable ignored) {
                }
            }

            if ((flags & 1024) != 0) {
                try {
                    installSslPin();
                } catch (Throwable ignored) {
                }
            }
            if ((flags & 8192) != 0 && !verifyDexes(ctx)) hit.append("mem ");
            if ((flags & 16384) != 0 && hookLibs()) hit.append("hook ");
            if ((flags & 65536) != 0 && !verifySo(ctx)) hit.append("so ");
            if ((flags & 131072) != 0 && memTampered()) hit.append("fullmem ");
            if ((flags & 32768) != 0 && !hwidMatch(ctx)) {
                hit.append("hwid ");
                Log.w(TAG, "hwid unauthorized, device=" + deviceId());
            }
            if ((flags & 4096) != 0) {
                try {
                    installCrashLog(ctx);
                } catch (Throwable ignored) {
                }
            }

            try {
                Rasp.nativeStart(flags, action);
            } catch (Throwable ignored) {
            }

            if (hit.length() > 0) {
                Log.w(TAG, "env risk: " + hit.toString().trim());
                if (action == 0) {
                    try {
                        Process.killProcess(Process.myPid());
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 设备绑定：比对 assets/mhook_protect/hwid.cfg 中记录的设备指纹。 */
    static boolean hwidMatch(Context ctx) {
        try {
            String exp = readAsset(ctx, "mhook_protect/hwid.cfg").trim();
            if (exp.isEmpty()) return true;      // 白名单为空 -> 不限制
            String id = deviceId();
            for (String t : exp.split("[,\\s;]+")) {
                if (id.equalsIgnoreCase(t.trim())) return true;
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    static String deviceId() {
        try {
            String s = Build.MANUFACTURER + "|" + Build.MODEL + "|" + Build.BRAND + "|"
                    + Build.DEVICE + "|" + Build.HARDWARE + "|" + Build.FINGERPRINT;
            return toHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }

    /** 防内存 dump/篡改：apk/dex/vdex/odex/oat 映射被置为可写视为异常（正常只读）。 */
    static boolean memTampered() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String l;
            while ((l = r.readLine()) != null) {
                int sp = l.indexOf(' ');
                if (sp < 0) continue;
                int sp2 = l.indexOf(' ', sp + 1);
                if (sp2 < 0) continue;
                String perms = l.substring(sp + 1, sp2).trim();
                // 仅当同时可写且可执行（rwx，正常 apk/dex 映射绝不该如此）才判定被篡改，避免误报
                if (perms.indexOf('w') < 0 || perms.indexOf('x') < 0) continue;
                String p = l.substring(sp2 + 1).trim().toLowerCase();
                if (p.contains("base.apk") || p.endsWith(".dex") || p.endsWith(".vdex")
                        || p.endsWith(".odex") || p.endsWith(".oat")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 全 dex 完整性自校验。 */
    static boolean verifyDexes(Context ctx) {
        return verifyHashes(ctx, "mhook_protect/dexhash.cfg");
    }

    /** SO 防篡改：比对 assets/mhook_protect/sohash.cfg。 */
    static boolean verifySo(Context ctx) {
        return verifyHashes(ctx, "mhook_protect/sohash.cfg");
    }

    static boolean verifyHashes(Context ctx, String asset) {
        try {
            String cfg = readAsset(ctx, asset);
            if (cfg.isEmpty()) return true;
            java.util.Map<String, String> exp = new java.util.HashMap<>();
            for (String line : cfg.split("\n")) {
                int i = line.indexOf(':');
                if (i > 0) exp.put(line.substring(0, i).trim(), line.substring(i + 1).trim().toLowerCase());
            }
            if (exp.isEmpty()) return true;
            java.util.zip.ZipFile z = new java.util.zip.ZipFile(ctx.getApplicationInfo().sourceDir);
            try {
                for (java.util.Map.Entry<String, String> e : exp.entrySet()) {
                    java.util.zip.ZipEntry ze = z.getEntry(e.getKey());
                    if (ze == null) return false;
                    String h = sha256Stream(z.getInputStream(ze));
                    if (h == null || !h.equalsIgnoreCase(e.getValue())) return false;
                }
            } finally {
                z.close();
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    static String sha256Stream(InputStream in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) md.update(b, 0, n);
            in.close();
            return toHex(md.digest());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Hook 框架 / 注入库检测（/proc/self/maps）。 */
    static boolean hookLibs() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String l;
            while ((l = r.readLine()) != null) {
                String s = l.toLowerCase();
                if (s.contains("libsubstrate") || s.contains("libdobby") || s.contains("libsandhook")
                        || s.contains("libwhale") || s.contains("libepic") || s.contains("xposed")
                        || s.contains("libshadowhook") || s.contains("liblsplant") || s.contains("libpine")
                        || s.contains("libriru")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 崩溃日志：默认未捕获异常处理器写文件后转发原处理器。 */
    static void installCrashLog(final Context ctx) {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    writeCrash(ctx, t, e);
                } catch (Throwable ignored) {
                }
                if (def != null) def.uncaughtException(t, e);
            }
        });
    }

    static void writeCrash(Context ctx, Thread t, Throwable e) {
        try {
            File dir = ctx.getFilesDir();
            File f = new File(dir, "mhook_crash.log");
            if (f.exists() && f.length() > 512 * 1024) f.delete();
            java.io.PrintWriter w = new java.io.PrintWriter(new java.io.FileWriter(f, true));
            try {
                w.println("---- " + new java.util.Date() + " ----");
                w.println("thread: " + (t != null ? t.getName() : "?"));
                e.printStackTrace(w);
                w.flush();
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 变速/加速器检测：墙钟推进明显快于实时时钟 -> 疑似变速。 */
    static boolean isAccelerated() {
        try {
            long rt = android.os.SystemClock.elapsedRealtime();
            long ct = System.currentTimeMillis();
            Thread.sleep(300);
            long drt = android.os.SystemClock.elapsedRealtime() - rt;
            long dct = System.currentTimeMillis() - ct;
            return dct - drt > 200;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 仅信任系统 CA 的默认 SSLContext（排除用户安装的 CA，防代理抓包）。 */
    static void installSslPin() throws Exception {
        KeyStore sys = KeyStore.getInstance(KeyStore.getDefaultType());
        sys.load(null, null);
        KeyStore aks = KeyStore.getInstance("AndroidCAStore");
        aks.load(null, null);
        Enumeration<String> al = aks.aliases();
        while (al.hasMoreElements()) {
            String a = al.nextElement();
            if (a != null && a.startsWith("system:")) {
                java.security.cert.Certificate c = aks.getCertificate(a);
                if (c != null) sys.setCertificateEntry(a, c);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(sys);
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, tmf.getTrustManagers(), new SecureRandom());
        SSLContext.setDefault(sc);
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    }

    static String readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
            return new String(b.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    static String currentSignSha(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi;
            if (Build.VERSION.SDK_INT >= 28) {
                pi = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] s = pi.signingInfo != null && pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
                return sha256Hex(s[0].toByteArray());
            } else {
                pi = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
                return sha256Hex(pi.signatures[0].toByteArray());
            }
        } catch (Throwable t) {
            return "";
        }
    }

    static String ownDexSha(Context ctx) {
        ZipFile z = null;
        try {
            String apk = ctx.getApplicationInfo().sourceDir;
            z = new ZipFile(apk);
            ZipEntry e = z.getEntry("classes.dex");
            if (e == null) return "";
            try (InputStream in = z.getInputStream(e)) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
                return toHex(md.digest());
            }
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                if (z != null) z.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static String sha256Hex(byte[] b) throws Exception {
        return toHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    static boolean isMultiOpened(Context ctx) {
        try {
            int my = Process.myUid();
            int real = ctx.getPackageManager().getApplicationInfo(ctx.getPackageName(), 0).uid;
            return my != real;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean hasForeignA11y(Context ctx) {
        try {
            AccessibilityManager am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (list == null) return false;
            for (AccessibilityServiceInfo i : list) {
                if (i.getResolveInfo() != null && i.getResolveInfo().serviceInfo != null
                        && !ctx.getPackageName().equals(i.getResolveInfo().serviceInfo.packageName)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean isRooted(Context ctx) {
        return hasSuFile() || hasRootPackage(ctx) || mountsHit() || whichSu() || mapsHit()
                || (Build.TAGS != null && Build.TAGS.contains("test-keys"));
    }

    static String rootDiag(Context ctx) {
        return "suFile=" + hasSuFile() + " rootPkg=" + hasRootPackage(ctx)
                + " mounts=" + mountsHit() + " whichSu=" + whichSu()
                + " maps=" + mapsHit() + " tags=" + Build.TAGS;
    }

    static boolean hasSuFile() {
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/sd/xbin/su",
                "/data/local/bin/su", "/data/local/xbin/su", "/su/bin/su", "/system/app/Superuser.apk",
                "/system/bin/failsafe/su", "/magisk/.core/bin/su",
                "/data/adb/magisk", "/data/adb/magisk.img", "/data/adb/ksu", "/data/adb/ksud",
                "/data/adb/modules", "/sbin/.magisk", "/dev/.magisk", "/cache/.disable_magisk",
                "/data/adb/ksu/bin/ksud", "/data/adb/ksu/modules"};
        for (String p : paths) {
            try {
                if (new File(p).exists()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    static boolean hasRootPackage(Context ctx) {
        String[] pkgs = {"com.topjohnwu.magisk", "me.weishu.kernelsu", "eu.chainfire.supersu",
                "com.noshufou.android.su", "com.koushikdutta.superuser", "com.thirdparty.superuser",
                "com.yellowes.su", "com.zachspong.temprootremovejb", "com.ramdroid.appquarantine",
                "com.dianxinos.superuser", "com.speedsoftware.rootexplorer"};
        PackageManager pm = null;
        try {
            pm = ctx.getPackageManager();
        } catch (Throwable ignored) {
        }
        if (pm != null) {
            for (String p : pkgs) {
                try {
                    pm.getPackageInfo(p, 0);
                    return true;
                } catch (Throwable ignored) {
                }
            }
            try {
                java.util.List<android.content.pm.PackageInfo> list = pm.getInstalledPackages(0);
                if (list != null) {
                    for (android.content.pm.PackageInfo pi : list) {
                        String n = pi.packageName;
                        if (n == null) continue;
                        String s = n.toLowerCase();
                        if (s.contains("magisk") || s.contains("kernelsu") || s.contains("supersu")
                                || s.equals("eu.chainfire.supersu") || s.endsWith(".su")) return true;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    static boolean mountsHit() {
        String[] files = {"/proc/mounts", "/proc/self/mounts", "/proc/self/mountinfo"};
        for (String f : files) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String l;
                while ((l = r.readLine()) != null) {
                    String s = l.toLowerCase();
                    if (s.contains("magisk") || s.contains("kernelsu") || s.contains("/.magisk")
                            || s.contains("core/magisk") || s.contains("ksu")
                            || s.contains("lsposed") || s.contains("zygisk")) return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    static boolean mapsHit() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String l;
            while ((l = r.readLine()) != null) {
                String s = l.toLowerCase();
                if (s.contains("magisk") || s.contains("zygisk") || s.contains("lsposed")
                        || s.contains("kernelsu") || s.contains("ksu")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean whichSu() {
        for (String p : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su"}) {
            try {
                if (new File(p).canExecute()) return true;
            } catch (Throwable ignored) {
            }
        }
        java.lang.Process pr = null;
        try {
            java.lang.ProcessBuilder pb = new java.lang.ProcessBuilder("/system/bin/sh", "-c", "which su || command -v su");
            pb.redirectErrorStream(true);
            pr = pb.start();
            final java.lang.Process fpr = pr;
            final boolean[] done = {false};
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(fpr.getInputStream()))) {
                    String l = r.readLine();
                    if (l != null && l.contains("su")) done[0] = true;
                } catch (Throwable ignored) {
                }
            });
            t.setDaemon(true);
            t.start();
            t.join(800);
            if (done[0]) return true;
        } catch (Throwable ignored) {
        } finally {
            try {
                if (pr != null) pr.destroy();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    static boolean isEmulator() {
        try {
            String f = Build.FINGERPRINT;
            if (f != null && (f.startsWith("generic") || f.contains("vbox") || f.contains("emulator") || f.contains("test-keys"))) return true;
            String hw = Build.HARDWARE;
            if (hw != null && (hw.contains("goldfish") || hw.contains("ranchu"))) return true;
            if ("google_sdk".equals(Build.PRODUCT) || "sdk".equals(Build.PRODUCT)) return true;
            String model = Build.MODEL;
            if (model != null && (model.contains("Emulator") || model.contains("Android SDK"))) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean isFrida() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.contains("frida") || l.contains("gadget") || l.contains("libfrida")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static int tracerPid() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("TracerPid:")) return Integer.parseInt(l.substring(10).trim());
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
