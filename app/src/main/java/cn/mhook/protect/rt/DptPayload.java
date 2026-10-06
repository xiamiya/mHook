package cn.mhook.protect.rt;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * DPT 运行时：从 assets/mhook_protect/payloadN.dex 读取加密抽取类，解密后在**内存**中加载
 * （API26+ 用 InMemoryDexClassLoader，不落盘）；低版本回退到私有目录文件式加载。
 */
public final class DptPayload {

    private DptPayload() {
    }

    public static ClassLoader create(ApplicationInfo ai, ClassLoader parent) {
        try {
            String apk = ai.sourceDir;
            String cfg;
            try (ZipFile z = new ZipFile(apk)) {
                byte[] c = readEntry(z, "assets/mhook_protect/dpt.cfg");
                cfg = (c != null) ? new String(c, "UTF-8") : null;
            }
            if (cfg == null || cfg.isEmpty()) return null;
            String[] p = cfg.split("\\|");
            byte[] salt = hex(p[0]);
            int n = Integer.parseInt(p[1]);
            byte[] key = DptKey.nativeKey(salt);
            if (key == null) return null;
            if (n <= 0) return null;

            if (Build.VERSION.SDK_INT >= 26) {
                ByteBuffer[] bufs = new ByteBuffer[n];
                try (ZipFile z = new ZipFile(apk)) {
                    for (int i = 0; i < n; i++) {
                        byte[] dex = decrypt(key, readEntry(z, "assets/mhook_protect/payload" + i + ".dex"));
                        if (dex == null) return null;
                        bufs[i] = ByteBuffer.wrap(dex);
                    }
                }
                return new dalvik.system.InMemoryDexClassLoader(bufs, parent);
            }
            // API 24/25 回退：文件式（带 nativeLibraryDir）
            File dir = new File(ai.dataDir, "dpt");
            dir.mkdirs();
            StringBuilder paths = new StringBuilder();
            try (ZipFile z = new ZipFile(apk)) {
                for (int i = 0; i < n; i++) {
                    byte[] dex = decrypt(key, readEntry(z, "assets/mhook_protect/payload" + i + ".dex"));
                    if (dex == null) return null;
                    File f = new File(dir, "payload" + i + ".dex");
                    try (FileOutputStream fo = new FileOutputStream(f)) {
                        fo.write(dex);
                    }
                    if (paths.length() > 0) paths.append(File.pathSeparator);
                    paths.append(f.getAbsolutePath());
                }
            }
            return new dalvik.system.DexClassLoader(paths.toString(), dir.getAbsolutePath(),
                    ai.nativeLibraryDir, parent);
        } catch (Throwable t) {
            Log.e("mhook.dpt", "payload load failed", t);
            return null;
        }
    }

    static byte[] decrypt(byte[] key, byte[] data) throws Exception {
        if (data == null || data.length < 17) return null;
        byte[] iv = new byte[16];
        System.arraycopy(data, 0, iv, 0, 16);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data, 16, data.length - 16);
    }

    static byte[] readEntry(ZipFile z, String name) throws Exception {
        ZipEntry e = z.getEntry(name);
        if (e == null) return null;
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
