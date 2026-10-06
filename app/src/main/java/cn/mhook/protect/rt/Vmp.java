package cn.mhook.protect.rt;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VMP 运行时入口（类名固定，不参与重命名混淆；native 通过 JNI_OnLoad 注册）。
 * key = assets/mhook_protect/vmp/<key> 资源名；id = 该方法在 bundle 中的程序下标。
 */
public class Vmp {

    static {
        System.loadLibrary("vmp");
    }

    private static final ConcurrentHashMap<String, byte[]> CACHE = new ConcurrentHashMap<>();

    public static int run(String key, int id, int[] args) {
        byte[] b = bundle(key);
        return b == null ? 0 : nativeRun(b, id, args);
    }

    public static long runL(String key, int id, int[] args) {
        byte[] b = bundle(key);
        return b == null ? 0 : nativeRunL(b, id, args);
    }

    static byte[] bundle(String key) {
        byte[] c = CACHE.get(key);
        if (c != null) return c;
        try {
            Context ctx = app();
            if (ctx == null) return null;
            try (InputStream in = ctx.getAssets().open("mhook_protect/vmp/" + key)) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
                c = b.toByteArray();
            }
            CACHE.put(key, c);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    static Context app() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object o = m.invoke(null);
            return o instanceof Context ? (Context) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static native int nativeRun(byte[] bundle, int id, int[] args);

    public static native long nativeRunL(byte[] bundle, int id, int[] args);
}
