package cn.mhook.mhook.xposed.dump;

/**
 * DEX 体检器：解析已 dump 的 dex，逐类逐方法检查 codeItem 的方法体是否真实（抽取壳判定）。
 * 定位：思路是读 class_data_item → direct/virtual methods → code_off → code_item.insns，
 * 若大量方法 insns 全为 0x0000 → 判定为「方法抽取壳骨架」，否则为完整 dex。
 */
public class DexInspector {

    public static class Result {
        public int totalClasses;
        public int totalMethods;
        public int emptyMethods;      // insns 全 0
        public int truncated;         // code_off 越界等解析异常（保守视为异常但非空）
        public boolean isSkeleton;    // 空壳率超阈值
        public String summary;

        public int emptyRatePercent() {
            if (totalMethods == 0) return 0;
            return (int) (emptyMethods * 100L / totalMethods);
        }
    }

    /** 返回体检结果；data 不是合法 dex 返回 null。 */
    public static Result inspect(byte[] data) {
        if (data == null || data.length < 0x70) return null;
        if ((data[0] & 0xff) != 0x64 || (data[1] & 0xff) != 0x65
                || (data[2] & 0xff) != 0x78 || (data[3] & 0xff) != 0x0A) return null;
        Result r = new Result();
        try {
            int classDefsSize = le32(data, 0x60);
            int classDefsOff = le32(data, 0x64);
            if (classDefsOff + classDefsSize * 32L > data.length) classDefsSize = Math.max(0, (data.length - classDefsOff) / 32);
            r.totalClasses = classDefsSize;
            for (int i = 0; i < classDefsSize; i++) {
                int base = classDefsOff + i * 32;
                if (base + 32 > data.length) break;
                int classDataOff = le32(data, base + 24);
                if (classDataOff <= 0) continue;
                r.totalMethods += scanClassData(data, classDataOff, r);
            }
            int rate = r.emptyRatePercent();
            r.isSkeleton = rate >= 50;
            r.summary = String.format("classes=%d methods=%d empty=%d(%d%%) %s",
                    r.totalClasses, r.totalMethods, r.emptyMethods, rate,
                    r.isSkeleton ? "【疑似方法抽取壳】" : "OK(方法体完整)");
            return r;
        } catch (Throwable t) {
            r.summary = "体检异常: " + t.getMessage();
            return r;
        }
    }

    private static int scanClassData(byte[] data, int off, Result r) {
        int[] cursor = {off};
        int direct = (int) readLeb(data, cursor);   // direct_methods_size
        int virtual = (int) readLeb(data, cursor);  // virtual_methods_size
        int n = direct + virtual;
        // 跳过 static_fields + instance_fields (各 size 的字段, 每个字段2个uleb)
        int staticFields = (int) readLeb(data, cursor);
        int instanceFields = (int) readLeb(data, cursor);
        for (int i = 0; i < staticFields + instanceFields; i++) {
            readLeb(data, cursor);
            readLeb(data, cursor);
        }
        int empty = 0;
        for (int m = 0; m < n; m++) {
            readLeb(data, cursor); // method_idx_diff
            readLeb(data, cursor); // access_flags
            long codeOff = readLeb(data, cursor);
            if (codeOff > 0 && codeOff < data.length) {
                if (isCodeEmpty(data, (int) codeOff)) {
                    empty++;
                }
            } else if (codeOff != 0) {
                r.truncated++;
            }
        }
        r.totalMethods += n;
        r.emptyMethods += empty;
        return n;
    }

    /** code_item: regs(2) ins(2) outs(2) tries(2) debug_off(4) insns_size(4) insns[insns_size*2] */
    private static boolean isCodeEmpty(byte[] data, int codeOff) {
        try {
            if (codeOff + 16 > data.length) return true;
            int insnsSize = le32(data, codeOff + 12);
            int insStart = codeOff + 16;
            if (insStart + insnsSize * 2 > data.length) return true;
            for (int i = 0; i < insnsSize; i++) {
                int u16 = (data[insStart + i * 2] & 0xff) | ((data[insStart + i * 2 + 1] & 0xff) << 8);
                if (u16 != 0) return false;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** ULEB128 读取，返回长值；cursor[0] 为读指针。 */
    private static long readLeb(byte[] data, int[] cursor) {
        long v = 0;
        int shift = 0;
        int p = cursor[0];
        while (p < data.length && shift < 64) {
            int b = data[p] & 0xff;
            p++;
            v |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        cursor[0] = p;
        return v;
    }

    /** 从 dex 解析全部类描述符（形如 Lfoo/Bar;）。失败返回空数组。 */
    public static java.util.List<String> listClassDescriptors(byte[] data) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (data == null || data.length < 0x70) return out;
        try {
            int classDefsSize = le32(data, 0x60);
            int classDefsOff = le32(data, 0x64);
            int typeIdsSize = le32(data, 0x40);
            int typeIdsOff = le32(data, 0x44);
            int stringIdsSize = le32(data, 0x38);
            int stringIdsOff = le32(data, 0x3C);
            for (int i = 0; i < classDefsSize; i++) {
                int base = classDefsOff + i * 32;
                if (base + 32 > data.length) break;
                int typeIdx = le32(data, base);
                if (typeIdx < 0 || typeIdx >= typeIdsSize) continue;
                int descOff = le32(data, typeIdsOff + typeIdx * 4);
                if (descOff < 0 || descOff >= stringIdsSize) continue;
                int strDataOff = le32(data, stringIdsOff + descOff * 4);
                String s = readString(data, strDataOff);
                if (s != null && s.startsWith("L") && s.endsWith(";")) {
                    out.add(s);
                } else if (s != null) {
                    out.add(s);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** MUTF-8 字符串读取（跳过 uleb128 长度）。 */
    private static String readString(byte[] data, int off) {
        try {
            if (off < 0 || off + 1 > data.length) return null;
            int p = off;
            // skip uleb128 length
            while (p < data.length && (data[p] & 0x80) != 0) p++;
            p++;
            int start = p;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            while (p < data.length && data[p] != 0) {
                int b = data[p] & 0xff;
                if ((b & 0x80) == 0) {
                    bos.write(b); p++;
                } else if ((b & 0xE0) == 0xC0 && p + 1 < data.length) {
                    bos.write(((b & 0x1F) << 6) | (data[p + 1] & 0x3F)); p += 2;
                } else if ((b & 0xF0) == 0xE0 && p + 2 < data.length) {
                    bos.write(((b & 0x0F) << 12) | ((data[p + 1] & 0x3F) << 6) | (data[p + 2] & 0x3F)); p += 3;
                } else { p++; }
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }
}