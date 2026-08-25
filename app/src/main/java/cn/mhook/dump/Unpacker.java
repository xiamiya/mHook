package cn.mhook.dump;

import java.util.Arrays;

/**
 * 离线还原执行器：实现常见简单可逆壳原语。
 * - NRV2D/UCL 解压（腾讯/乐固等压缩型壳）
 * - XOR 字节变换
 * - 字节替换表变换
 * - ZIP 头尾 XOR 还原（爱加密容器层）
 * - 通用 ops 流水线（xor_repeat / byte_sub / rc4 / reverse）
 */
public class Unpacker {

    /** NRV2D 解压。maxOut 为输出上限（防爆）。 */
    public static byte[] nrv2dDecompress(byte[] src, int maxOut) {
        if (src == null || src.length == 0) return new byte[0];
        byte[] dst = new byte[maxOut];
        int s = 0, d = 0, bitpos = 0, last = 1;
        while (s < src.length && d < dst.length) {
            if (getbit(src, bitpos++) == 1) { dst[d++] = src[s++]; continue; }
            int w = 1;
            for (;;) {
                w = (w << 1) | getbit(src, bitpos++);
                if (getbit(src, bitpos++) == 1) break;
                w = (w << 1) - 2;
                w = w | getbit(src, bitpos++);
            }
            int offset, low = 0;
            if (w == 2) { offset = last; }
            else { low = src[s++] & 0xff; offset = (((w << 8) - 768 + low) >> 1) + 1; last = offset; }
            int prev;
            if (w == 2) { prev = getbit(src, bitpos++) << 1; }
            else { prev = ((~low) & 1) << 1; }
            int m = getbit(src, bitpos++) | prev;
            int length;
            if (m != 0) { length = m; }
            else {
                int ww = 1;
                for (;;) {
                    ww = (ww << 1) | getbit(src, bitpos++);
                    if (getbit(src, bitpos++) == 1) break;
                }
                length = ww + 2;
            }
            int copylen = length + 1 + (offset > 1280 ? 1 : 0);
            for (int i = 0; i < copylen; i++) {
                if (d >= dst.length || d - offset < 0) break;
                dst[d] = dst[d - offset];
                d++;
            }
        }
        return Arrays.copyOf(dst, d);
    }

    private static int getbit(byte[] src, int bitpos) {
        return (src[bitpos >> 3] >> (7 - (bitpos & 7))) & 1;
    }

    /** XOR 全量变换。 */
    public static byte[] xor(byte[] src, int key) {
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i++) out[i] = (byte) (src[i] ^ (key & 0xff));
        return out;
    }

    /** 字节替换表变换：out[i] = table[src[i] & 0xff]。 */
    public static byte[] byteSub(byte[] src, int[] table) {
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i++) {
            int idx = src[i] & 0xff;
            out[i] = (byte) (table != null && idx < table.length ? table[idx] : src[i]);
        }
        return out;
    }

    /** ZIP 头尾 XOR 还原：key<0 时自动按 PK\x03\x04 猜测。 */
    public static byte[] zipXorRestore(byte[] src, int key) {
        byte[] out = src.clone();
        int k = key;
        if (k < 0) {
            int[] votes = new int[256];
            byte[] want = {0x50, 0x4B, 0x03, 0x04};
            for (int i = 0; i < 4 && i < out.length; i++) votes[(out[i] ^ want[i]) & 0xff]++;
            int best = 0, bestV = -1;
            for (int i = 0; i < 256; i++) if (votes[i] > bestV) { bestV = votes[i]; best = i; }
            k = best;
        }
        for (int i = 0; i < out.length; i++) out[i] = (byte) (out[i] ^ (k & 0xff));
        return out;
    }

    /** 通用变换执行器：ops 列表按顺序应用。 */
    public static byte[] applyOps(byte[] src, com.alibaba.fastjson.JSONArray ops) {
        byte[] cur = src;
        if (ops == null) return cur;
        for (int i = 0; i < ops.size(); i++) {
            com.alibaba.fastjson.JSONObject op = ops.getJSONObject(i);
            if (op == null) continue;
            String name = op.getString("op");
            if ("xor_repeat".equals(name)) { byte[] k = hex(op.getString("key")); if (k != null && k.length > 0) cur = xorRepeat(cur, k); }
            else if ("byte_sub".equals(name)) { byte[] t = hex(op.getString("table")); if (t != null && t.length == 256) cur = byteSubTable(cur, t); }
            else if ("rc4".equals(name)) { byte[] k = hex(op.getString("key")); if (k != null && k.length > 0) cur = rc4(cur, k); }
            else if ("reverse".equals(name)) cur = reverse(cur);
        }
        return cur;
    }

    public static byte[] xorRepeat(byte[] src, byte[] key) {
        if (key == null || key.length == 0) return src.clone();
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i++) out[i] = (byte) (src[i] ^ key[i % key.length]);
        return out;
    }

    public static byte[] byteSubTable(byte[] src, byte[] table) {
        if (table == null || table.length != 256) return src.clone();
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i++) out[i] = table[src[i] & 0xff];
        return out;
    }

    public static byte[] rc4(byte[] src, byte[] key) {
        if (key == null || key.length == 0) return src.clone();
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xff)) & 0xff;
            int t = s[i]; s[i] = s[j]; s[j] = t;
        }
        byte[] out = new byte[src.length];
        int a = 0; j = 0;
        for (int i = 0; i < src.length; i++) {
            a = (a + 1) & 0xff;
            j = (j + s[a]) & 0xff;
            int t = s[a]; s[a] = s[j]; s[j] = t;
            out[i] = (byte) (src[i] ^ s[(s[a] + s[j]) & 0xff]);
        }
        return out;
    }

    public static byte[] reverse(byte[] src) {
        byte[] out = new byte[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[src.length - 1 - i];
        return out;
    }

    public static byte[] hex(String s) {
        if (s == null) return null;
        String t = s.trim().replace(" ", "").replace(",", "").replace("0x", "").replace("0X", "");
        if (t.length() % 2 != 0) t = "0" + t;
        try {
            byte[] out = new byte[t.length() / 2];
            for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
            return out;
        } catch (Throwable e) { return null; }
    }
}