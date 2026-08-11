package cn.mhook.mhook.xposed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.util.Log;
import com.tamsiree.rxkit.RxFileTool;
import com.tamsiree.rxkit.RxShellTool;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import cn.mhook.mhook.xposed.appxw.AppXWFX;
import cn.mhook.mhook.xposed.dialog.Dialog;
import cn.mhook.mhook.xposed.dump.StartDump;
import cn.mhook.mhook.xposed.utils.H;
import cn.mhook.mhook.xposed.utils.mHookCfg;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static cn.mhook.mhook.xposed.utils.H.startupparam;
import static cn.mhook.mhook.xposed.utils.mHookCfg.logDir;
import static cn.mhook.mhook.xposed.utils.mHookCfg.mDir;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

public class XposedMain implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (loadPackageParam.packageName.equals("cn.mhook.mhook")){
            findAndHookMethod("cn.mhook.activity.MainActivity", loadPackageParam.classLoader, "xp", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    param.setResult(true);
                }
            });
            return;
        }
        init(loadPackageParam);
    }



    private void init(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        H.loadPackageParam = loadPackageParam;
        H.pkg = loadPackageParam.packageName;
        mHookCfg.init();
        if (!RxFileTool.fileExists(mDir)) return;
        try { savaLog(); }catch (Throwable e){} //打印日志
        Dialog.init();
        try {
            findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Context c = (Context) param.args[0];
                    if (c!=null){
                        H.context = c;
                        H.flush();
                        new StartHook().init();
                    }
                }
            });

            XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    Context c =(Context) param.thisObject;
                    if (c!=null){
                        H.context = c;
                        new StartHook().init();
                    }
                }
            });
        }catch (Throwable e){

        }

        try {  //热修复
            H.systemContext = getSystemContext();
            H.context = getSystemContext();
        }catch (Throwable e){
            Log.w("err","获取Context失败："+e.getMessage());
        }

        try {
            StartFix.init();
        }catch (Throwable e){
            Log.w("err","热修复失败："+e.getMessage());
        }

        try {
            StartDump.init();
        }catch (Throwable e){
            Log.w("err","脱壳失败："+e.getMessage());
        }

        try {
            new AppXWFX();
        }catch (Throwable e){
            Log.w("err","应用行为分析失败："+e.getMessage());
        }

        try {
            new StartHook().init();
        }catch (Throwable e){
            Log.w("err","自定义HOOK失败："+e.getMessage());
        }
    }

    private void savaLog(){
        new Thread() {
            @Override
            public void run() {
                RxShellTool.execCmd("logcat -c",false);
                RxShellTool.execCmd( "logcat -v time > "+logDir,false);
            }
        }.start();
    }

    private Context getSystemContext() throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class activityThreadClass = Class.forName("android.app.ActivityThread");
        Method currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
        currentActivityThreadMethod.setAccessible(true);
        Object activityThread = currentActivityThreadMethod.invoke(activityThreadClass);
        Method getSystemContextMethod = activityThread.getClass().getDeclaredMethod("getSystemContext");
        getSystemContextMethod.setAccessible(true);
        Context systemContext = (Context) getSystemContextMethod.invoke(activityThread);
        return systemContext;
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        startupparam = startupParam;
    }
}
