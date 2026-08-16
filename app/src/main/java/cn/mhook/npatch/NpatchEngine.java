package cn.mhook.npatch;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

import dalvik.system.DexClassLoader;

/**
 * 在设备端调用内置 NPatch patcher：把 DumpModule 嵌入目标 APK 并签名，
 * sigBypass 过签等级 3（Extreme）。npatch-dex.jar + 所需资源随 APK assets 打包。
 */
public class NpatchEngine {

    private static final String ASSET_ROOT = "npatch";
    private static final String[] ASSETS = {
            "npatch-dex.jar",
            "npatch.key", "keystore", "new_keystore", "fpa_app.key",
            "mtprovider.dex", "public.xml",
            "npatch/loader.bin", "npatch/metaloader.dex",
            "npatch/so/arm64-v8a/libnpatch.so",
            "npatch/so/x86_64/libnpatch.so",
            "DumpModule.apk"
    };

    private NpatchEngine() {
    }

    /** 把内置 npatch-dex.jar 与资源解压到 filesDir/npatch。 */
    public static File prepare(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "npatch");
        // 每次都重新解压，避免残留旧版本（如 DumpModule 升级）
        if (dir.exists()) {
            File[] olds = dir.listFiles();
            if (olds != null) {
                for (File o : olds) o.delete();
            }
        }
        if (!dir.exists()) dir.mkdirs();
        AssetManager am = ctx.getAssets();
        for (String rel : ASSETS) {
            File f = new File(dir, rel);
            if (f.exists()) continue;
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream is = am.open(ASSET_ROOT + "/" + rel)) {
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
            } catch (Throwable t) {
                // 缺失的资源不影响（按需加载）
            }
        }
        return dir;
    }

    /**
     * 执行 patch：把 moduleApk 嵌入 targetApk，sigBypass 3，输出到 outDir。
     * 阻塞执行，返回生成的 npatched.apk。
     */
    public static File patch(Context ctx, File targetApk, File moduleApk, File outDir) throws Exception {
        File dir = prepare(ctx);
        if (outDir.exists()) {
            File[] olds = outDir.listFiles();
            if (olds != null) {
                for (File o : olds) o.delete();
            }
        }
        if (!outDir.exists()) outDir.mkdirs();

        ClassLoader resourceLoader = new ResourceAwareLoader(dir, ctx.getClassLoader());
        File dexJar = new File(dir, "npatch-dex.jar");
        // DexClassLoader 拒绝可写的 dex 文件，必须置为只读
        if (dexJar.exists()) {
            dexJar.setWritable(false);
            dexJar.setReadable(true, false);
        }
        File optDir = new File(ctx.getCacheDir(), "npatch_opt");
        if (!optDir.exists()) optDir.mkdirs();
        ClassLoader cl = new DexClassLoader(dexJar.getPath(), optDir.getPath(), null, resourceLoader);

        Class<?> cls = Class.forName("top.nkbe.npatch.patch.NPatch", true, cl);
        java.lang.reflect.Method main = cls.getMethod("main", String[].class);
        String[] args = {
                "-o", outDir.getAbsolutePath(),
                targetApk.getAbsolutePath(),
                "-m", moduleApk.getAbsolutePath(),
                "-l", "3",
                "--force"
        };
        try {
            main.invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            StringBuilder sb = new StringBuilder(c.toString());
            for (StackTraceElement el : c.getStackTrace()) {
                sb.append("\n    at ").append(el);
            }
            if (c.getCause() != null) {
                sb.append("\nCaused by: ").append(c.getCause());
            }
            throw new Exception(sb.toString(), c);
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder(t.toString());
            for (StackTraceElement el : t.getStackTrace()) {
                sb.append("\n    at ").append(el);
            }
            throw new Exception(sb.toString(), t);
        }

        File[] files = outDir.listFiles();
        File best = null;
        long bestTime = -1;
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith("-npatched.apk") && f.length() > 1000 && f.lastModified() > bestTime) {
                    best = f;
                    bestTime = f.lastModified();
                }
            }
        }
        if (best != null) return best;
        throw new Exception("patch 未生成有效的输出文件");
    }

    /** 让 NPatch 的 getResourceAsStream("assets/...") 命中 filesDir/npatch 下的文件。 */
    private static final class ResourceAwareLoader extends ClassLoader {
        private final File dir;

        ResourceAwareLoader(File dir, ClassLoader parent) {
            super(parent);
            this.dir = dir;
        }

        /** 与宿主 app 冲突的库：不委托父加载器，由 npatch-dex 自己提供，避免 IllegalAccessError。 */
        private boolean isConflicting(String name) {
            return name.startsWith("com.google.common.")
                    || name.startsWith("org.apache.commons.io.")
                    || name.startsWith("com.google.gson.")
                    || name.startsWith("com.beust.jcommander.");
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> c = findLoadedClass(name);
            if (c == null && !isConflicting(name)) {
                try {
                    c = super.loadClass(name, false);
                } catch (Throwable ignored) {
                }
            }
            if (c == null) {
                throw new ClassNotFoundException(name);
            }
            if (resolve) resolveClass(c);
            return c;
        }

        private URL resolve(String name) {
            String rel = name;
            if (rel.startsWith("assets/")) rel = rel.substring("assets/".length());
            File f = new File(dir, rel);
            if (f.exists()) {
                try {
                    return f.toURI().toURL();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }

        @Override
        protected URL findResource(String name) {
            URL u = resolve(name);
            if (u != null) return u;
            return super.findResource(name);
        }

        @Override
        protected Enumeration<URL> findResources(String name) {
            URL u = resolve(name);
            if (u != null) {
                return Collections.enumeration(Collections.singletonList(u));
            }
            try {
                return super.findResources(name);
            } catch (Throwable t) {
                return Collections.emptyEnumeration();
            }
        }
    }
}
