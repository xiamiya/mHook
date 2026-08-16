package cn.mhook;

import android.util.Log;

import com.alibaba.fastjson.JSONObject;
import com.tamsiree.rxkit.RxFileTool;
import com.tamsiree.rxkit.view.RxToast;

import static cn.mhook.mData.mDir;
import static cn.mhook.msu.su.set777;

public class App {

    private static String path = mDir+"mHookApp/appSetting.json";

    public static Boolean enable(String type){
        if (RxFileTool.fileExists(path)){
           String cfg = RxFileTool.readFile2String(path,"utf-8");
             if (cfg!=null&&!cfg.isEmpty()){
                 try {
                     JSONObject appCfg = JSONObject.parseObject(cfg);
                     if (appCfg.containsKey(type)&&appCfg.getBoolean(type))return true;
                 }catch (Throwable throwable){
                     Log.i("err",throwable.getMessage());
                     return false;
                 }
             }
        }
        return false;
    }

    public static void setEnable(String type,Boolean enadle){
        try {
            if (RxFileTool.fileExists(path)){
                String cfg = RxFileTool.readFile2String(path,"utf-8");
                if (cfg!=null&&!cfg.isEmpty()){
                    try {
                        JSONObject appCfg = JSONObject.parseObject(cfg);
                        appCfg.put(type,enadle);
                        RxFileTool.writeFileFromString(path,appCfg.toJSONString(),false);
                    }catch (Throwable throwable){
                        Log.i("err",throwable.getMessage());
                    }
                }
            }else {
                JSONObject appCfg = new JSONObject();
                appCfg.put(type,enadle);
                RxFileTool.writeFileFromString(path,appCfg.toJSONString(),false);
                set777();
            }
        } catch (Throwable t) {
            Log.e("App", "setEnable err: " + t.getMessage());
        }
    }
}
