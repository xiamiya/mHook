package cn.mhook.protect;

import java.io.File;

import bin.mt.apksign.key.JksSignatureKey;
import bin.mt.apksign.key.SignatureKey;
import cn.mhook.npatch.DerSignatureKey;

/**
 * 应用保护 · 签名配置。三种模式：内置证书 / 自有 Keystore / 自有 key+cert。
 * 口令只走内存、不落盘、不落日志。
 */
public class SignConfig {

    public static final int MODE_BUILTIN = 0;   // mHook 内置证书
    public static final int MODE_KEYSTORE = 1;  // 自有 .jks / .keystore
    public static final int MODE_KEYCERT = 2;   // key.pk8 + cert

    public int mode = MODE_BUILTIN;

    // keystore
    public String keystorePath = "";
    public String alias = "";
    public String storePass = "";
    public String keyPass = "";

    // key/cert
    public String keyPk8Path = "";
    public String certPath = "";

    // schemes
    public boolean v1 = true, v2 = true, v3 = true, v4 = false, zipalign = true;

    /** 构建签名器（内置证书的文件由调用方先确保已释放到 filesDir）。 */
    public SignatureKey buildKey(File builtinCert, File builtinKey) throws Exception {
        if (mode == MODE_KEYSTORE) {
            String kp = keyPass.isEmpty() ? storePass : keyPass;
            return new JksSignatureKey(new File(keystorePath), storePass, alias, kp);
        }
        if (mode == MODE_KEYCERT) {
            return new DerSignatureKey(new File(certPath), new File(keyPk8Path));
        }
        return new DerSignatureKey(builtinCert, builtinKey);
    }

    /** 校验配置是否完整（不校验口令正确性）。 */
    public String validate() {
        if (mode == MODE_KEYSTORE) {
            if (keystorePath.isEmpty()) return "请选择 Keystore 文件";
            if (alias.isEmpty()) return "请填写签名别名";
            if (storePass.isEmpty()) return "请填写库口令";
        } else if (mode == MODE_KEYCERT) {
            if (keyPk8Path.isEmpty() || certPath.isEmpty()) return "请选择 key.pk8 与证书";
        }
        return null;
    }
}
