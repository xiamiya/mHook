package cn.mhook.dump;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.Adler32;

/**
 * DEX 文件修复工具：修复 dex 头的 checksum 和 signature。
 */
public class DexFixer {

    private static final int DEX_MAGIC = 0x6465780a; // "dex\n"
    private static final int DEX_MAGIC_LE = 0x0a786564;

    /**
     * 检查字节数组是否是有效的 DEX 文件。
     */
    public static boolean isDex(byte[] data) {
        if (data == null || data.length < 32) return false;
        // DEX magic: "dex\n035\0" 或类似
        return data[0] == 'd' && data[1] == 'e' && data[2] == 'x' && data[3] == '\n';
    }

    /**
     * 修复 DEX 文件头（checksum + signature）。
     */
    public static void fix(byte[] data) {
        if (data == null || data.length < 12) return;

        // 修正 file_size (offset 32-35)
        int fileSize = data.length;
        data[32] = (byte) (fileSize & 0xff);
        data[33] = (byte) ((fileSize >> 8) & 0xff);
        data[34] = (byte) ((fileSize >> 16) & 0xff);
        data[35] = (byte) ((fileSize >> 24) & 0xff);

        // 修正 checksum (offset 8-11) - adler32 of bytes 12..end
        long checksum = adler32(data, 12, data.length - 12);
        data[8] = (byte) (checksum & 0xff);
        data[9] = (byte) ((checksum >> 8) & 0xff);
        data[10] = (byte) ((checksum >> 16) & 0xff);
        data[11] = (byte) ((checksum >> 24) & 0xff);

        // 修正 signature (offset 12-31) - sha1 of bytes 32..end
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(data, 32, data.length - 32);
            byte[] sig = md.digest();
            System.arraycopy(sig, 0, data, 12, 20);
        } catch (Exception ignored) {}
    }

    private static long adler32(byte[] data, int offset, int len) {
        long a = 1, b = 0;
        for (int i = offset; i < offset + len; i++) {
            a = (a + (data[i] & 0xff)) % 65521;
            b = (b + a) % 65521;
        }
        return (b << 16) | a;
    }
}
