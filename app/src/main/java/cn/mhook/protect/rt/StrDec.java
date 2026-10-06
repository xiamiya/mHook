package cn.mhook.protect.rt;

import android.content.Context;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;

/**
 * 字符串解密器（运行时，注入目标 APK）。
 * 与 DexStringEncrypt 对应： 自定义Base64 → [IV(4)‖密文] ；密钥流 = SHA256(KEY‖IV) 链式；明文 = (密文-位置偏移) XOR 密钥流。
 * KEY 以 obf-mask(0x5A) 形式存放，运行期还原。
 */
public class StrDec {

    private static final byte MASK = (byte) 0x5A;

    // KEY[i] ^ 0x5A
    private static final byte[] KA = {
            (byte) 0x61, (byte) 0x25, (byte) 0x48, (byte) 0x9E, (byte) 0xC0, (byte) 0x0F, (byte) 0xBA, (byte) 0x77,
            (byte) 0xD2, (byte) 0x3B, (byte) 0x55, (byte) 0xA9, (byte) 0x16, (byte) 0xF3, (byte) 0x4D, (byte) 0x34,
            (byte) 0x88, (byte) 0x6A, (byte) 0xE1, (byte) 0x2E, (byte) 0x53, (byte) 0xB2, (byte) 0x07, (byte) 0xC9,
            (byte) 0x7C, (byte) 0xF5, (byte) 0x1B, (byte) 0x9B, (byte) 0x31, (byte) 0x42, (byte) 0xA4, (byte) 0x0D
    };

    private static final char[] AL = "/+9876543210zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA".toCharArray();

    private static byte[] K;

    private static byte[] key() {
        byte[] k = K;
        if (k != null) return k;
        k = loadKey();
        if (k == null) {
            k = new byte[32];
            for (int i = 0; i < 32; i++) k[i] = (byte) (KA[i] ^ MASK);
        }
        K = k;
        return k;
    }

    /** 白盒化密钥：优先从 assets/mhook_protect/str.key 读取（掩码存储），失败回退内置常量。 */
    private static byte[] loadKey() {
        try {
            Context ctx = app();
            if (ctx == null) return null;
            InputStream in = ctx.getAssets().open("mhook_protect/str.key");
            byte[] b = new byte[32];
            int off = 0, n;
            while (off < 32 && (n = in.read(b, off, 32 - off)) != -1) off += n;
            in.close();
            if (off < 32) return null;
            for (int i = 0; i < 32; i++) b[i] = (byte) (b[i] ^ MASK);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context app() {
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

    public static String d(String s) {
        if (s == null) return null;
        try {
            return dec(s);
        } catch (Throwable t) {
            return s;
        }
    }

    private static String dec(String s) throws Exception {
        int pad = 0;
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == '=') {
            pad++;
            e--;
        }
        int bytes = s.length() / 4 * 3 - pad;
        byte[] data = new byte[bytes];
        int buf = 0, bits = 0, oi = 0;
        for (int i = 0; i < e; i++) {
            int v = idx(s.charAt(i));
            if (v < 0) v = 0;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                data[oi++] = (byte) (buf >> bits);
            }
        }
        int clen = data.length - 4;
        if (clen < 0) return s;
        byte[] iv = new byte[4];
        System.arraycopy(data, 0, iv, 0, 4);
        byte[] ks = keystream(iv, clen);
        byte[] pt = new byte[clen];
        for (int i = 0; i < clen; i++) {
            int c = data[4 + i] & 0xFF;
            c = (c - ((i * 7) & 0xFF)) & 0xFF;
            c ^= ks[i] & 0xFF;
            pt[i] = (byte) c;
        }
        return new String(pt, "UTF-8");
    }

    private static int idx(char c) {
        for (int i = 0; i < 64; i++) if (AL[i] == c) return i;
        return -1;
    }

    private static byte[] keystream(byte[] iv, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] seed = new byte[36];
        System.arraycopy(key(), 0, seed, 0, 32);
        System.arraycopy(iv, 0, seed, 32, 4);
        byte[] blk = md.digest(seed);
        byte[] out = new byte[len];
        int p = 0;
        while (p < len) {
            int c = Math.min(blk.length, len - p);
            System.arraycopy(blk, 0, out, p, c);
            p += c;
            blk = md.digest(blk);
        }
        return out;
    }
}
