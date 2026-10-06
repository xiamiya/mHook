package cn.mhook.protect.rt;

/**
 * DPT 密钥派生入口（类名固定，不参与重命名；native 通过 JNI_OnLoad 注册）。
 * key = SHA256(master || salt)[0..16]，master 只在 libdpt.so 中（混淆存放）。
 */
public class DptKey {

    static {
        System.loadLibrary("dpt");
    }

    public static native byte[] nativeKey(byte[] salt);
}
