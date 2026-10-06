package cn.mhook.protect;

import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import bin.mt.apksign.V2V3SchemeSigner;
import bin.mt.apksign.key.SignatureKey;
import cn.mhook.npatch.AxmlPatch;
import cn.mhook.npatch.IProgress;

/**
 * 应用保护 · 加固管线（P1 骨架）。
 *
 * 阶段：解析 → 各防护引擎（P1 未接入，仅记录）→ 重打包+对齐 → V2/V3 签名。
 * 真实可用：资源原样复制 + resources.arsc 4 字节对齐 + dex checksum 修复 + V2/V3 签名（内置 / 自有证书）。
 */
public final class ProtectEngine {

    private ProtectEngine() {
    }

    /** HWID 设备绑定开关（暂时关闭，待接入网络授权系统后再启用）。 */
    public static final boolean ENABLE_HWID = false;

    /** wbc 白盒化随机密钥。 */
    private static volatile byte[] WBC_KEY = null;

    /** DPT 元数据：keyHex|payloadCount，供同一 process() 内 manifest 写入。 */
    private static volatile String DPT_META = null;

    // 注入 helper dex 的随机类名（每次加固随机生成）
    private static String N_STRDEC, N_LOGKILL, N_FACTORY, N_CLOADER, N_DPT, N_GUARD, N_NOLEAK, N_SOLOADER, N_DATALOADER;

    private static final java.util.Random RND = new java.util.Random();

    private static String randSeg() {
        String cs = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder();
        sb.append(cs.charAt(RND.nextInt(26)));
        for (int i = 0; i < 7; i++) sb.append(cs.charAt(RND.nextInt(26)));
        return sb.toString();
    }

    /** 描述符 Lcn/mhook/x/Y; -> 点号类名 cn.mhook.x.Y */
    static String descToName(String desc) {
        return desc.substring(1, desc.length() - 1).replace('/', '.');
    }

    /** 已接入的真实引擎。 */
    private static boolean implemented(String key) {
        switch (key) {
            case "str": case "stripdbg": case "cfg": case "res": case "rename": case "arith": case "so_enc": case "data_enc": case "wbc":
            case "abi64": case "dedup": case "noleak": case "aes":
            case "antidbg": case "rasp": case "root": case "emu": case "frida":
            case "integrity": case "secure": case "sig":
            case "multiopen": case "a11y": case "clip": case "sslpin": case "accel":
            case "crashlog": case "mem": case "antihook": case "so_obf": case "so_sig": case "fullmem":
            case "slimupdate": case "vmp":
            case "dpt":
                return true;
            default:
                return false;
        }
    }

    public static File process(Context ctx, File apk, ProtectConfig cfg, IProgress prog) throws Exception {
        File work = new File(ctx.getCacheDir(), "protect");
        work.mkdirs();
        DPT_META = null;
        {
            String rp = "L" + randSeg() + "/" + randSeg() + "/";
            N_STRDEC = rp + randSeg() + ";";
            N_LOGKILL = rp + randSeg() + ";";
            N_FACTORY = rp + randSeg() + ";";
            N_CLOADER = rp + randSeg() + ";";
            N_DPT = rp + randSeg() + ";";
            N_GUARD = rp + randSeg() + ";";
            N_NOLEAK = rp + randSeg() + ";";
            N_SOLOADER = rp + randSeg() + ";";
            N_DATALOADER = rp + randSeg() + ";";
            DexAssetOpen.LOADER_CLASS = N_DATALOADER;
            DexStringEncrypt.STRDEC_CLASS = N_STRDEC;
            DexLogKill.KILL_CLASS = N_LOGKILL;
            DexNoLeak.NOLEAK_CLASS = N_NOLEAK;
        }
        DexStringEncrypt.resetKey();
        WBC_KEY = null;
        if (cfg.on("wbc")) {
            byte[] wk = new byte[32];
            new java.security.SecureRandom().nextBytes(wk);
            DexStringEncrypt.setKey(wk);
            WBC_KEY = wk;
        }
        if (prog == null) prog = s -> {
        };

        prog.log("① 解析 APK ...");
        String pkg = "app";
        try (ZipFile zip = new ZipFile(apk)) {
            byte[] mf = readEntry(zip, "AndroidManifest.xml");
            if (mf != null) {
                String p = safe(() -> AxmlPatch.findPackageName(mf));
                String a = safe(() -> AxmlPatch.findApplicationName(mf));
                if (p != null && !p.isEmpty()) pkg = p;
                prog.log("  包名: " + p + (a != null ? "  Application: " + a : ""));
            }
        }

        String[][] phases = {
                {"② 代码混淆", "str,cfg,rename,res,arith,stripdbg"},
                {"③ 抽取 / 加密", "dpt,aes"},
                {"④ VMP 虚拟化", "vmp"},
                {"⑤ SO 加固", "so_enc,so_obf,so_sig"},
                {"⑥ 运行时防护", "antidbg,rasp,root,emu,frida,antihook,multiopen,accel,a11y,mem,fullmem,sig,integrity,secure"},
                {"⑦ 数据 / 通信", "data_enc,noleak,sslpin,wbc,clip"},
        };
        int skipped = 0, total = 0;
        for (String[] ph : phases) {
            int on = 0, unimpl = 0;
            for (String k : ph[1].split(",")) if (cfg.on(k)) {
                on++;
                total++;
                if (!implemented(k)) {
                    skipped++;
                    unimpl++;
                }
            }
            if (on > 0) prog.log(ph[0] + "：启用 " + on + " 项" + (unimpl > 0 ? "（" + unimpl + " 项引擎未接入，跳过）" : ""));
        }

        File source = apk;

        // ① 类名/方法/字段混淆（必须在字符串加密之前：反射保护规则要基于明文字符串）
        if (cfg.on("rename")) {
            prog.log("① 类名/方法/字段混淆（rename）...");
            source = renameApk(ctx, source, work, cfg, prog, pkg);
        }

        // ② SO 加壳（必须在字符串加密之前：此时 loadLibrary 的参数还是明文字符串，才能被识别）
        if (cfg.on("so_enc")) {
            prog.log("② SO 加壳（so_enc）...");
            source = soEncApk(ctx, source, work, cfg, prog);
        } else if (cfg.on("dpt") || cfg.on("aes")) {
            // DPT/AES 用内存加载器时，需让 loadLibrary 能解析到 App 的 native 库
            prog.log("② 内存加载器 native 支持（loadLibrary 重定向）...");
            source = injectSoLoader(ctx, source, work, prog);
        }

        // ③ 代码混淆（真实）：先移除日志 → 再字符串加密 + 控制流
        if (cfg.on("str") || cfg.on("stripdbg") || cfg.on("cfg") || cfg.on("res")) {
            prog.log("③ 代码混淆：" + (cfg.on("stripdbg") ? "移除日志 " : "")
                    + (cfg.on("str") ? "字符串加密 " : "") + (cfg.on("cfg") ? "控制流混淆 " : "")
                    + (cfg.on("res") ? "资源混淆 " : ""));
            source = obfuscateApk(ctx, source, work, cfg, prog);
        }

        // ④ 资源/配置文件加密
        if (cfg.on("data_enc")) {
            prog.log("④ 资源加密（data_enc）...");
            source = dataEncApk(ctx, source, work, cfg, prog);
        }

        // ⑤ Dex 虚拟化
        if (cfg.on("vmp")) {
            prog.log("⑤ 虚拟化（vmp）...");
            source = vmpApk(ctx, source, work, cfg, prog);
        }

        // ⑥ DPT 方法/类抽取 / AES 整包加密：抽取类移出主 dex，加密为 payload，运行时还原
        if (cfg.on("dpt") || cfg.on("aes")) {
            prog.log(cfg.on("aes") ? "⑥ 整包 DEX 加密（AES）..." : "⑥ 方法抽取（DPT）...");
            source = dptApk(ctx, source, work, cfg, prog, pkg, cfg.on("aes"));
        }

        // 预构建签名 key（供运行时签名校验嵌入证书指纹 + ⑨ 复用）
        File builtinCert = new File(ctx.getFilesDir(), "mhook_cert.der");
        File builtinKey = new File(ctx.getFilesDir(), "mhook_key.pk8");
        if (cfg.sign.mode == SignConfig.MODE_BUILTIN && (!builtinCert.exists() || !builtinKey.exists())) {
            releaseAsset(ctx, "mhook_cert.der", builtinCert);
            releaseAsset(ctx, "mhook_key.pk8", builtinKey);
        }
        SignatureKey key = null;
        try {
            key = cfg.sign.buildKey(builtinCert, builtinKey);
        } catch (Throwable t) {
            prog.log("  构建签名密钥失败: " + t);
        }

        // ⑦ 运行时防护（RASP）：注入 Guard Provider（root/模拟器/Frida/调试/完整性/防截屏/签名校验）
        byte[] newManifest = null;
        byte[] guardDex = null;
        java.util.Map<String, byte[]> extraAssets = null;
        int gflags = 0;
        if (cfg.on("antidbg")) gflags |= 8;
        if (cfg.on("root")) gflags |= 1;
        if (cfg.on("emu")) gflags |= 2;
        if (cfg.on("frida")) gflags |= 4;
        if (cfg.on("rasp")) gflags |= 15;
        if (cfg.on("integrity")) gflags |= 16;
        if (cfg.on("secure")) gflags |= 32;
        if (cfg.on("sig")) gflags |= 64;
        if (cfg.on("multiopen")) gflags |= 128;
        if (cfg.on("a11y")) gflags |= 256;
        if (cfg.on("clip")) gflags |= 512;
        if (cfg.on("sslpin")) gflags |= 1024;
        if (cfg.on("accel")) gflags |= 2048;
        if (cfg.on("crashlog")) gflags |= 4096;
        if (cfg.on("mem")) gflags |= 8192;
        if (cfg.on("antihook")) gflags |= 16384;
        if (cfg.on("so_sig")) gflags |= 65536;
        if (cfg.on("fullmem")) gflags |= 131072;
        String hwidList = "";
        if (ENABLE_HWID && cfg.on("hwid")) {
            hwidList = normalizeHwidList(cfg.texts.get("hwidList"));
            if (!hwidList.isEmpty()) gflags |= 32768;
            prog.log("  本机 HWID（可加入白名单）: " + deviceId());
        }
        boolean needDpt = (cfg.on("dpt") || cfg.on("aes")) && DPT_META != null;
        if (gflags != 0 || needDpt) {
            File gf = new File(work, "guard.dex");
            releaseAsset(ctx, "protect/guard.dex", gf);
            if (gf.exists() && gf.length() > 0) {
                try {
                    java.util.Map<String, String> m = new java.util.HashMap<>();
                    m.put("Lcn/mhook/protect/rt/GuardProvider;", N_GUARD);
                    guardDex = DexRename.rename(readFile(gf), m, work);
                } catch (Throwable t) {
                    guardDex = readFile(gf);
                }
                String sigSha = "";
                if ((gflags & 64) != 0 && key != null) {
                    try {
                        sigSha = sha256Hex(key.getCertificate().getEncoded());
                    } catch (Throwable ignored) {
                    }
                }
                String dexSha = "";
                try (ZipFile zip = new ZipFile(source)) {
                    byte[] cd = readEntry(zip, "classes.dex");
                    if (cd != null) dexSha = sha256Hex(cd);
                } catch (Throwable ignored) {
                }
                int action = "退出".equals(cfg.choices.get("onHit")) ? 0 : 1;
                extraAssets = new java.util.HashMap<>();
                extraAssets.put("assets/mhook_protect/guard.cfg",
                        (gflags + ":" + action + ":" + sigSha + ":" + dexSha).getBytes("UTF-8"));
                if (gflags != 0) {
                    File rf1 = new File(work, "librasp_arm64.so");
                    releaseAsset(ctx, "protect/rasp/arm64-v8a/librasp.so", rf1);
                    if (rf1.exists()) extraAssets.put("lib/arm64-v8a/librasp.so", readFile(rf1));
                    File rf2 = new File(work, "librasp_x86_64.so");
                    releaseAsset(ctx, "protect/rasp/x86_64/librasp.so", rf2);
                    if (rf2.exists()) extraAssets.put("lib/x86_64/librasp.so", readFile(rf2));
                }
                if ((gflags & 32768) != 0 && !hwidList.isEmpty()) {
                    extraAssets.put("assets/mhook_protect/hwid.cfg", hwidList.getBytes("UTF-8"));
                }
                try (ZipFile zip = new ZipFile(source)) {
                    byte[] mf = readEntry(zip, "AndroidManifest.xml");
                    if (mf != null) {
                        if (needDpt) {
                            mf = AxmlLib.setAppComponentFactory(mf, descToName(N_FACTORY));
                        }
                        newManifest = AxmlLib.addProvider(mf, descToName(N_GUARD),
                                pkg + ".mhook.guard", false);
                    }
                }
                prog.log("⑦ 运行时防护：注入 Guard（flags=" + gflags + "）");
            } else {
                prog.log("  运行时防护：guard.dex 缺失，跳过");
            }
        }

        // wbc：写入掩码化随机密钥
        if (WBC_KEY != null) {
            if (extraAssets == null) extraAssets = new java.util.HashMap<>();
            byte[] ob = new byte[WBC_KEY.length];
            for (int i = 0; i < ob.length; i++) ob[i] = (byte) (WBC_KEY[i] ^ 0x5A);
            extraAssets.put("assets/mhook_protect/str.key", ob);
        }

        prog.log("⑧ 重打包 / 对齐 ...");
        File unsigned = new File(work, "protect_unsigned.apk");
        if (unsigned.exists()) unsigned.delete();
        repackAndAlign(source, unsigned, newManifest, guardDex, extraAssets,
                cfg.on("abi64"), cfg.on("dedup"), cfg.on("mem"), cfg.on("so_obf"), cfg.on("so_sig"),
                cfg.on("slimupdate"), prog);
        prog.log("  对齐完成: " + (unsigned.length() / 1024 / 1024) + " MB");

        prog.log("⑨ 签名 ...");
        File out = outputFile(ctx, cfg, apk.getName());
        prog.log("  签名方式: " + modeName(cfg.sign.mode) + "  方案: " + schemeName(cfg.sign));
        if (key == null) {
            key = cfg.sign.buildKey(builtinCert, builtinKey);
        }
        copyFile(unsigned, out);
        V2V3SchemeSigner.sign(out, key, true, true);
        unsigned.delete();

        prog.log("完成: " + out.getAbsolutePath() + " (" + (out.length() / 1024 / 1024) + " MB)");
        if (skipped > 0) {
            prog.log("提示: " + skipped + "/" + total + " 项防护引擎尚未接入，本次仅完成重打包+签名。");
        }
        return out;
    }

    interface Throwing<T> {
        T get() throws Exception;
    }

    private static <T> T safe(Throwing<T> t) {
        try {
            return t.get();
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------------- 代码混淆：字符串加密 + 移除日志 ----------------
    private static File obfuscateApk(Context ctx, File inApk, File work, ProtectConfig cfg, IProgress prog) throws Exception {
        boolean enc = cfg.on("str");
        boolean log = cfg.on("stripdbg");
        boolean noleak = cfg.on("noleak");
        byte[] strDec = null, logKill = null, noleakDex = null;
        if (enc) {
            File f = new File(work, "strdec.dex");
            releaseAsset(ctx, "protect/strdec.dex", f);
            if (f.exists() && f.length() > 0) {
                try {
                    java.util.Map<String, String> m = new java.util.HashMap<>();
                    m.put("Lcn/mhook/protect/rt/StrDec;", N_STRDEC);
                    strDec = DexRename.rename(readFile(f), m, work);
                } catch (Throwable t) {
                    strDec = readFile(f);
                }
            } else prog.log("  解密器缺失（assets/protect/strdec.dex），字符串加密跳过");
        }
        if (log) {
            File f = new File(work, "logkill.dex");
            releaseAsset(ctx, "protect/logkill.dex", f);
            if (f.exists() && f.length() > 0) {
                try {
                    java.util.Map<String, String> m = new java.util.HashMap<>();
                    m.put("Lcn/mhook/protect/rt/LogKill;", N_LOGKILL);
                    logKill = DexRename.rename(readFile(f), m, work);
                } catch (Throwable t) {
                    logKill = readFile(f);
                }
            } else prog.log("  空日志类缺失（assets/protect/logkill.dex），移除日志跳过");
        }
        if (noleak) {
            File f = new File(work, "noleak.dex");
            releaseAsset(ctx, "protect/noleak.dex", f);
            if (f.exists() && f.length() > 0) {
                try {
                    java.util.Map<String, String> m = new java.util.HashMap<>();
                    m.put("Lcn/mhook/protect/rt/NoLeak;", N_NOLEAK);
                    noleakDex = DexRename.rename(readFile(f), m, work);
                } catch (Throwable t) {
                    noleakDex = readFile(f);
                }
            } else prog.log("  防泄漏类缺失（assets/protect/noleak.dex），日志防泄漏跳过");
        }
        File out = new File(work, "protect_obf.apk");
        int maxN = 1;
        java.util.Set<String> zipNames = new java.util.HashSet<>();
        try (ZipFile zip = new ZipFile(inApk)) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                zipNames.add(n);
                int idx = dexClassIndex(n);
                if (idx > maxN) maxN = idx;
            }
        }
        int encCount = 0, logCount = 0, cfCount = 0, resCount = 0, nlkCount = 0;
        byte[] arsc = null;
        java.util.Map<String, String> resRename = null;
        if (cfg.on("res")) {
            try (ZipFile zip = new ZipFile(inApk)) {
                byte[] a = readEntry(zip, "resources.arsc");
                if (a != null) {
                    ResObfuscate.Result rr = ResObfuscate.process(a, zipNames);
                    if (rr != null) {
                        arsc = rr.arsc;
                        resRename = rr.rename;
                        resCount = rr.rename.size();
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        try (ZipFile zip = new ZipFile(inApk);
             CountingOutputStream counting = new CountingOutputStream(new FileOutputStream(out));
             ZipOutputStream zout = new ZipOutputStream(counting)) {
            byte[] buf = new byte[65536];
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                String lower = name.toLowerCase();
                if (lower.startsWith("meta-inf/") && (lower.endsWith(".sf") || lower.endsWith(".rsa")
                        || lower.endsWith(".dsa") || lower.endsWith(".ec"))) {
                    continue;
                }
                byte[] data = null;
                if (dexClassIndex(name) > 0) {
                    byte[] nd = readAll(zip, ze);
                    boolean changed = false;
                    // 1) 先移除日志
                    if (log && logKill != null) {
                        try {
                            int[] c = new int[]{0};
                            byte[] r = DexLogKill.rewriteDex(nd, c);
                            if (r != null) { nd = r; logCount += c[0]; changed = true; }
                        } catch (Throwable t) {
                            prog.log("  移除日志失败(" + name + "): " + t);
                            android.util.Log.e("ProtectEngine", "logkill " + name, t);
                        }
                    }
                    // 1.5) 日志防泄漏
                    if (noleak && noleakDex != null) {
                        try {
                            int[] c = new int[]{0};
                            byte[] r = DexNoLeak.rewriteDex(nd, c);
                            if (r != null) { nd = r; nlkCount += c[0]; changed = true; }
                        } catch (Throwable t) {
                            android.util.Log.e("ProtectEngine", "noleak " + name, t);
                        }
                    }
                    // 2) 再字符串加密 + 控制流 + 算术混淆
                    boolean encOk = enc && strDec != null;
                    if (encOk || cfg.on("cfg") || cfg.on("arith")) {
                        try {
                            int[] ec = new int[]{0}, cc = new int[]{0};
                            byte[] r = DexStringEncrypt.obfuscateDex(nd, encOk, cfg.on("cfg"), cfg.on("arith"),
                                    "全部".equals(cfg.choices.get("obfScope")),
                                    cfg.texts.get("obfInclude"), cfg.texts.get("exclude"), ec, cc);
                            if (r != null) { nd = r; encCount += ec[0]; cfCount += cc[0]; changed = true; }
                        } catch (Throwable t) {
                            prog.log("  字符串/控制流混淆失败(" + name + "): " + t);
                            android.util.Log.e("ProtectEngine", "obf " + name, t);
                        }
                    }
                    if (changed) data = nd;
                }
                boolean isArsc = "resources.arsc".equals(name);
                if (isArsc && arsc != null) data = arsc;
                String writeName = (resRename != null && resRename.containsKey(name)) ? resRename.get(name) : name;
                ZipEntry ne = new ZipEntry(writeName);
                if (isArsc && data != null) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(data.length);
                    ne.setCompressedSize(data.length);
                    CRC32 c = new CRC32();
                    c.update(data);
                    ne.setCrc(c.getValue());
                } else if (data != null) {
                    ne.setMethod(ZipEntry.DEFLATED);
                } else if (ze.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(ze.getSize());
                    ne.setCompressedSize(ze.getCompressedSize());
                    ne.setCrc(ze.getCrc());
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                alignStoreEntry(counting.count(), ne, name);
                zout.putNextEntry(ne);
                if (data != null) {
                    zout.write(data);
                } else {
                    try (InputStream is = zip.getInputStream(ze)) {
                        int n;
                        while ((n = is.read(buf)) != -1) zout.write(buf, 0, n);
                    }
                }
                zout.closeEntry();
            }
            if (strDec != null && encCount > 0) {
                String n = "classes" + (++maxN) + ".dex";
                writeEntry(zout, n, strDec);
                prog.log("  注入解密器 " + n + " (" + (strDec.length / 1024) + " KB)");
            }
            if (noleakDex != null && nlkCount > 0) {
                String n = "classes" + (++maxN) + ".dex";
                writeEntry(zout, n, noleakDex);
                if (prog != null) prog.log("  注入防泄漏类 " + n + " (" + (noleakDex.length / 1024) + " KB)");
            }
            if (logKill != null && logCount > 0) {
                String n = "classes" + (++maxN) + ".dex";
                writeEntry(zout, n, logKill);
                prog.log("  注入空日志类 " + n + " (" + (logKill.length / 1024) + " KB)");
            }
        }
        android.util.Log.i("ProtectEngine", "obf flags: str=" + enc + " strDec=" + (strDec != null)
                + " stripdbg=" + log + " logKill=" + (logKill != null)
                + " cfg=" + cfg.on("cfg") + " res=" + cfg.on("res")
                + " => encCount=" + encCount + " logCount=" + logCount + " cfCount=" + cfCount + " resCount=" + resCount);
        if (prog != null) {
            if (enc) prog.log("  字符串加密：共 " + encCount + " 处常量");
            if (log) prog.log("  移除日志：重定向 " + logCount + " 处引用");
            if (cfg.on("cfg")) prog.log("  控制流混淆：处理 " + cfCount + " 个方法");
            if (cfg.on("res")) prog.log("  资源混淆：重命名 " + resCount + " 个资源文件");
        }
        return out;
    }

    private static void writeEntry(ZipOutputStream zout, String name, byte[] data) throws IOException {
        ZipEntry ne = new ZipEntry(name);
        ne.setMethod(ZipEntry.DEFLATED);
        zout.putNextEntry(ne);
        zout.write(data);
        zout.closeEntry();
    }

    private static int dexClassIndex(String name) {
        if (!name.endsWith(".dex") || !name.startsWith("classes")) return 0;
        String mid = name.substring(7, name.length() - 4);
        if (mid.isEmpty()) return 1;
        try {
            return Integer.parseInt(mid);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 解析 HWID 白名单（64 位十六进制，逗号/空白/分号分隔）。 */
    static String normalizeHwidList(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : raw.split("[,\\s;]+")) {
            t = t.trim().toLowerCase();
            if (t.length() == 64 && t.matches("[0-9a-f]+")) {
                if (sb.length() > 0) sb.append(',');
                sb.append(t);
            }
        }
        return sb.toString();
    }

    /** DPT 主密钥（^0xA5 混淆），与 libdpt.so 内一致。 */
    private static final byte[] DPT_MASTER_OB = {
            (byte) 0xc8, (byte) 0xed, (byte) 0xca, (byte) 0xca, (byte) 0xce, (byte) 0xe1, (byte) 0xd5, (byte) 0xd1,
            (byte) 0xee, (byte) 0xc0, (byte) 0xdc, (byte) 0x94, (byte) 0xf1, (byte) 0xca, (byte) 0x96, (byte) 0x97,
            (byte) 0x9c, (byte) 0xc4, (byte) 0xc7, (byte) 0xc6, (byte) 0xc1, (byte) 0xc0, (byte) 0xc3, (byte) 0xc2,
            (byte) 0xcd, (byte) 0xcc, (byte) 0xcf, (byte) 0xce, (byte) 0xc9, (byte) 0xc8, (byte) 0xcb, (byte) 0xca
    };

    /** key = SHA256(master || salt)[0..16]，与 libdpt.so 的 DptKey.nativeKey 一致。 */
    static byte[] deriveDptKey(byte[] salt) {
        try {
            byte[] m = new byte[32];
            for (int i = 0; i < 32; i++) m[i] = (byte) (DPT_MASTER_OB[i] ^ 0xA5);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(m);
            md.update(salt);
            return java.util.Arrays.copyOf(md.digest(), 16);
        } catch (Exception e) {
            return new byte[16];
        }
    }

    /** vmp 程序包加密：salt(16) + (bundle XOR keystream)，keystream=SHA256(key||ctr)。 */
    static byte[] vmpEncrypt(byte[] salt, byte[] plain) {
        try {
            byte[] key = deriveDptKey(salt);
            byte[] out = new byte[16 + plain.length];
            System.arraycopy(salt, 0, out, 0, 16);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            int off = 0, ctr = 0;
            while (off < plain.length) {
                md.reset();
                md.update(key);
                md.update(new byte[]{(byte) ctr, (byte) (ctr >> 8), (byte) (ctr >> 16), (byte) (ctr >> 24)});
                byte[] b = md.digest();
                int c = Math.min(32, plain.length - off);
                for (int i = 0; i < c; i++) out[16 + off + i] = (byte) (plain[off + i] ^ b[i]);
                off += c;
                ctr++;
            }
            return out;
        } catch (Exception e) {
            byte[] out = new byte[16 + plain.length];
            System.arraycopy(salt, 0, out, 0, 16);
            System.arraycopy(plain, 0, out, 16, plain.length);
            return out;
        }
    }

    /** 设备指纹（与 Guard 端算法一致）。 */
    static String deviceId() {
        try {
            String s = Build.MANUFACTURER + "|" + Build.MODEL + "|" + Build.BRAND + "|"
                    + Build.DEVICE + "|" + Build.HARDWARE + "|" + Build.FINGERPRINT;
            return sha256Hex(s.getBytes("UTF-8"));
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256Hex(byte[] b) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // ---------------- DPT 方法/类抽取 ----------------
    private static File dptApk(Context ctx, File inApk, File work, ProtectConfig cfg, IProgress prog, String pkg,
                               boolean encryptAll) throws Exception {
        File f = new File(work, "dpt.dex");
        releaseAsset(ctx, "protect/dpt.dex", f);
        byte[] dptDex = null;
        if (f.exists() && f.length() > 0) {
            try {
                java.util.Map<String, String> m = new java.util.HashMap<>();
                m.put("Lcn/mhook/protect/rt/ProtectFactory;", N_FACTORY);
                m.put("Lcn/mhook/protect/rt/ProtectClassLoader;", N_CLOADER);
                m.put("Lcn/mhook/protect/rt/DptPayload;", N_DPT);
                dptDex = DexRename.rename(readFile(f), m, work);
            } catch (Throwable t) {
                dptDex = readFile(f);
            }
        }
        if (dptDex == null) {
            prog.log("  DPT：dpt.dex 缺失，跳过");
            return inApk;
        }
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        StringBuilder keyHex = new StringBuilder();
        for (byte b : salt) keyHex.append(String.format("%02x", b));
        byte[] key = deriveDptKey(salt);   // 密钥由 native 主密钥派生，APK 内不存密钥

        final String pkgPrefix = "L" + pkg.replace('.', '/') + "/";
        final boolean allScope = "全部".equals(cfg.choices.get("obfScope"));
        final String include = cfg.texts.get("obfInclude");
        final String exclude = cfg.texts.get("exclude");
        java.util.function.Predicate<String> extract;
        if (encryptAll) {
            // AES 整包：抽取除自身 rt 类以外的全部类
            extract = t -> t != null && t.startsWith("L") && !t.startsWith("Lcn/mhook/protect/");
        } else {
            extract = t -> {
                if (t == null || !t.startsWith(pkgPrefix)) return false;
                if (t.startsWith("Lcn/mhook/protect/")) return false;
                String cls = t.substring(1, t.length() - 1).replace('/', '.');
                if (!allScope && !matchPrefix(cls, include)) return false;
                if (matchPrefix(cls, exclude)) return false;
                return true;
            };
        }

        String origAf = "";
        try (ZipFile zip = new ZipFile(inApk)) {
            byte[] mf = readEntry(zip, "AndroidManifest.xml");
            if (mf != null) {
                String v = AxmlLib.getAppComponentFactory(mf);
                if (v != null) origAf = v;
            }
        } catch (Throwable ignored) {
        }

        File out = new File(work, "protect_dpt.apk");
        int maxN = 1;
        try (ZipFile zip = new ZipFile(inApk)) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                int idx = dexClassIndex(e.nextElement().getName());
                if (idx > maxN) maxN = idx;
            }
        }
        int payloadCount = 0, totalEx = 0;
        boolean classesDexWritten = false;
        try (ZipFile zip = new ZipFile(inApk);
             CountingOutputStream counting = new CountingOutputStream(new FileOutputStream(out));
             ZipOutputStream zout = new ZipOutputStream(counting)) {
            byte[] buf = new byte[65536];
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                String lower = name.toLowerCase();
                if (lower.startsWith("meta-inf/") && (lower.endsWith(".sf") || lower.endsWith(".rsa")
                        || lower.endsWith(".dsa") || lower.endsWith(".ec"))) {
                    continue;
                }
                if (dexClassIndex(name) > 0) {
                    byte[] nd = readAll(zip, ze);
                    DexClassSplit.Result r = DexClassSplit.split(nd, extract, work);
                    if (r.payload != null && r.extracted > 0) {
                        writeEntry(zout, "assets/mhook_protect/payload" + payloadCount + ".dex", encrypt(key, r.payload));
                        payloadCount++;
                        totalEx += r.extracted;
                        if (r.keep != null && r.kept > 0) {
                            writeEntry(zout, name, r.keep);
                            if ("classes.dex".equals(name)) classesDexWritten = true;
                        }
                        // keep 为空：该 dex 全被抽取。primary 需非空（稍后用 dpt.dex 顶替），
                        // 非 primary 的空 dex 丢弃不写。
                        continue;
                    }
                    writeEntry(zout, name, nd);
                    if ("classes.dex".equals(name)) classesDexWritten = true;
                    continue;
                }
                ZipEntry ne = new ZipEntry(name);
                if (ze.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(ze.getSize());
                    ne.setCompressedSize(ze.getCompressedSize());
                    ne.setCrc(ze.getCrc());
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                alignStoreEntry(counting.count(), ne, name);
                zout.putNextEntry(ne);
                try (InputStream is = zip.getInputStream(ze)) {
                    int n;
                    while ((n = is.read(buf)) != -1) zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
            if (payloadCount > 0) {
                if (!classesDexWritten) {
                    // 主 dex 被抽空 → 用 dpt.dex 充当 classes.dex（非空且包名独立，避免跨加载器访问）
                    writeEntry(zout, "classes.dex", dptDex);
                } else {
                    writeEntry(zout, "classes" + (++maxN) + ".dex", dptDex);
                }
                writeEntry(zout, "assets/mhook_protect/dpt.cfg",
                        (keyHex + "|" + payloadCount + "|" + origAf).getBytes("UTF-8"));
                File lf1 = new File(work, "libdpt_arm64.so");
                releaseAsset(ctx, "protect/dpt/arm64-v8a/libdpt.so", lf1);
                if (lf1.exists()) writeEntry(zout, "lib/arm64-v8a/libdpt.so", readFile(lf1));
                File lf2 = new File(work, "libdpt_x86_64.so");
                releaseAsset(ctx, "protect/dpt/x86_64/libdpt.so", lf2);
                if (lf2.exists()) writeEntry(zout, "lib/x86_64/libdpt.so", readFile(lf2));
            }
        }
        if (payloadCount == 0) {
            out.delete();
            prog.log("  DPT：无可抽取类，跳过");
            return inApk;
        }
        DPT_META = keyHex + "|" + payloadCount;
        prog.log("  抽取 " + totalEx + " 个类 → " + payloadCount + " 个加密 payload，注入 dpt.dex");
        return out;
    }

    // ---------------- vmp：Dex 虚拟化 ----------------
    private static File vmpApk(Context ctx, File inApk, File work, ProtectConfig cfg, IProgress prog) throws Exception {
        File vf = new File(work, "vmp.dex");
        releaseAsset(ctx, "protect/vmp.dex", vf);
        if (!vf.exists() || vf.length() == 0) {
            prog.log("  vmp：vmp.dex 缺失，跳过");
            return inApk;
        }
        byte[] vmpDex = readFile(vf);
        byte[] soArm = null, soX86 = null;
        File f1 = new File(work, "libvmp_arm64.so");
        releaseAsset(ctx, "protect/vmp/arm64-v8a/libvmp.so", f1);
        if (f1.exists()) soArm = readFile(f1);
        File f2 = new File(work, "libvmp_x86_64.so");
        releaseAsset(ctx, "protect/vmp/x86_64/libvmp.so", f2);
        if (f2.exists()) soX86 = readFile(f2);

        final boolean allScope = "全部".equals(cfg.choices.get("obfScope"));
        final String include = cfg.texts.get("obfInclude");
        final String exclude = cfg.texts.get("exclude");
        File out = new File(work, "protect_vmp.apk");
        int maxN = 1;
        try (ZipFile zip = new ZipFile(inApk)) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                int idx = dexClassIndex(e.nextElement().getName());
                if (idx > maxN) maxN = idx;
            }
        }
        int total = 0;
        try (ZipFile zip = new ZipFile(inApk);
             CountingOutputStream counting = new CountingOutputStream(new FileOutputStream(out));
             ZipOutputStream zout = new ZipOutputStream(counting)) {
            byte[] buf = new byte[65536];
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                String lower = name.toLowerCase();
                if (lower.startsWith("meta-inf/") && (lower.endsWith(".sf") || lower.endsWith(".rsa")
                        || lower.endsWith(".dsa") || lower.endsWith(".ec"))) {
                    continue;
                }
                if (dexClassIndex(name) > 0) {
                    byte[] nd = readAll(zip, ze);
                    try {
                        int[] c = new int[]{0};
                        String bkey = "b" + dexClassIndex(name);
                        DexVmp.Result r = DexVmp.rewriteDex(nd, allScope, include, exclude, bkey, c);
                        if (r != null && r.count > 0) {
                            nd = r.dex;
                            total += r.count;
                            byte[] vsalt = new byte[16];
                            new java.security.SecureRandom().nextBytes(vsalt);
                            writeEntry(zout, "assets/mhook_protect/vmp/" + bkey, vmpEncrypt(vsalt, r.bundle));
                        }
                    } catch (Throwable t) {
                        android.util.Log.e("ProtectEngine", "vmp " + name, t);
                    }
                    writeEntry(zout, name, nd);
                    continue;
                }
                ZipEntry ne = new ZipEntry(name);
                if (ze.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(ze.getSize());
                    ne.setCompressedSize(ze.getCompressedSize());
                    ne.setCrc(ze.getCrc());
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                alignStoreEntry(counting.count(), ne, name);
                zout.putNextEntry(ne);
                try (InputStream is = zip.getInputStream(ze)) {
                    int n;
                    while ((n = is.read(buf)) != -1) zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
            if (total > 0) {
                writeEntry(zout, "classes" + (++maxN) + ".dex", vmpDex);
                if (soArm != null) writeEntry(zout, "lib/arm64-v8a/libvmp.so", soArm);
                if (soX86 != null && !cfg.on("abi64")) writeEntry(zout, "lib/x86_64/libvmp.so", soX86);
            }
        }
        if (total == 0) {
            out.delete();
            prog.log("  vmp：无可虚拟化方法，跳过");
            return inApk;
        }
        prog.log("  vmp：虚拟化 " + total + " 个方法，注入 libvmp.so");
        return out;
    }

    // ---------------- data_enc：资源/配置文件加密 ----------------
    private static final java.util.Set<String> DATA_EXT = new java.util.HashSet<>(java.util.Arrays.asList(
            "json", "xml", "dat", "db", "bin", "cfg", "ini", "conf", "properties", "txt", "csv", "yaml", "yml"));

    private static File dataEncApk(Context ctx, File inApk, File work, ProtectConfig cfg, IProgress prog) throws Exception {
        File lf = new File(work, "dataloader.dex");
        releaseAsset(ctx, "protect/dataloader.dex", lf);
        byte[] loader = null;
        if (lf.exists() && lf.length() > 0) {
            try {
                java.util.Map<String, String> m = new java.util.HashMap<>();
                m.put("Lcn/mhook/protect/rt/DataLoader;", N_DATALOADER);
                loader = DexRename.rename(readFile(lf), m, work);
            } catch (Throwable t) {
                loader = readFile(lf);
            }
        }
        if (loader == null) {
            prog.log("  data_enc：dataloader.dex 缺失，跳过");
            return inApk;
        }
        java.util.Set<String> cands = new java.util.HashSet<>();
        try (ZipFile zip = new ZipFile(inApk)) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                if (!n.startsWith("assets/") || n.startsWith("assets/mhook_protect/")) continue;
                int dot = n.lastIndexOf('.');
                if (dot < 0) continue;
                String ext = n.substring(dot + 1).toLowerCase();
                if (DATA_EXT.contains(ext)) cands.add(n);
            }
        }
        if (cands.isEmpty()) {
            prog.log("  data_enc：无可加密资源，跳过");
            return inApk;
        }
        byte[] key = new byte[16];
        new java.security.SecureRandom().nextBytes(key);
        StringBuilder keyHex = new StringBuilder();
        for (byte x : key) keyHex.append(String.format("%02x", x));
        File out = new File(work, "protect_data.apk");
        int maxN = 1;
        try (ZipFile zip = new ZipFile(inApk)) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                int idx = dexClassIndex(e.nextElement().getName());
                if (idx > maxN) maxN = idx;
            }
        }
        int rewrites = 0, encCount = 0;
        try (ZipFile zip = new ZipFile(inApk);
             CountingOutputStream counting = new CountingOutputStream(new FileOutputStream(out));
             ZipOutputStream zout = new ZipOutputStream(counting)) {
            byte[] buf = new byte[65536];
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                String lower = name.toLowerCase();
                if (lower.startsWith("meta-inf/") && (lower.endsWith(".sf") || lower.endsWith(".rsa")
                        || lower.endsWith(".dsa") || lower.endsWith(".ec"))) {
                    continue;
                }
                if (cands.contains(name)) {
                    writeEntry(zout, "assets/mhook_protect/data/" + name.substring("assets/".length()) + ".enc",
                            encrypt(key, readAll(zip, ze)));
                    encCount++;
                    continue;
                }
                if (dexClassIndex(name) > 0) {
                    byte[] nd = readAll(zip, ze);
                    try {
                        int[] c = new int[]{0};
                        byte[] r = DexAssetOpen.rewriteDex(nd, c);
                        if (r != null) { nd = r; rewrites += c[0]; }
                    } catch (Throwable t) {
                        android.util.Log.e("ProtectEngine", "dataenc " + name, t);
                    }
                    writeEntry(zout, name, nd);
                    continue;
                }
                ZipEntry ne = new ZipEntry(name);
                if (ze.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(ze.getSize());
                    ne.setCompressedSize(ze.getCompressedSize());
                    ne.setCrc(ze.getCrc());
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                alignStoreEntry(counting.count(), ne, name);
                zout.putNextEntry(ne);
                try (InputStream is = zip.getInputStream(ze)) {
                    int n;
                    while ((n = is.read(buf)) != -1) zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
            if (rewrites > 0) {
                writeEntry(zout, "assets/mhook_protect/data.cfg", keyHex.toString().getBytes("UTF-8"));
                writeEntry(zout, "classes" + (++maxN) + ".dex", loader);
            }
        }
        prog.log("  data_enc：加密 " + encCount + " 个资源，重定向 " + rewrites + " 处 open");
        return out;
    }

    // ---------------- so_enc：SO 加壳 ----------------
    private static File soEncApk(Context ctx, File inApk, File work, ProtectConfig cfg, IProgress prog) throws Exception {
        File lf = new File(work, "soloader.dex");
        releaseAsset(ctx, "protect/soloader.dex", lf);
        byte[] soloader = null;
        if (lf.exists() && lf.length() > 0) {
            try {
                java.util.Map<String, String> m = new java.util.HashMap<>();
                m.put("Lcn/mhook/protect/rt/SoLoader;", N_SOLOADER);
                soloader = DexRename.rename(readFile(lf), m, work);
            } catch (Throwable t) {
                soloader = readFile(lf);
            }
        }
        if (soloader == null) {
            prog.log("  SO 加壳：soloader.dex 缺失，跳过");
            return inApk;
        }
        java.util.Map<String, byte[]> libs = new java.util.LinkedHashMap<>();
        java.util.Set<String> neededNames = new java.util.HashSet<>();
        java.util.Set<String> strings = new java.util.HashSet<>();
        try (ZipFile zip = new ZipFile(inApk)) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String n = ze.getName();
                if (n.startsWith("lib/") && n.endsWith(".so")) {
                    byte[] b = readAll(zip, ze);
                    libs.put(n, b);
                    for (String dep : ElfStrip.needed(b)) neededNames.add(dep);
                }
            }
        }
        try (ZipFile zip = new ZipFile(inApk)) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (dexClassIndex(ze.getName()) == 0) continue;
                try {
                    org.jf.dexlib2.dexbacked.DexBackedDexFile d =
                            new org.jf.dexlib2.dexbacked.DexBackedDexFile(org.jf.dexlib2.Opcodes.getDefault(), readAll(zip, ze));
                    for (org.jf.dexlib2.dexbacked.reference.DexBackedStringReference sr : d.getStringReferences()) {
                        if (sr.getString() != null) strings.add(sr.getString());
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        java.util.Map<String, byte[]> cands = new java.util.LinkedHashMap<>();
        for (var en : libs.entrySet()) {
            String base = en.getKey().substring(en.getKey().lastIndexOf('/') + 1);   // libxxx.so
            if (neededNames.contains(base)) continue;
            String shortName = base.substring(3, base.length() - 3);                  // xxx
            if (!strings.contains(shortName) && !strings.contains(base)) continue;    // 只处理被 loadLibrary 引用的
            cands.put(en.getKey(), en.getValue());
        }
        if (cands.isEmpty()) {
            prog.log("  SO 加壳：无可加密的叶子库，跳过");
            return inApk;
        }
        byte[] key = new byte[16];
        new java.security.SecureRandom().nextBytes(key);
        StringBuilder keyHex = new StringBuilder();
        for (byte x : key) keyHex.append(String.format("%02x", x));

        File out = new File(work, "protect_so.apk");
        int maxN = 1;
        try (ZipFile zip = new ZipFile(inApk)) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                int idx = dexClassIndex(e.nextElement().getName());
                if (idx > maxN) maxN = idx;
            }
        }
        int rewrites = 0, encCount = 0;
        try (ZipFile zip = new ZipFile(inApk);
             CountingOutputStream counting = new CountingOutputStream(new FileOutputStream(out));
             ZipOutputStream zout = new ZipOutputStream(counting)) {
            byte[] buf = new byte[65536];
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                String lower = name.toLowerCase();
                if (lower.startsWith("meta-inf/") && (lower.endsWith(".sf") || lower.endsWith(".rsa")
                        || lower.endsWith(".dsa") || lower.endsWith(".ec"))) {
                    continue;
                }
                if (cands.containsKey(name)) {
                    String[] seg = name.split("/");
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    byte[] so = cands.get(name);
                    if (cfg.on("so_obf")) so = ElfStrip.strip(so);   // 加密前先剥离段头/符号
                    writeEntry(zout, "assets/mhook_protect/so/" + seg[1] + "/" + base + ".enc",
                            encrypt(key, so));
                    encCount++;
                    continue;
                }
                if (dexClassIndex(name) > 0) {
                    byte[] nd = readAll(zip, ze);
                    try {
                        int[] c = new int[]{0};
                        byte[] r = DexInvokeRedirect.rewriteDex(nd, "Ljava/lang/System;", "loadLibrary",
                                N_SOLOADER, "load", c);
                        if (r != null) { nd = r; rewrites += c[0]; }
                    } catch (Throwable t) {
                        android.util.Log.e("ProtectEngine", "soload " + name, t);
                    }
                    writeEntry(zout, name, nd);
                    continue;
                }
                ZipEntry ne = new ZipEntry(name);
                if (ze.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(ze.getSize());
                    ne.setCompressedSize(ze.getCompressedSize());
                    ne.setCrc(ze.getCrc());
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                alignStoreEntry(counting.count(), ne, name);
                zout.putNextEntry(ne);
                try (InputStream is = zip.getInputStream(ze)) {
                    int n;
                    while ((n = is.read(buf)) != -1) zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
            writeEntry(zout, "assets/mhook_protect/so.cfg", keyHex.toString().getBytes("UTF-8"));
            if (rewrites > 0) writeEntry(zout, "classes" + (++maxN) + ".dex", soloader);
        }
        prog.log("  SO 加壳：加密 " + encCount + " 个库，重定向 " + rewrites + " 处 loadLibrary");
        return out;
    }

    // ---------------- 内存加载器 native 支持（注入 SoLoader + 重定向 loadLibrary） ----------------
    private static File injectSoLoader(Context ctx, File inApk, File work, IProgress prog) throws Exception {
        File lf = new File(work, "soloader.dex");
        releaseAsset(ctx, "protect/soloader.dex", lf);
        byte[] soloader = null;
        if (lf.exists() && lf.length() > 0) {
            try {
                java.util.Map<String, String> m = new java.util.HashMap<>();
                m.put("Lcn/mhook/protect/rt/SoLoader;", N_SOLOADER);
                soloader = DexRename.rename(readFile(lf), m, work);
            } catch (Throwable t) {
                soloader = readFile(lf);
            }
        }
        if (soloader == null) {
            prog.log("  SoLoader 缺失，跳过");
            return inApk;
        }
        File out = new File(work, "protect_soload.apk");
        int maxN = 1;
        try (ZipFile zip = new ZipFile(inApk)) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                int idx = dexClassIndex(e.nextElement().getName());
                if (idx > maxN) maxN = idx;
            }
        }
        int rewrites = 0;
        try (ZipFile zip = new ZipFile(inApk);
             CountingOutputStream counting = new CountingOutputStream(new FileOutputStream(out));
             ZipOutputStream zout = new ZipOutputStream(counting)) {
            byte[] buf = new byte[65536];
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                String lower = name.toLowerCase();
                if (lower.startsWith("meta-inf/") && (lower.endsWith(".sf") || lower.endsWith(".rsa")
                        || lower.endsWith(".dsa") || lower.endsWith(".ec"))) {
                    continue;
                }
                if (dexClassIndex(name) > 0) {
                    byte[] nd = readAll(zip, ze);
                    try {
                        int[] c = new int[]{0};
                        byte[] r = DexInvokeRedirect.rewriteDex(nd, "Ljava/lang/System;", "loadLibrary",
                                N_SOLOADER, "load", c);
                        if (r != null) { nd = r; rewrites += c[0]; }
                    } catch (Throwable t) {
                        android.util.Log.e("ProtectEngine", "soload " + name, t);
                    }
                    writeEntry(zout, name, nd);
                    continue;
                }
                ZipEntry ne = new ZipEntry(name);
                if (ze.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(ze.getSize());
                    ne.setCompressedSize(ze.getCompressedSize());
                    ne.setCrc(ze.getCrc());
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                alignStoreEntry(counting.count(), ne, name);
                zout.putNextEntry(ne);
                try (InputStream is = zip.getInputStream(ze)) {
                    int n;
                    while ((n = is.read(buf)) != -1) zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
            if (rewrites > 0) writeEntry(zout, "classes" + (++maxN) + ".dex", soloader);
        }
        prog.log("  重定向 " + rewrites + " 处 loadLibrary（注入 SoLoader）");
        return out;
    }

    // ---------------- rename：类名混淆 ----------------
    private static boolean isFrameworkType(String t) {
        return t.startsWith("Landroid/") || t.startsWith("Ljava/") || t.startsWith("Ldalvik/")
                || t.startsWith("Lorg/") || t.startsWith("Lkotlin/") || t.startsWith("Lsun/")
                || t.startsWith("Ljavax/") || t.startsWith("Lcom/android/") || t.startsWith("Landroidx/");
    }

    private static String simpleName(String t) {
        return t.substring(t.lastIndexOf('/') + 1, t.length() - 1);
    }

    private static File renameApk(Context ctx, File inApk, File work, ProtectConfig cfg, IProgress prog, String pkg) throws Exception {
        java.util.Set<String> keepNames = new java.util.HashSet<>();
        try (ZipFile zip = new ZipFile(inApk)) {
            byte[] mf = readEntry(zip, "AndroidManifest.xml");
            if (mf != null) keepNames.addAll(AxmlLib.listAndroidNames(mf));
        }
        java.util.Map<String, String> superMap = new java.util.HashMap<>();
        java.util.Map<String, java.util.List<String>> ifaceMap = new java.util.HashMap<>();
        java.util.Set<String> hasNative = new java.util.HashSet<>();
        java.util.Set<String> enums = new java.util.HashSet<>();
        java.util.Set<String> strings = new java.util.HashSet<>();
        java.util.Map<String, java.util.List<String>> classMethods = new java.util.HashMap<>();
        java.util.Map<String, java.util.List<String>> classFields = new java.util.HashMap<>();
        try (ZipFile zip = new ZipFile(inApk)) {
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (dexClassIndex(ze.getName()) == 0) continue;
                org.jf.dexlib2.dexbacked.DexBackedDexFile d =
                        new org.jf.dexlib2.dexbacked.DexBackedDexFile(org.jf.dexlib2.Opcodes.getDefault(), readAll(zip, ze));
                for (org.jf.dexlib2.dexbacked.DexBackedClassDef cd : d.getClasses()) {
                    superMap.put(cd.getType(), cd.getSuperclass());
                    ifaceMap.put(cd.getType(), new java.util.ArrayList<>(cd.getInterfaces()));
                    if ((cd.getAccessFlags() & org.jf.dexlib2.AccessFlags.ENUM.getValue()) != 0) enums.add(cd.getType());
                    java.util.List<String> mn = new java.util.ArrayList<>();
                    java.util.List<String> fn = new java.util.ArrayList<>();
                    for (org.jf.dexlib2.iface.Method m : cd.getMethods()) {
                        if ((m.getAccessFlags() & org.jf.dexlib2.AccessFlags.NATIVE.getValue()) != 0)
                            hasNative.add(cd.getType());
                        mn.add(m.getName());
                    }
                    for (org.jf.dexlib2.iface.Field f : cd.getFields()) fn.add(f.getName());
                    classMethods.put(cd.getType(), mn);
                    classFields.put(cd.getType(), fn);
                }
                for (org.jf.dexlib2.dexbacked.reference.DexBackedStringReference sr : d.getStringReferences()) {
                    String s = sr.getString();
                    if (s != null) strings.add(s);
                }
            }
        }
        final boolean allScope = "全部".equals(cfg.choices.get("obfScope"));
        final String include = cfg.texts.get("obfInclude");
        final String exclude = cfg.texts.get("exclude");
        java.util.Map<String, java.util.List<String>> byPkg = new java.util.HashMap<>();
        for (String t : superMap.keySet()) {
            int i = t.lastIndexOf('/');
            String p = i > 0 ? t.substring(0, i) : "";
            byPkg.computeIfAbsent(p, k -> new java.util.ArrayList<>()).add(t);
        }
        java.util.Map<String, String> typeMap = new java.util.HashMap<>();
        for (var en : byPkg.entrySet()) {
            boolean allEligible = !en.getValue().isEmpty();
            for (String t : en.getValue()) {
                if (!eligible(t, allScope, include, exclude, superMap, hasNative, keepNames, strings)) {
                    allEligible = false;
                    break;
                }
            }
            if (!allEligible) continue;
            String newPkg = "L" + randSeg() + "/" + randSeg() + "/";
            java.util.Set<String> used = new java.util.HashSet<>();
            for (String t : en.getValue()) {
                String ns;
                do {
                    ns = randSeg();
                } while (!used.add(ns));
                typeMap.put(t, newPkg + ns + ";");
            }
        }
        if (typeMap.isEmpty()) {
            prog.log("  重命名：无符合条件的类，跳过");
            return inApk;
        }
        // 方法/字段名混淆：仅当超类链与接口链全部在重命名集合内（且非枚举）时才对该类生效
        java.util.Set<String> methodRenamable = new java.util.HashSet<>();
        for (String t : typeMap.keySet()) {
            if (enums.contains(t)) continue;
            boolean ok = true;
            String s = superMap.get(t);
            int g = 0;
            while (s != null && g++ < 60) {
                if (!typeMap.containsKey(s)) { ok = false; break; }
                s = superMap.get(s);
            }
            if (ok) {
                java.util.Deque<String> ifs = new java.util.ArrayDeque<>(ifaceMap.getOrDefault(t, java.util.Collections.emptyList()));
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (!ifs.isEmpty()) {
                    String i = ifs.poll();
                    if (!seen.add(i)) continue;
                    if (!typeMap.containsKey(i)) { ok = false; break; }
                    ifs.addAll(ifaceMap.getOrDefault(i, java.util.Collections.emptyList()));
                }
            }
            if (ok) methodRenamable.add(t);
        }
        java.util.Map<String, String> methodNames = new java.util.HashMap<>();
        java.util.Map<String, String> fieldNames = new java.util.HashMap<>();
        for (String t : methodRenamable) {
            for (String nm : classMethods.getOrDefault(t, java.util.Collections.emptyList())) {
                if (nm == null || nm.startsWith("<") || strings.contains(nm)) continue;
                if (!methodNames.containsKey(nm)) methodNames.put(nm, randSeg());
            }
            for (String fnm : classFields.getOrDefault(t, java.util.Collections.emptyList())) {
                if (fnm == null || strings.contains(fnm)) continue;
                if (!fieldNames.containsKey(fnm)) fieldNames.put(fnm, randSeg());
            }
        }
        File out = new File(work, "protect_rename.apk");
        try (ZipFile zip = new ZipFile(inApk);
             CountingOutputStream counting = new CountingOutputStream(new FileOutputStream(out));
             ZipOutputStream zout = new ZipOutputStream(counting)) {
            byte[] buf = new byte[65536];
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                String lower = name.toLowerCase();
                if (lower.startsWith("meta-inf/") && (lower.endsWith(".sf") || lower.endsWith(".rsa")
                        || lower.endsWith(".dsa") || lower.endsWith(".ec"))) {
                    continue;
                }
                if (dexClassIndex(name) > 0) {
                    writeEntry(zout, name, DexRename.rename(readAll(zip, ze), typeMap,
                            methodRenamable, methodNames, fieldNames, work));
                    continue;
                }
                ZipEntry ne = new ZipEntry(name);
                if (ze.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(ze.getSize());
                    ne.setCompressedSize(ze.getCompressedSize());
                    ne.setCrc(ze.getCrc());
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                alignStoreEntry(counting.count(), ne, name);
                zout.putNextEntry(ne);
                try (InputStream is = zip.getInputStream(ze)) {
                    int n;
                    while ((n = is.read(buf)) != -1) zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
        }
            prog.log("  重命名：" + typeMap.size() + " 个类，" + methodNames.size() + " 个方法，"
                    + fieldNames.size() + " 个字段");
        return out;
    }

    private static boolean eligible(String t, boolean allScope, String include, String exclude,
                                    java.util.Map<String, String> superMap, java.util.Set<String> hasNative,
                                    java.util.Set<String> keepNames, java.util.Set<String> strings) {
        if (t.length() < 3 || t.charAt(0) != 'L') return false;
        if (isFrameworkType(t) || t.startsWith("Lcn/mhook/")) return false;
        String cls = descToName(t);
        if (!allScope && !matchPrefix(cls, include)) return false;
        if (matchPrefix(cls, exclude)) return false;
        if (hasNative.contains(t)) return false;
        if (keepNames.contains(cls)) return false;
        if (strings.contains(cls) || strings.contains(simpleName(t))) return false;
        String s = superMap.get(t);
        int guard = 0;
        while (s != null && guard++ < 40) {
            if (isFrameworkType(s)) return false;
            s = superMap.get(s);
        }
        return true;
    }

    private static byte[] encrypt(byte[] key, byte[] data) throws Exception {
        byte[] iv = new byte[16];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                new javax.crypto.spec.IvParameterSpec(iv));
        byte[] ct = c.doFinal(data);
        byte[] out = new byte[16 + ct.length];
        System.arraycopy(iv, 0, out, 0, 16);
        System.arraycopy(ct, 0, out, 16, ct.length);
        return out;
    }

    private static boolean matchPrefix(String cls, String csv) {
        if (csv == null || csv.trim().isEmpty()) return false;
        for (String p : csv.split("[,\\s;]+")) {
            if (!p.isEmpty() && cls.startsWith(p.trim())) return true;
        }
        return false;
    }

    // ---------------- 重打包 + 对齐 + dex checksum ----------------
    private static void repackAndAlign(File in, File out, byte[] newManifest, byte[] guardDex,
                                       java.util.Map<String, byte[]> extraAssets,
                                       boolean onlyArm64, boolean dedup, boolean mem, boolean soObf, boolean soSig,
                                       boolean slim, IProgress prog) throws IOException {
        java.util.Map<String, String> dexHashes = mem ? new java.util.LinkedHashMap<>() : null;
        java.util.Map<String, String> soHashes = soSig ? new java.util.LinkedHashMap<>() : null;
        java.util.TreeMap<Integer, byte[]> dexList = new java.util.TreeMap<>();
        try (ZipFile zip = new ZipFile(in);
             CountingOutputStream counting = new CountingOutputStream(new FileOutputStream(out));
             ZipOutputStream zout = new ZipOutputStream(counting)) {
            if (dedup) zout.setLevel(9);
            byte[] buf = new byte[65536];
            int total = 0;
            int maxN = 1;
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                e.nextElement();
                total++;
            }
            int done = 0;
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                String lower = name.toLowerCase();
                // 剔除原签名
                if (lower.startsWith("meta-inf/") && (lower.endsWith(".sf") || lower.endsWith(".rsa")
                        || lower.endsWith(".dsa") || lower.endsWith(".ec"))) {
                    continue;
                }
                if (onlyArm64 && name.startsWith("lib/")) {
                    String[] seg = name.split("/");
                    if (seg.length >= 3 && !"arm64-v8a".equals(seg[1])) continue;
                }
                if ((soObf || soSig) && name.startsWith("lib/") && name.endsWith(".so")) {
                    byte[] so = readAll(zip, ze);
                    if (soObf) so = ElfStrip.strip(so);
                    if (soHashes != null) soHashes.put(name, sha256Hex(so));
                    ZipEntry ne = new ZipEntry(name);
                    if (ze.getMethod() == ZipEntry.STORED) {
                        ne.setMethod(ZipEntry.STORED);
                        ne.setSize(so.length);
                        ne.setCompressedSize(so.length);
                        CRC32 c = new CRC32();
                        c.update(so);
                        ne.setCrc(c.getValue());
                    } else {
                        ne.setMethod(ZipEntry.DEFLATED);
                    }
                    alignStoreEntry(counting.count(), ne, name);
                    zout.putNextEntry(ne);
                    zout.write(so);
                    zout.closeEntry();
                    continue;
                }
                if (soHashes != null && name.startsWith("assets/mhook_protect/so/") && name.endsWith(".enc")) {
                    byte[] d = readAll(zip, ze);
                    soHashes.put(name, sha256Hex(d));
                    writeEntry(zout, name, d);
                    continue;
                }
                if (dexClassIndex(name) > 0) {
                    // 收集所有 classesN.dex，稍后连续重编号写出（避免编号断档导致 ART 漏加载）
                    dexList.put(dexClassIndex(name), fixDexHeader(readAll(zip, ze)));
                    continue;
                }
                byte[] data = null;
                if ("AndroidManifest.xml".equals(name) && newManifest != null) {
                    data = newManifest;
                }
                ZipEntry ne = new ZipEntry(name);
                boolean keepStored = ze.getMethod() == ZipEntry.STORED
                        && !(slim && !"resources.arsc".equals(name) && !name.startsWith("lib/"));
                if (data != null) {
                    ne.setMethod(ZipEntry.DEFLATED);
                } else if (keepStored) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(ze.getSize());
                    ne.setCompressedSize(ze.getCompressedSize());
                    ne.setCrc(ze.getCrc());
                } else {
                    ne.setMethod(ZipEntry.DEFLATED);
                }
                alignStoreEntry(counting.count(), ne, name);
                zout.putNextEntry(ne);
                if (data != null) {
                    zout.write(data);
                } else {
                    InputStream in2 = zip.getInputStream(ze);
                    int n;
                    while ((n = in2.read(buf)) != -1) zout.write(buf, 0, n);
                    in2.close();
                }
                zout.closeEntry();
                done++;
                if (prog != null && (done % 500 == 0 || done == total)) prog.log("    处理 " + done + "/" + total);
            }
            // 连续重编号写出所有 dex（+ 运行时防护 guard.dex）
            java.util.List<byte[]> dexes = new java.util.ArrayList<>(dexList.values());
            if (guardDex != null) dexes.add(guardDex);
            int dnum = 0;
            for (byte[] d : dexes) {
                String n = (dnum == 0) ? "classes.dex" : ("classes" + (dnum + 1) + ".dex");
                dnum++;
                writeEntry(zout, n, d);
                if (dexHashes != null) dexHashes.put(n, sha256Hex(d));
            }
            if (guardDex != null && prog != null) {
                prog.log("  注入运行时防护 (" + (guardDex.length / 1024) + " KB)，dex 重编号为 " + dnum + " 个");
            }
            if (extraAssets != null) {
                for (java.util.Map.Entry<String, byte[]> en : extraAssets.entrySet()) {
                    writeEntry(zout, en.getKey(), en.getValue());
                }
            }
            if (dexHashes != null && !dexHashes.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (java.util.Map.Entry<String, String> e : dexHashes.entrySet()) {
                    sb.append(e.getKey()).append(':').append(e.getValue()).append('\n');
                }
                writeEntry(zout, "assets/mhook_protect/dexhash.cfg", sb.toString().getBytes("UTF-8"));
            }
            if (soHashes != null && !soHashes.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (java.util.Map.Entry<String, String> e : soHashes.entrySet()) {
                    sb.append(e.getKey()).append(':').append(e.getValue()).append('\n');
                }
                writeEntry(zout, "assets/mhook_protect/sohash.cfg", sb.toString().getBytes("UTF-8"));
            }
        }
    }

    /** resources.arsc 必须 STORED + 4 字节对齐（targetSdk>=30）。 */
    static void alignStoreEntry(long pos, ZipEntry ne, String name) {
        if (!"resources.arsc".equals(name)) return;
        try {
            int nameLen = ne.getName().getBytes("UTF-8").length;
            int need = (int) ((4 - ((pos + 30 + nameLen) % 4)) % 4);
            if (need == 0) return;
            int extraLen = need + 4;
            byte[] extra = new byte[extraLen];
            extra[0] = (byte) 0xA5;
            extra[1] = (byte) 0xA5;
            int sz = extraLen - 4;
            extra[2] = (byte) (sz & 0xFF);
            extra[3] = (byte) ((sz >> 8) & 0xFF);
            ne.setExtra(extra);
        } catch (Throwable ignored) {
        }
    }

    static byte[] fixDexHeader(byte[] dex) {
        if (dex == null || dex.length < 32 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x') return dex;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(dex, 32, dex.length - 32);
            byte[] sig = sha1.digest();
            System.arraycopy(sig, 0, dex, 12, 20);
            Adler32 adler = new Adler32();
            adler.update(dex, 12, dex.length - 12);
            long c = adler.getValue();
            dex[8] = (byte) (c & 0xFF);
            dex[9] = (byte) ((c >>> 8) & 0xFF);
            dex[10] = (byte) ((c >>> 16) & 0xFF);
            dex[11] = (byte) ((c >>> 24) & 0xFF);
        } catch (Exception ignored) {
        }
        return dex;
    }

    /** 计数输出流（用于 resources.arsc 对齐）。 */
    static class CountingOutputStream extends java.io.FilterOutputStream {
        private long count;

        CountingOutputStream(java.io.OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        long count() {
            return count;
        }
    }

    // ---------------- 工具 ----------------
    private static byte[] readAll(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static byte[] readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        return e == null ? null : readAll(zip, e);
    }

    private static String modeName(int m) {
        return m == SignConfig.MODE_KEYSTORE ? "自有 .jks" : m == SignConfig.MODE_KEYCERT ? "自有 key/cert" : "内置证书";
    }

    private static String schemeName(SignConfig s) {
        StringBuilder sb = new StringBuilder();
        if (s.v1) sb.append("V1+");
        if (s.v2) sb.append("V2+");
        if (s.v3) sb.append("V3+");
        if (s.v4) sb.append("V4+");
        if (s.zipalign) sb.append("对齐");
        String r = sb.toString();
        return r.endsWith("+") ? r.substring(0, r.length() - 1) : r;
    }

    private static File outputFile(Context ctx, ProtectConfig cfg, String apkName) {
        String dir = cfg.texts.get("outDir");
        File base;
        if (dir != null && !dir.trim().isEmpty()) {
            String d = dir.trim().replace("Download/", "");
            base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), d);
        } else {
            base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mhook_dump");
        }
        base.mkdirs();
        String name = apkName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return new File(base, name + "_protect.apk");
    }

    private static void releaseAsset(Context ctx, String asset, File dst) {
        try (InputStream in = ctx.getAssets().open(asset); FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        } catch (Throwable ignored) {
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
    }
}
