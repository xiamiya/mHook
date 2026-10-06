package cn.mhook.protect.rt;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SO 加载器：
 *  - 若 assets/mhook_protect/so/&lt;abi&gt;/&lt;so&gt;.enc 存在 → 解密到私有目录后 System.load；
 *  - 否则用 App 自身 nativeLibraryDir 直接 System.load（供内存加载器/DPT 场景下 loadLibrary 使用）；
 *  - 都没有 → 回退 System.loadLibrary(name)。
 */
public class SoLoader {

    public static void load(String name) {
        try {
            if (load0(name)) return;
        } catch (Throwable ignored) {
        }
        System.loadLibrary(name);
    }

    static boolean load0(String name) throws Exception {
        Context ctx = app();
        if (ctx == null) return false;
        String soname = (name.startsWith("lib") && name.endsWith(".so")) ? name : ("lib" + name + ".so");
        String apk = ctx.getApplicationInfo().sourceDir;
        byte[] enc = null;
        String keyHex = null;
        try (ZipFile z = new ZipFile(apk)) {
            ZipEntry k = z.getEntry("assets/mhook_protect/so.cfg");
            if (k != null) {
                keyHex = new String(readAll(z.getInputStream(k)), "UTF-8").trim();
                for (String abi : android.os.Build.SUPPORTED_ABIS) {
                    ZipEntry e = z.getEntry("assets/mhook_protect/so/" + abi + "/" + soname + ".enc");
                    if (e != null) {
                        enc = readAll(z.getInputStream(e));
                        break;
                    }
                }
            }
        }
        if (enc != null && keyHex != null) {
            byte[] so = decrypt(hex(keyHex), enc);
            File dir = ctx.getDir("mso", 0);
            File f = new File(dir, soname);
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(so);
            }
            System.load(f.getAbsolutePath());
            return true;
        }
        // 未加密：直接用 App 的 nativeLibraryDir
        File f = new File(ctx.getApplicationInfo().nativeLibraryDir, soname);
        if (f.exists()) {
            System.load(f.getAbsolutePath());
            return true;
        }
        return false;
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

    static byte[] decrypt(byte[] key, byte[] data) throws Exception {
        byte[] iv = new byte[16];
        System.arraycopy(data, 0, iv, 0, 16);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data, 16, data.length - 16);
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
