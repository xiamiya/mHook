package cn.mhook;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

import com.qmuiteam.qmui.arch.QMUISwipeBackActivityManager;
import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.RxShellTool;
import com.tamsiree.rxkit.RxTool;
import com.tencent.bugly.crashreport.CrashReport;

import java.io.File;

import cn.mhook.mhook.contentprovider.appCfg;
import cn.mhook.mhook.contentprovider.jsonCfg;
import cn.mhook.msu.su;
import cn.mhook.skin.QDSkinManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

public class mHookApplication extends Application {

    public static Context context;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Bugly 崩溃上报（AppID 来自 Bugly 平台，4.1.9.3 用 CrashReport.initCrashReport）
        try {
            CrashReport.initCrashReport(this, "3f0b2cfeba", false);
        } catch (Throwable t) {
            // bugly init failure should not break the host app
        }
        try {
            BlackBoxCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override
                public String getHostPackageName() {
                    return base.getPackageName();
                }
            });
        } catch (Throwable t) {
            // sandbox init failure should not break the host app
        }
        // 回调列表是进程本地的：每个进程（含 :black / :p0 虚拟进程）都会执行本方法，
        // 必须在所有进程注册，虚拟进程内虚拟应用的 Application 创建时才会触发动态分析。
        try {
            BlackBoxCore.get().addAppLifecycleCallback(new AppLifecycleCallback() {
                @Override
                public void beforeApplicationOnCreate(String packageName, String processName, android.app.Application application, int userId) {
                    try {
                        if (packageName != null && packageName.equals(processName)) {
                            // 线程名伪装：隐藏沙箱/动态分析特征线程名
                            cn.mhook.mhook.xposed.dump.ThreadHider.start();
                            // 尽早启动动态分析：即使 onCreate 后续崩溃（如加固壳动态加载失败），也能先抓到已加载的 dex
                            File outDir = new File(BlackBoxCore.getContext().getFilesDir(),
                                    "sandbox_dump" + File.separator + packageName);
                            cn.mhook.mhook.xposed.dump.SandboxDexDumper.start(outDir, application.getClassLoader(), application);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void afterApplicationOnCreate(String packageName, String processName, android.app.Application application, int userId) {
                    // onCreate 之后再用最新 classloader 触发一次：壳可能在 onCreate 期间加载/替换 classloader
                    try {
                        if (packageName != null && packageName.equals(processName)) {
                            File outDir = new File(BlackBoxCore.getContext().getFilesDir(),
                                    "sandbox_dump" + File.separator + packageName);
                            cn.mhook.mhook.xposed.dump.SandboxDexDumper.start(outDir, application.getClassLoader(), application);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            // sandbox init failure should not break the host app
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            BlackBoxCore.get().doCreate();
        } catch (Throwable t) {
            // sandbox init failure should not break the host app
        }
        // 沙箱派生的 :black / :p0.. 进程只做引擎初始化，跳过 mHook 自身的重活
        if (!isMainProcess()) {
            return;
        }
        new Thread() {
            @Override
            public void run() {
                RxShellTool.execCmd("logcat -c",false);
                RxShellTool.execCmd( "logcat -v time > /sdcard/mHookLog.txt",false);
            }
        }.start();

        context = getApplicationContext();
        QMUISwipeBackActivityManager.init(this);
        QDSkinManager.install(this);
        jsonCfg.context = this;
        appCfg.context = this;
        RxTool.init(this);
        su.init(this);

        // 兜底：BlackBox 的 SimpleCrashFix 会覆盖全局异常处理器并吞掉部分 Java 异常，
        // 这里在最外层再包一层，确保所有 Java 异常都能上报 Bugly，然后交给原处理器。
        installBuglyCrashForwarder();
    }

    private static void installBuglyCrashForwarder() {
        try {
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable throwable) {
                    try {
                        CrashReport.postCatchedException(throwable, thread);
                    } catch (Throwable ignored) {
                    }
                    if (prev != null) {
                        prev.uncaughtException(thread, throwable);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private boolean isMainProcess() {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[128];
            int n;
            try {
                n = in.read(buf);
            } finally {
                in.close();
            }
            int len = 0;
            while (len < n && buf[len] != 0) len++;
            String name = new String(buf, 0, len);
            return name == null || name.equals(getPackageName());
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if((newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES){
            QDSkinManager.changeSkin(QDSkinManager.SKIN_DARK);
        }else if(QDSkinManager.getCurrentSkin() == QDSkinManager.SKIN_DARK){
            QDSkinManager.changeSkin(QDSkinManager.SKIN_BLUE);
        }
    }

}
