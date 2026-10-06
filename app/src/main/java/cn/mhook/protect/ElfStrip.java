package cn.mhook.protect;

/**
 * SO 符号/段头混淆：清除 ELF section header 表（e_shoff/e_shnum/e_shstrndx = 0）
 * 并擦除原段头区，抹掉符号表/字符串表。动态链接器只用 program header（PT_LOAD/PT_DYNAMIC），不受影响。
 */
public final class ElfStrip {

    private ElfStrip() {
    }

    public static byte[] strip(byte[] data) {
        try {
            if (data == null || data.length < 64) return data;
            if (data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') return data;
            int cls = data[4] & 0xFF;   // 1=32,2=64
            int dat = data[5] & 0xFF;   // 1=LE,2=BE
            if (dat != 1) return data;  // 仅处理小端
            boolean is64 = cls == 2;
            long shoff = is64 ? u64(data, 0x28) : (u32(data, 0x20) & 0xFFFFFFFFL);
            int shentsize = is64 ? u16(data, 0x3A) : u16(data, 0x2E);
            int shnum = is64 ? u16(data, 0x3C) : u16(data, 0x30);
            int shstrndx = is64 ? u16(data, 0x3E) : u16(data, 0x32);
            if (shoff <= 0 || shnum <= 0 || shentsize <= 0) return data;
            for (int i = 0; i < shnum; i++) {
                long b = shoff + (long) i * shentsize;
                if (b + shentsize > data.length) break;
                long type, flags, off, size;
                if (is64) {
                    type = u32(data, (int) b + 4);
                    flags = u64(data, (int) b + 8);
                    off = u64(data, (int) b + 24);
                    size = u64(data, (int) b + 32);
                } else {
                    type = u32(data, (int) b + 4);
                    flags = u32(data, (int) b + 8);
                    off = u32(data, (int) b + 16);
                    size = u32(data, (int) b + 20);
                }
                if ((flags & 0x2) != 0) continue;   // SHF_ALLOC：参与加载，保留
                if (type == 8) continue;            // SHT_NOBITS：无文件内容
                if (i == shstrndx) continue;        // 保留段名字符串表
                if (size <= 0 || off < 0 || off + size > data.length) continue;
                for (long k = off; k < off + size; k++) data[(int) k] = 0;
            }
            return data;
        } catch (Throwable t) {
            return data;
        }
    }

    /** 解析 ELF 的 DT_NEEDED（依赖的 so 名）。 */
    public static java.util.List<String> needed(byte[] d) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            if (d == null || d.length < 64) return out;
            if (d[0] != 0x7f || d[1] != 'E' || d[2] != 'L' || d[3] != 'F') return out;
            boolean is64 = d[4] == 2;
            long phoff = is64 ? u64(d, 0x20) : (u32(d, 0x1C) & 0xFFFFFFFFL);
            int phentsize = is64 ? u16(d, 0x36) : u16(d, 0x2A);
            int phnum = is64 ? u16(d, 0x38) : u16(d, 0x2C);
            long dynOff = -1, dynSize = 0;
            java.util.List<long[]> loads = new java.util.ArrayList<>();
            for (int i = 0; i < phnum; i++) {
                int p = (int) (phoff + (long) i * phentsize);
                if (p < 0 || p + 56 > d.length) break;
                long type = u32(d, p);
                long off = is64 ? u64(d, p + 8) : (u32(d, p + 4) & 0xFFFFFFFFL);
                long vaddr = is64 ? u64(d, p + 16) : (u32(d, p + 8) & 0xFFFFFFFFL);
                long filesz = is64 ? u64(d, p + 32) : (u32(d, p + 16) & 0xFFFFFFFFL);
                if (type == 1) loads.add(new long[]{vaddr, off, filesz});
                if (type == 2) {
                    dynOff = off;
                    dynSize = filesz;
                }
            }
            if (dynOff < 0) return out;
            int entsize = is64 ? 16 : 8;
            long strtabAddr = -1;
            java.util.List<Long> needOffs = new java.util.ArrayList<>();
            for (long o = dynOff; o + entsize <= dynOff + dynSize && o + entsize <= d.length; o += entsize) {
                long tag = is64 ? u64(d, (int) o) : (u32(d, (int) o) & 0xFFFFFFFFL);
                long val = is64 ? u64(d, (int) o + 8) : (u32(d, (int) o + 4) & 0xFFFFFFFFL);
                if (tag == 0) break;
                if (tag == 1) needOffs.add(val);
                else if (tag == 5) strtabAddr = val;
            }
            if (strtabAddr < 0) return out;
            long strOff = strtabAddr;
            for (long[] l : loads) {
                if (strtabAddr >= l[0] && strtabAddr < l[0] + Math.max(l[2], 1)) {
                    strOff = l[1] + (strtabAddr - l[0]);
                    break;
                }
            }
            for (long no : needOffs) {
                int p = (int) (strOff + no);
                if (p < 0 || p >= d.length) continue;
                int e = p;
                while (e < d.length && d[e] != 0) e++;
                out.add(new String(d, p, e - p, "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static int u16(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] d, int p) {
        return (d[p] & 0xFFL) | ((d[p + 1] & 0xFFL) << 8) | ((d[p + 2] & 0xFFL) << 16) | ((d[p + 3] & 0xFFL) << 24);
    }

    private static long u64(byte[] d, int p) {
        return u32(d, p) | (u32(d, p + 4) << 32);
    }

    private static void put16(byte[] d, int p, int v) {
        d[p] = (byte) v;
        d[p + 1] = (byte) (v >> 8);
    }

    private static void put32(byte[] d, int p, long v) {
        d[p] = (byte) v;
        d[p + 1] = (byte) (v >> 8);
        d[p + 2] = (byte) (v >> 16);
        d[p + 3] = (byte) (v >> 24);
    }

    private static void put64(byte[] d, int p, long v) {
        put32(d, p, v & 0xFFFFFFFFL);
        put32(d, p + 4, (v >>> 32) & 0xFFFFFFFFL);
    }
}
