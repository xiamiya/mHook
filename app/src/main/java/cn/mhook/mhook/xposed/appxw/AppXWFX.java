package cn.mhook.mhook.xposed.appxw;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.view.View;
import android.widget.Toast;
import com.alibaba.fastjson.JSONObject;

import java.io.File;

import cn.mhook.mhook.xposed.utils.H;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static cn.mhook.mhook.xposed.Config.getAppCfg;
import static cn.mhook.mhook.xposed.Config.getEnable;
import static cn.mhook.mhook.xposed.utils.H.getStackTrace;
import static cn.mhook.mhook.xposed.utils.H.putDetail;

public class AppXWFX {
    private final XC_LoadPackage.LoadPackageParam lpparam;

    public AppXWFX(XC_LoadPackage.LoadPackageParam lpparam){
        this.lpparam = lpparam;
        if (!getEnable("appCfgEnable")){
            return;
        }
        if (getEnable("dialog")) hookDialog();
        if (getEnable("toast")) hookToast();
        if (getEnable("button")) hookOnClick();
        if (getEnable("activity_goto")) hookActivityOnCreate();
        if (getEnable("activity_finish")) hookActivitySkip();
        if (getEnable("file")) hookFiles();
        initMethodReturnProbe();
    }

    /** 自定义方法返回值探测：读取 appCfg 的 methodReturn 数组并 hook。 */
    private void initMethodReturnProbe(){
        try {
            com.alibaba.fastjson.JSONObject cfg = getAppCfg();
            if (cfg != null && cfg.getJSONArray("methodReturn") != null) {
                MethodReturnProbe.init(lpparam, cfg.getJSONArray("methodReturn"));
            }
        } catch (Throwable ignored) {
        }
    }

    private void hookDialog(){
        XposedBridge.hookAllMethods(Dialog.class, "show", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                H.p(H.msg("对话框弹出",param.thisObject.getClass().getName(),putDetail(param,getStackTrace())));
            }
        });
    }

    private void hookToast(){
        XposedBridge.hookAllMethods(Toast.class, "show", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                H.p(H.msg("Toast弹出",param.thisObject.getClass().getName(),putDetail(param,getStackTrace())));
            }
        });
    }

    private void hookActivityOnCreate(){
        XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                H.p(H.msg("界面启动",param.thisObject.getClass().getName(),putDetail(param,getStackTrace())));
            }
        });
    }

    private void hookActivitySkip(){
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                Context who = (Context)param.args[0];
                IBinder contextThread = (IBinder)param.args[1];
                IBinder token = (IBinder)param.args[2];
                Activity target = (Activity)param.args[3];
                Intent intent = (Intent)param.args[4];
                int requestCode = (int)param.args[5];
                Bundle options = (Bundle)param.args[6];
                H.p(H.msg("将要跳转界面", JSONObject.parseObject(JSONObject.toJSONString(intent)),putDetail(param,getStackTrace())));
            }
        });
    }

    private void hookFiles(){
        XposedBridge.hookAllConstructors(File.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                if (param!=null&&param.args!=null&&param.args.length>0){
                    H.p(H.msg("访问存储",param.args[0].toString(),putDetail(param,getStackTrace())));
                    return;
                }
            }
        });
    }

    private void hookOnClick(){
        XposedHelpers.findAndHookMethod(View.class, "setOnClickListener", View.OnClickListener.class, new ViewOnClickListenerHooker());
    }

    private class ViewOnClickListenerHooker extends XC_MethodHook {

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            super.beforeHookedMethod(param);
            final View.OnClickListener listener = (View.OnClickListener) param.args[0];
            View.OnClickListener newListener=new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                        XposedBridge.hookAllMethods(listener.getClass(), "onClick", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                super.beforeHookedMethod(param);
                                if (!param.thisObject.getClass().getName().startsWith("cn.mhook.mhook.xposed")){
                                    H.p(H.msg("点击事件触发",param.thisObject.getClass().getName(),putDetail(null,getStackTrace())));
                                }
                            }
                        });
                    if (listener==null){
                        return ;
                    }else{
                        listener.onClick(v);
                    }
                }
            };
            param.args[0]=newListener;
        }
    }


}
