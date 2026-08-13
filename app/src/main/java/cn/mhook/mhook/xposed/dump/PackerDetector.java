package cn.mhook.mhook.xposed.dump;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import java.io.File;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 基于加固特征指纹（Application 类 / lib/*.so / assets 文件名）识别 APK 的加固方案。
 * 特征来源：G:\mHookdemo\加固特征 样本库。
 */
public class PackerDetector {

    public static class Sig {
        final String name;
        final String[] app;
        final String[] lib;
        final String[] asset;

        Sig(String name, String[] app, String[] lib, String[] asset) {
            this.name = name;
            this.app = app;
            this.lib = lib;
            this.asset = asset;
        }

        boolean matches(String appClass, Set<String> libs, Set<String> assets) {
            if (app != null && appClass != null) {
                for (String s : app) {
                    if (appClass.contains(s)) return true;
                }
            }
            if (lib != null) {
                for (String s : lib) {
                    for (String l : libs) {
                        if (l.contains(s)) return true;
                    }
                }
            }
            if (asset != null) {
                for (String s : asset) {
                    for (String a : assets) {
                        if (a.contains(s)) return true;
                    }
                }
            }
            return false;
        }
    }

    private static final Sig[] SIGS = {
            // 顺序=优先级：先精确特征，再通用特征；共性文件(.jgapp/ijiami.ajm)不做唯一判据
            // 360 企业版以 libjgcxi / libjiagu_vip 区分
            new Sig("360企业加固", null, new String[]{"libjgcxi", "libjiagu_vip"}, null),
            // 含 libjiagu 且非360（易固/Frezrik）必须先于 360 判断
            new Sig("娜迦加固", null, new String[]{"libxloader"}, new String[]{"maindata"}),
            new Sig("易固", new String[]{"hehua.StubApp"}, new String[]{"libjgdtc", "libvmp"}, null),
            new Sig("Frezrik加固", new String[]{"com.frezrik.jiagu"}, null, null),
            new Sig("360加固", new String[]{"com.stub.StubApp", "com.qihoo.util"}, new String[]{"libjiagu", "libprotectClass"}, null),
            new Sig("支付宝加固", new String[]{"com.ashield.Stub", "com.ashield"}, new String[]{"libashield", "libashieldAdapter", "libsign"}, null),
            new Sig("阿里加固", new String[]{"com.ali.mobisecenhance"}, new String[]{"libalisecuritysdk", "libalijtca", "libcn.vcinema"}, new String[]{"ali_sec.dat", "alibaba_version"}),
            new Sig("阿里聚安全", null, new String[]{"libalijtca", "libalisec"}, new String[]{"aliprotect.dat", "dingtalkttid"}),
            new Sig("梆梆企业", new String[]{"com.secneo.apkwrapper"}, new String[]{"libDexHelper", "libdexjni"}, null),
            new Sig("梆梆加固", new String[]{"com.SecShell.SecShell"}, new String[]{"libSecShell"}, new String[]{"classes0.jar"}),
            new Sig("腾讯御安全", new String[]{"StubWrapperProxyApplication", "com.tencent.StubShell.TxAppEntry"}, new String[]{"libshell-super", "libshella", "libshellx"}, new String[]{"tosversion", "0OO00l111l1l", "0OO00oo11l1l"}),
            new Sig("腾讯加固", null, new String[]{"libshell-super", "libshellx"}, new String[]{"o0oooOO0ooOo.dat"}),
            new Sig("网易易盾高级版", new String[]{"com.netease.nis.wrapper"}, new String[]{"libnesec"}, new String[]{"nedata.db"}),
            new Sig("网易易盾普通版", new String[]{"com.netease.android.protect"}, new String[]{"libunisec"}, new String[]{"_ntcfg_.data"}),
            new Sig("百度加固企业", null, new String[]{"libbaiduprotect"}, new String[]{"baiduprotect.m"}),
            new Sig("新百度加固", null, new String[]{"libbaiduprotect"}, new String[]{"baiduprotect-sec.dex", "baiduprotect1.i.dex"}),
            new Sig("百度加固", null, new String[]{"libbaiduprotect"}, new String[]{"baiduprotect1.jar"}),
            // 爱加密普通版用 af.bin/signed.bin/libexec 区分，企业版用 libijmDataEncryption/IJMDal.Data
            new Sig("爱加密", new String[]{"s.h.e.l.l.S", "s.h.e.l.l.A"}, new String[]{"libexec"}, new String[]{"af.bin", "signed.bin", "ijm_lib"}),
            new Sig("爱加密企业", null, new String[]{"libijmDataEncryption"}, new String[]{"IJMDal.Data", "ijiami.dat"}),
            new Sig("落叶加固魔改版", new String[]{"4b089d578346008b.ProxyApplication", "4b089d578346008b.ProxyComponentFactory"}, null, new String[]{"app_acf", "app_name"}),
            new Sig("落叶加固开源版", new String[]{"com.luoyesiqiu.shell"}, null, new String[]{"d_shell_data_001", "OoooooOooo", "vwwwwwvwww"}),
            new Sig("蛮犀加固", new String[]{"com.mx.shell"}, new String[]{"libmxldd"}, null),
            new Sig("顶象加固", new String[]{"com.security.shell"}, new String[]{"libapk0000", "libstub000"}, new String[]{"dsnapk0000.vd", "dsnstub000.vd", "csnb4adab14.data"}),
            new Sig("几维安全", new String[]{"com.kiwivm.security"}, new String[]{"libKwProtectSDK", "libkwsdataenc", "libkwscmm", "libkwsgmain"}, null),
            new Sig("ARM加固", new String[]{"arm.StubApp"}, new String[]{"libArmEpicVm", "libarm_protect"}, null),
            new Sig("Epic v2", new String[]{"Epic.ProtectApp"}, new String[]{"libEP_arm", "libEP_arm64"}, new String[]{"Epic_dexs", "Epic_so"}),
            new Sig("Appdome加固", new String[]{"android.support.v4.soft.ApplicationMain"}, new String[]{"libloader"}, new String[]{"m7a", "m8a"}),
            new Sig("CTools加固", new String[]{"crash.stub.ProxyApplication"}, new String[]{"libnmmp", "libnmmvm"}, null),
            new Sig("Google加固", new String[]{"com.pairip.application"}, new String[]{"libpairipcore"}, null),
            new Sig("OPPO加固", new String[]{"com.omes.omas"}, new String[]{"libomas"}, new String[]{"classes1.png"}),
            new Sig("Nesun", new String[]{"com.nesun.stub"}, new String[]{"libzprotect"}, new String[]{"origin.apk"}),
            new Sig("ShadowSafety", new String[]{"v.m.p"}, new String[]{"libshadowsafety"}, new String[]{"libShadowSafetyProtect"}),
            new Sig("TiamoMuxue", new String[]{"com.muxue.xue"}, new String[]{"libTiamo", "libmuxue"}, new String[]{"沐雪"}),
            new Sig("深思数盾", new String[]{"v5f259fe1.l5f259fe1", "l5f259fe1"}, null, new String[]{"l5f259fe1"}),
            new Sig("启明星辰", null, new String[]{"libvenustech", "libsqlen_venus", "libvenSec"}, new String[]{"venus0", "venusmd", "venusrc"}),
            new Sig("中国移动加固", null, new String[]{"libcmvmp", "libmogosecurity"}, new String[]{"mogosec_classes", "decrypt.so"}),
            new Sig("海云安", null, new String[]{"libsecidea"}, new String[]{"secdata1.dat", "secdata2.dat"}),
            new Sig("珊瑚灵御", null, new String[]{"libreincp"}, null),
            new Sig("盛大加固", null, new String[]{"libapssec"}, null),
            new Sig("瑞星加固", null, new String[]{"librsprotect"}, null),
            new Sig("网秦加固", null, new String[]{"libnqshield"}, null),
            new Sig("通付盾", null, new String[]{"libegis"}, new String[]{"libegis.a"}),
            new Sig("APKProtect", null, new String[]{"libAPKProtect"}, null),
            new Sig("AppSealin", null, new String[]{"libcovault"}, null),
            new Sig("AppShield", null, new String[]{"libahope"}, null),
            new Sig("DexProtect", new String[]{"ProtectedTvPlayerApplication"}, new String[]{"libdp"}, new String[]{"dp.arm", "dp.x86", "classes.dex.dat", "ic.dat", "se.dat"}),
            new Sig("DexProtector", null, new String[]{"libdexprotector"}, new String[]{"dexprotector"}),
            new Sig("随风加固", new String[]{"cn.beingyi.sub"}, null, null),
    };

    /** 返回加固方案名称；未识别返回 null。 */
    public static String detect(Context context, String apkPath) {
        try {
            String appClass = getApplicationClass(context, apkPath);
            Set<String> libs = new HashSet<>();
            Set<String> assets = new HashSet<>();
            try {
                ZipFile zf = new ZipFile(apkPath);
                try {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        String n = en.nextElement().getName();
                        if (n.startsWith("lib/") && n.endsWith(".so")) {
                            String base = n.substring(n.lastIndexOf('/') + 1);
                            if (base.endsWith(".so")) base = base.substring(0, base.length() - 3);
                            libs.add(base);
                        } else if (n.startsWith("assets/")) {
                            String a = n.substring("assets/".length());
                            assets.add(a);
                            // 部分壳(360/梆梆/腾讯)把壳 so 放在 assets/ 下
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
                    if (sig.matches(appClass, libs, assets)) return sig.name;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String getApplicationClass(Context context, String apkPath) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageArchiveInfo(apkPath,
                    android.content.pm.PackageManager.GET_ACTIVITIES);
            if (pi != null && pi.applicationInfo != null) {
                String cls = pi.applicationInfo.className;
                if (cls == null) {
                    ApplicationInfo ai = pi.applicationInfo;
                    cls = ai.className;
                }
                if (cls == null) cls = pi.applicationInfo.name;
                return cls;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
