package cn.mhook.protect;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 资源混淆（安全子集）：把 resources.arsc 全局字符串池里 `res/...` 的**文件名**等长替换为短名，
 * 同时把 APK 内对应的 `res/*` 条目改名。等长替换不改变字符串池偏移/长度，故不重建 arsc，稳定安全。
 * 任一环节不确定 → 返回 null（不做任何改动）。
 */
public final class ResObfuscate {

    public static class Result {
        public byte[] arsc;
        public final Map<String, String> rename = new HashMap<>();
    }

    private ResObfuscate() {
    }

    /** arsc 原地改字符串池；zipNames 为 APK 内实际存在的条目名（只改名能对上的）。 */
    public static Result process(byte[] arsc, Set<String> zipNames) {
        try {
            if (arsc == null || arsc.length < 12) return null;
            if (u16(arsc, 0) != 0x0002) return null;            // ResTable_header
            int sp = 12;
            if (u16(arsc, sp) != 0x0001) return null;           // StringPool
            int spHeaderSize = u16(arsc, sp + 2);
            int stringCount = i32(arsc, sp + 8);
            int flags = i32(arsc, sp + 16);
            int stringsStart = i32(arsc, sp + 20);
            boolean utf8 = (flags & 0x100) != 0;
            long offArr = sp + spHeaderSize;
            long dataStart = (long) sp + stringsStart;
            if (offArr + (long) stringCount * 4 > arsc.length) return null;

            Result r = new Result();
            Set<String> used = new HashSet<>();
            for (int i = 0; i < stringCount; i++) {
                int off = i32(arsc, (int) (offArr + (long) i * 4));
                int pos = (int) (dataStart + off);
                if (pos < 0 || pos >= arsc.length) continue;
                int bytesStart, byteLen;
                String s;
                if (utf8) {
                    int[] a = uleb(arsc, pos);
                    int[] b = uleb(arsc, a[1]);
                    bytesStart = b[1];
                    byteLen = b[0];
                    if (bytesStart < 0 || byteLen < 0 || bytesStart + byteLen > arsc.length) continue;
                    s = new String(arsc, bytesStart, byteLen, StandardCharsets.UTF_8);
                } else {
                    int[] a = uleb(arsc, pos);
                    bytesStart = a[1];
                    byteLen = a[0] * 2;
                    if (bytesStart < 0 || byteLen < 0 || bytesStart + byteLen > arsc.length) continue;
                    s = new String(arsc, bytesStart, byteLen, StandardCharsets.UTF_16LE);
                }
                if (!isResPath(s)) continue;
                if (!zipNames.contains(s)) continue;            // 必须与包内条目一致
                String nn = shortName(s);
                if (nn == null || nn.equals(s)) continue;
                if (used.contains(nn)) continue;
                // 写回（等长）
                if (utf8) {
                    byte[] nb = nn.getBytes(StandardCharsets.UTF_8);
                    if (nb.length != byteLen) continue;
                    System.arraycopy(nb, 0, arsc, bytesStart, nb.length);
                } else {
                    if (nn.length() * 2 != byteLen) continue;
                    for (int k = 0; k < nn.length(); k++) {
                        arsc[bytesStart + k * 2] = (byte) nn.charAt(k);
                        arsc[bytesStart + k * 2 + 1] = 0;
                    }
                }
                used.add(nn);
                r.rename.put(s, nn);
            }
            if (r.rename.isEmpty()) return null;
            r.arsc = arsc;
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isResPath(String s) {
        if (!s.startsWith("res/") || s.length() < 5) return false;
        int slash = s.lastIndexOf('/');
        return slash >= 4;                                       // 至少 res/x/..
    }

    /** 保持目录与扩展名，把文件名替换为等长的 'a'；非 ASCII 文件名则放弃。 */
    private static String shortName(String s) {
        int slash = s.lastIndexOf('/');
        int dot = s.lastIndexOf('.');
        String base, ext;
        if (dot > slash) {
            base = s.substring(slash + 1, dot);
            ext = s.substring(dot);
        } else {
            base = s.substring(slash + 1);
            ext = "";
        }
        if (base.isEmpty()) return null;
        for (int i = 0; i < base.length(); i++) if (base.charAt(i) > 127) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < base.length(); i++) b.append('a');
        return s.substring(0, slash + 1) + b + ext;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    /** 返回 {value, nextOffset}。 */
    private static int[] uleb(byte[] b, int o) {
        int v = 0, shift = 0;
        while (true) {
            int x = b[o] & 0xFF;
            v |= (x & 0x7F) << shift;
            o++;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return new int[]{v, o};
    }
}
