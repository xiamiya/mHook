package cn.mhook.protect.rt;

import android.content.res.AssetManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 资源/配置文件透明解密：拦截 AssetManager.open(name)，
 * 若存在 assets/mhook_protect/data/&lt;name&gt;.enc 则解密返回，否则回退原 open。
 */
public class DataLoader {

    public static InputStream open(AssetManager am, String name) throws Exception {
        try {
            InputStream enc = am.open("mhook_protect/data/" + name + ".enc");
            if (enc != null) {
                byte[] data = readAll(enc);
                byte[] key = hex(new String(readAll(am.open("mhook_protect/data.cfg")), "UTF-8").trim());
                return new ByteArrayInputStream(decrypt(key, data));
            }
        } catch (FileNotFoundException ignored) {
        }
        return am.open(name);
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
