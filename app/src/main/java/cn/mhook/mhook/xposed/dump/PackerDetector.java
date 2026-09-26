package cn.mhook.mhook.xposed.dump;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 基于加固特征指纹（Application 类 / lib/*.so / assets 文件名 / 版本号 so 正则）识别 APK 的加固方案。
 * <p>
 * 特征来源：
 *  - mHook 原样本库；
 *  - ApkCheckPack（moyuwa/ApkCheckPack，规则更新 2026-06-18，40+ 厂商）。
 * <p>
 * 代际 gen：1=DEX 整体加密·落地加载；2=DEX 整体加密·内存加载；3=函数抽取(+反调试)；
 * 4=VMP/Java2C（基本无法自动动态分析）。
 */
public class PackerDetector {

    public static class Sig {
        final String name;
        final int gen;
        final String[] app;
        final String[] lib;
        final String[] asset;
        final Pattern[] soregex;

        Sig(String name, int gen, String[] app, String[] lib, String[] asset, String[] soregex) {
            this.name = name;
            this.gen = gen;
            this.app = app;
            this.lib = lib;
            this.asset = asset;
            if (soregex != null && soregex.length > 0) {
                this.soregex = new Pattern[soregex.length];
                for (int i = 0; i < soregex.length; i++) {
                    try {
                        this.soregex[i] = Pattern.compile(soregex[i]);
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                this.soregex = null;
            }
        }

        /** 短特征(如 libdp/m7a/t86)必须精确相等，避免误报；长特征(>=6)允许包含。 */
        private static boolean libMatch(String l, String s) {
            return l.equals(s) || (s.length() >= 6 && l.contains(s));
        }

        private static boolean assetMatch(String a, String s) {
            return a.equals(s) || (s.length() >= 6 && a.contains(s));
        }
        boolean matches(String appClass, Set<String> libs, Set<String> assets, List<String> names) {
            if (app != null && appClass != null) {
                for (String s : app) if (appClass.contains(s)) return true;
            }
            if (lib != null) {
                for (String s : lib)
                    for (String l : libs) if (libMatch(l, s)) return true;
            }
            if (asset != null) {
                for (String s : asset)
                    for (String a : assets) if (assetMatch(a, s)) return true;
            }
            if (soregex != null) {
                for (Pattern p : soregex) {
                    if (p == null) continue;
                    for (String n : names) if (p.matcher(n).find()) return true;
                }
            }
            return false;
        }
    }

    /**
     * 顺序=优先级：先精确特征，再通用特征（共性文件如 ijm_lib/ijiami.ajm 不做唯一判据）。
     */
    private static final Sig[] SIGS = {
            // ===== 腾讯系 =====
            new Sig("腾讯乐固(VMP)", 4, null, new String[]{"libxgVipSecurity"}, null, null),
            new Sig("腾讯加固", 2, null, new String[]{"libshell-super", "libshellx", "libshell.so", "libtup", "libshel1x", "liblegudb", "libshella"},
                    new String[]{"o0oooOO0ooOo.dat", "mix.dex", "mixz.dex", "tencent_stub", "tosversion", "000000011111.dex", "000000111111.dex", "o0ooo000oo0o.dat", "t86", "tosprotection", "0OO00l111l1l", "0000000lllll.dex", "00000olllll.dex"},
                    new String[]{"libshella-\\d+\\.\\d+\\.\\d+\\.\\d+\\.so", "libshell-super.\\d+\\.so", "libshellx-super.\\d+\\.so", "libshell-superv.*\\.\\d{4}\\.so", "libWSSec(V?)\\.so"}),
            new Sig("腾讯御安全", 2, new String[]{"StubWrapperProxyApplication", "com.tencent.StubShell.TxAppEntry"}, new String[]{"libtosprotection", "libshell-super", "libshella", "libshellx"},
                    new String[]{"tosversion", "0OO00l111l1l", "0OO00oo11l1l"}, new String[]{"libtosprotection\\.(armeabi|armeabi-v7a|x86|arm64-v8a|x86_64)\\.so"}),
            new Sig("腾讯手游加固", 2, null, new String[]{"libtprt"}, null, null),
            new Sig("腾讯Bugly", 2, null, new String[]{"libBugly"}, null, null),

            // ===== 顶象 / 网易 / 几维 =====
            new Sig("顶象加固", 3, new String[]{"com.security.shell"}, new String[]{"libapk0000", "libstub000", "libx3g", "libsys_misc"},
                    new String[]{"dsnapk0000.vd", "dsnstub000.vd", "csnb4adab14.data"}, null),
            new Sig("网易易盾高级(VMP)", 4, new String[]{"com.netease.nis.wrapper"}, new String[]{"libnesec"}, new String[]{"nedata.db"}, null),
            new Sig("网易易盾普通版", 2, new String[]{"com.netease.android.protect"}, new String[]{"libunisec"}, new String[]{"_ntcfg_.data"}, null),
            new Sig("几维安全", 3, new String[]{"com.kiwivm.security"},
                    new String[]{"libKwProtectSDK", "libkwsdataenc", "libkwscmm", "libkwsgmain", "libkwscr", "libkwslinker", "kdpdata", "libkdp", "libkadp", "libkiwi_dumper", "libkiwicrash", "libkwdataenc"},
                    new String[]{"dex.dat"}, null),

            // ===== 360 =====
            new Sig("360企业加固", 3, null, new String[]{"libjgcxi", "libjiagu_vip"}, null, null),
            new Sig("360加固", 3, new String[]{"com.stub.StubApp", "com.qihoo.util"},
                    new String[]{"libjiagu", "libjgdtc", "libprotectClass", "libSafeManageService"}, new String[]{".appkey"},
                    new String[]{"libjiagu_.*\\.so", "libjgdtc_.*\\.so"}),

            // ===== 百度 =====
            new Sig("百度加固企业", 2, null, new String[]{"libbaiduprotect"}, new String[]{"baiduprotect.m"}, null),
            new Sig("新百度加固", 2, null, new String[]{"libbaiduprotect"}, new String[]{"baiduprotect-sec.dex", "baiduprotect1.i.dex"}, null),
            new Sig("百度加固", 2, null, new String[]{"libbaiduprotect", "libbaiduprotect_art", "libbaiduprotect_x86"},
                    new String[]{"baiduprotect1.jar", "baiduprotect.jar"}, null),

            // ===== 爱加密 =====
            new Sig("爱加密企业版", 3, null, new String[]{"libijmDataEncryption"}, new String[]{"IJMDal.Data", "ijiami.dat", "ijiami.ajm"}, null),
            new Sig("爱加密5代壳", 3, null, new String[]{"libijmDataEncryption"}, new String[]{"IJMDal.Data"}, null),
            new Sig("爱加密3代壳", 3, null, new String[]{"libexecv3"}, new String[]{"ijiami3.ajm"}, null),
            new Sig("爱加密", 2, new String[]{"s.h.e.l.l.S", "s.h.e.l.l.A"}, new String[]{"libexec", "libexecmain"},
                    new String[]{"af.bin", "signed.bin", "ijm_lib"}, null),

            // ===== 梆梆 =====
            new Sig("梆梆企业", 3, new String[]{"com.secneo.apkwrapper"}, new String[]{"libDexHelper", "libdexjni"},
                    new String[]{"classes.jar"}, new String[]{"libDexHelper-.+\\.so", "libDexHelper-x86\\.so"}),
            new Sig("梆梆加固", 2, new String[]{"com.SecShell.SecShell"}, new String[]{"libSecShell", "libSecShel1", "libsecexe", "libsecmain"},
                    new String[]{"classes0.jar", "secData0.jar"}, new String[]{"libSecShell_art\\.so"}),

            // ===== 阿里 =====
            new Sig("阿里加固", 2, new String[]{"com.ali.mobisecenhance"},
                    new String[]{"libalisecuritysdk", "libalijtca", "libcn.vcinema", "libmobisec", "libsgmain", "libsgsecuritybody", "libzuma", "libpreverify1", "libzumadata", "libfakejni"},
                    new String[]{"ali_sec.dat", "alibaba_version", "aliprotect.dat"}, null),
            new Sig("阿里云加固", 2, null, new String[]{"libdemolish", "libdemolishdata"}, null, null),

            // ===== 娜迦 =====
            new Sig("娜迦加固", 2, null, new String[]{"libxloader", "libchaosvmp", "libddog", "libfdog", "libhdog"},
                    new String[]{"maindata"}, new String[]{"lib.dog\\.so"}),
            new Sig("娜迦企业版", 2, null, new String[]{"libedog"}, null, null),
            new Sig("娜迦(VMP)", 4, null, new String[]{"libvdog"}, null, new String[]{"libvdog-.+\\.so"}),

            // ===== 腾讯系以外的国内厂商 =====
            new Sig("通付盾", 2, null, new String[]{"libegis", "libNSaferOnly", "libgeiri"}, new String[]{"libegis.a"}, null),
            new Sig("网秦加固", 2, null, new String[]{"libnqshield"}, null, null),
            new Sig("盛大加固", 2, null, new String[]{"libapssec"}, null, null),
            new Sig("瑞星加固", 2, null, new String[]{"librsprotect"}, null, null),
            new Sig("珊瑚灵御", 2, null, new String[]{"libreincp"}, null, new String[]{"libreincp_.+\\.so"}),
            new Sig("海云安加固", 2, null, new String[]{"libsecidea", "libitsec"}, new String[]{"secdata1.dat", "secdata2.dat", "itse"}, null),
            new Sig("启明星辰", 2, null, new String[]{"libvenustech", "libsqlen_venus", "libvenSec"}, new String[]{"venus0", "venusmd", "venusrc"}, null),
            new Sig("中国移动加固", 3, null, new String[]{"libcmvmp", "libmogosecurity", "libmogosec_dex", "libmogosec_sodecrypt"},
                    new String[]{"mogosec_classes", "mogosec_data", "mogosec_dexinfo", "decrypt.so"}, null),
            new Sig("蛮犀加固", 2, new String[]{"com.mx.shell"}, new String[]{"libmxldd", "libdSafeShell", "libmxacc", "libmanxi"},
                    new String[]{"mxsafe"}, null),
            new Sig("易固", 2, new String[]{"hehua.StubApp"}, new String[]{"libjgdtc", "libvmp"}, null, null),
            new Sig("Frezrik加固", 2, new String[]{"com.frezrik.jiagu"}, null, null, null),
            new Sig("深盾安全(Virbox)", 2, null, new String[]{"libvirbox"}, null, new String[]{"libvirbox..\\.so"}),
            new Sig("CFCA加固", 2, null, new String[]{"libbasec", "libsecenh"}, new String[]{"my_classes.jar"}, null),
            new Sig("apktoolplus", 2, null, new String[]{"libapktoolplus_jiagu"}, new String[]{"jiagu_data.bin", "sign.bin"}, null),
            new Sig("能信安科技", 2, null, new String[]{"libzprotect"}, null, null),
            new Sig("UU安全加固", 2, null, new String[]{"libuusafe"}, null, null),
            new Sig("LIAPP加固", 2, new String[]{"com.lockincomp.liapp"}, null, new String[]{"LIAPP.ini", "pkgInfo.txt"}, null),

            // ===== OPPO / Google / 国外 =====
            new Sig("OPPO加固", 2, new String[]{"com.omes.omas"}, new String[]{"libomas", "OPPOProtect"}, new String[]{"classes1.png"}, new String[]{"OPPOProtect\\d{4}\\.so"}),
            new Sig("OPPO安全检测SDK", 2, null, new String[]{"libomesStdSco"}, null, null),
            new Sig("Google加固", 2, new String[]{"com.pairip.application"}, new String[]{"libpairipcore"}, null, null),
            new Sig("Appdome加固", 2, new String[]{"android.support.v4.soft.ApplicationMain"}, null, new String[]{"m7a", "m8a"}, null),
            new Sig("DexProtector", 2, null, new String[]{"libdexprotector"}, new String[]{"dexprotector"}, null),
            new Sig("DexProtect", 2, new String[]{"ProtectedTvPlayerApplication"}, new String[]{"libdp"},
                    new String[]{"dp.arm", "dp.x86", "classes.dex.dat", "ic.dat", "se.dat"}, null),
            new Sig("G-Presto加固", 2, new String[]{"com.bishopsoft/Presto", "Presto_Init", "ATG_H init"},
                    new String[]{"libATG_L", "libATG_D", "libATG_H"}, new String[]{"ATG_E.sec"}, null),
            new Sig("appguard.us", 2, new String[]{"AppGuard$IOnLoadInformation", "Lcom/nhnent/appguard/AppGuard"},
                    new String[]{"libAppGuard", "libdiresu"}, null, null),

            // ===== 其他 =====
            new Sig("梆梆(bangcle旧版)", 2, new String[]{"com.secapk.wrapper"}, null, null, null),
            new Sig("支付宝加固", 2, new String[]{"com.ashield.Stub", "com.ashield"}, new String[]{"libashield", "libashieldAdapter", "libsign"}, null, null),
            new Sig("ARM加固", 2, new String[]{"arm.StubApp"}, new String[]{"libArmEpicVm", "libarm_protect"}, null, null),
            new Sig("Epic v2", 2, new String[]{"Epic.ProtectApp"}, new String[]{"libEP_arm", "libEP_arm64"}, new String[]{"Epic_dexs", "Epic_so"}, null),
            new Sig("CTools加固", 2, new String[]{"crash.stub.ProxyApplication"}, new String[]{"libnmmp", "libnmmvm"}, null, null),
            new Sig("Nesun", 2, new String[]{"com.nesun.stub"}, new String[]{"libzprotect"}, new String[]{"origin.apk"}, null),
            new Sig("ShadowSafety", 3, new String[]{"v.m.p"}, new String[]{"libshadowsafety"}, new String[]{"libShadowSafetyProtect"}, null),
            new Sig("TiamoMuxue", 2, new String[]{"com.muxue.xue"}, new String[]{"libTiamo", "libmuxue"}, new String[]{"沐雪"}, null),
            new Sig("深思数盾", 2, new String[]{"v5f259fe1.l5f259fe1", "l5f259fe1"}, null, new String[]{"l5f259fe1"}, null),
            new Sig("APKProtect", 2, null, new String[]{"libAPKProtect", "libapk-protect"}, null, null),
            new Sig("AppSealin", 2, null, new String[]{"libcovault"}, null, null),
            new Sig("AppShield", 2, null, new String[]{"libahope"}, null, null),
            new Sig("落叶加固魔改版", 2, new String[]{"4b089d578346008b.ProxyApplication", "4b089d578346008b.ProxyComponentFactory"}, null, new String[]{"app_acf", "app_name"}, null),
            new Sig("落叶加固开源版", 2, new String[]{"com.luoyesiqiu.shell"}, null, new String[]{"d_shell_data_001", "OoooooOooo", "vwwwwwvwww"}, null),
            new Sig("随风加固", 2, new String[]{"cn.beingyi.sub"}, null, null, null),
    };

    /** 检测结果：名称 + 代际。 */
    public static class Result {
        public final String name;
        public final int gen;
        Result(String name, int gen) { this.name = name; this.gen = gen; }
        public String genName() {
            switch (gen) {
                case 1: return "一代·落地加载";
                case 2: return "二代·内存加载";
                case 3: return "三代·函数抽取";
                case 4: return "四代·VMP/Java2C";
                default: return "未识别";
            }
        }
    }

    /** 额外指纹：dex 内字符串(jclass)匹配——治类名被混淆 / Application 非壳代理的壳。{名称, 代际, 特征串...} */
    private static final Object[][] JCLASS = {
            {"appguard.us", 2, "AppGuard$IOnLoadInformation", "Lcom/nhnent/appguard/AppGuard"},
            {"G-Presto加固", 2, "Lcom/bishopsoft/Presto", "Presto_Init", "ATG_H init"},
            {"腾讯乐固(VMP)", 4, "com/tencent/StubShell", "TxAppEntry"},
            {"网易易盾高级(VMP)", 4, "com/netease/nis/wrapper", "nis.wrapper"},
            {"顶象加固", 3, "com/security/shell", "dx.matrix", "DingXiang"},
            {"几维安全", 3, "com/kiwivm/security", "kwi.dumper", "kwsgmain"},
            {"爱加密", 2, "s.h.e.l.l", "ijiami", "com/secure/SignatureKiller"},
            {"梆梆加固", 2, "com/secneo/apkwrapper", "DexHelper"},
            {"360加固", 3, "com/stub/StubApp", "com/qihoo/util"},
            {"百度加固", 2, "com/baidu/protect", "baiduprotect"},
            {"通付盾", 2, "com/egis", "NSaferOnly"},
            {"蛮犀加固", 2, "com/mx/shell", "mxsafe"},
            {"中国移动加固", 3, "mogosec", "mogosecurity"},
            {"ShadowSafety", 3, "v/m/p", "ShadowSafetyProtect"},
            {"梆梆(bangcle旧版)", 2, "com/secapk/wrapper"},
            {"LIAPP加固", 2, "com/lockincomp/liapp", "LiappCommon"},
            {"娜迦加固", 2, "com/nagain", "libchaosvmp"},
    };
    /** 返回检测结果（名称+代际）；未识别返回 null。 */
    public static Result detectResult(Context context, String apkPath) {
        try {
            String appClass = getApplicationClass(context, apkPath);
            Set<String> libs = new HashSet<>();
            Set<String> assets = new HashSet<>();
            List<String> names = new ArrayList<>();
            try {
                ZipFile zf = new ZipFile(apkPath);
                try {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        String n = en.nextElement().getName();
                        names.add(n);
                        if (n.startsWith("lib/") && n.endsWith(".so")) {
                            String base = n.substring(n.lastIndexOf('/') + 1);
                            if (base.endsWith(".so")) base = base.substring(0, base.length() - 3);
                            libs.add(base);
                        } else if (n.startsWith("assets/")) {
                            String a = n.substring("assets/".length());
                            assets.add(a);
                            // 部分(360/梆梆/腾讯)把壳 so 放在 assets/ 下
                            if (a.endsWith(".so")) {
                                String base = a.substring(a.lastIndexOf('/') + 1);
                                if (base.endsWith(".so")) base = base.substring(0, base.length() - 3);
                                libs.add(base);
                            }
                        }
                    }
                } finally {
                    zf.close();
                }
            } catch (Throwable ignored) {
            }
            for (Sig sig : SIGS) {
                try {
                    if (sig.matches(appClass, libs, assets, names)) return new Result(sig.name, sig.gen);
                } catch (Throwable ignored) {
                }
            }
            // 兜底：dex 字符串(jclass)匹配
            Result jr = matchJclass(apkPath);
            if (jr != null) return jr;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 返回加固方案名称；未识别返回 null。 */
    public static String detect(Context context, String apkPath) {
        Result r = detectResult(context, apkPath);
        return r == null ? null : r.name;
    }

    private static Result matchJclass(String apkPath) {
        try {
            byte[] blob = readDexBlob(apkPath);
            if (blob == null) return null;
            String s = new String(blob, "ISO-8859-1");
            for (Object[] row : JCLASS) {
                for (int i = 2; i < row.length; i++) {
                    if (s.contains((String) row[i])) return new Result((String) row[0], (Integer) row[1]);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static byte[] readDexBlob(String apkPath) {
        try (ZipFile zf = new ZipFile(apkPath)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            Enumeration<? extends ZipEntry> en = zf.entries();
            long total = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().matches("classes\\d*\\.dex")) continue;
                if (total > (48L << 20)) break;
                java.io.InputStream in = zf.getInputStream(e);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    total += n;
                    if (total > (48L << 20)) break;
                }
                in.close();
            }
            return bos.size() > 0 ? bos.toByteArray() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 生成「加固检测报告」文本。 */
    public static String report(Result r, boolean shellApk) {
        StringBuilder sb = new StringBuilder();
        if (r == null) {
            sb.append("加固方案：未识别").append(shellApk ? "（检测到壳特征）" : "（未检测到已知加固）").append("\n\n");
            sb.append("可脱性：未知\n建议：仍可尝试自动处理，或改用「签名强度检测」");
            return sb.toString();
        }
        sb.append("加固方案：").append(r.name).append("\n");
        sb.append("代际：").append(r.genName()).append("\n\n");
        switch (r.gen) {
            case 1:
            case 2:
                sb.append("可脱性：较高（整体壳，内存 dump 可脱）\n");
                sb.append("建议：直接开始检测（原生 / IO 重定向）");
                break;
            case 3:
                sb.append("可脱性：中等（函数抽取，方法体运行时才解密）\n");
                sb.append("建议：开始检测后若方法体为 nop，改用 SR方案 Lv.4，或等待深度回填");
                break;
            case 4:
                sb.append("可脱性：较低（VMP / Java2C，指令已转译）\n");
                sb.append("建议：自动脱壳通常无效，建议仅做「签名强度检测」或手工分析");
                break;
            default:
                sb.append("可脱性：未知");
        }
        return sb.toString();
    }
    private static String getApplicationClass(Context context, String apkPath) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageArchiveInfo(apkPath,
                    android.content.pm.PackageManager.GET_ACTIVITIES);
            if (pi != null && pi.applicationInfo != null && pi.applicationInfo.className != null) {
                return pi.applicationInfo.className;
            }
        } catch (Throwable ignored) {
        }
        // framework className 常为 null：直接解析 AXML 的 application android:name（与脱修同一解析器）
        try (ZipFile zf = new ZipFile(apkPath)) {
            ZipEntry e = zf.getEntry("AndroidManifest.xml");
            if (e != null) {
                java.io.InputStream in = zf.getInputStream(e);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                in.close();
                return cn.mhook.npatch.AxmlPatch.findApplicationName(bos.toByteArray());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
