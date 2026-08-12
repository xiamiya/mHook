package cn.mhook.mhook.xposed.appxw;

import android.text.TextUtils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import cn.mhook.mhook.xposed.utils.H;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 方法返回值探测：对用户指定的 类+方法 做 hook，运行后把返回值打印到「应用行为控制」的
 * 记录里（走 H.p → printLog → 悬浮窗），并智能解释常见类型。
 *
 * 配置格式（后续接入 应用行为控制 的 appCfg 时，存到自定义键下即可）：
 * [
 *   { "className": "com.example.SomeClass", "methodName": "getTime" },
 *   { "className": "com.example.UserInfo", "methodName": "isVip" }
 * ]
 *
 * 用法：MethodReturnProbe.init(loadPackageParam, hooksArray);
 * 返回值解释示例：
 *   boolean -> boolean=true / boolean=false
 *   long    -> long=1712345678901 毫秒时间戳 ≈ 2024-04-06 01:54:38（或 秒时间戳）
 *   String  -> String="xxx"
 */
public class MethodReturnProbe {

    /** 同一方法两次记录的最小间隔，避免 hook 到高频方法（如 currentTimeMillis）拖垮进程。 */
    private static final long MIN_INTERVAL_MS = 500;
    private static final Map<String, Long> sLastRecord = new HashMap<String, Long>();

    public static void init(XC_LoadPackage.LoadPackageParam lpparam, JSONArray hooks) {
        if (hooks == null || hooks.isEmpty()) return;
        for (Object o : hooks) {
            JSONObject h = (JSONObject) o;
            if (h == null) continue;
            String cls = h.getString("className");
            String mth = h.getString("methodName");
            if (TextUtils.isEmpty(cls) || TextUtils.isEmpty(mth)) continue;
            hookMethod(lpparam, cls, mth);
        }
    }

    /** 对指定 类+方法 挂 afterHookedMethod，记录返回值（hook 该方法的所有重载）。 */
    public static void hookMethod(final XC_LoadPackage.LoadPackageParam lpparam, final String className, final String methodName) {
        try {
            Class<?> c = XposedHelpers.findClass(className, lpparam.classLoader);
            XposedBridge.hookAllMethods(c, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        String who = param.method.getDeclaringClass().getName() + "." + param.method.getName();
                        long now = System.currentTimeMillis();
                        synchronized (sLastRecord) {
                            Long last = sLastRecord.get(who);
                            if (last != null && now - last < MIN_INTERVAL_MS) return;
                            sLastRecord.put(who, now);
                        }
                        Object ret = param.getResult();
                        JSONObject j = new JSONObject(true);
                        j.put("方法", who);
                        j.put("返回值", formatReturn(ret));
                        j.put("调用", H.getStackTrace());
                        H.p(H.msg("返回值探测", "方法 " + who + " 返回", j));
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            // 类不存在/方法不存在时静默跳过（目标应用可能未加载该类）
        }
    }

    /** 把返回值格式化为可读文本，并对常见类型做智能解释。 */
    public static String formatReturn(Object ret) {
        if (ret == null) return "null";
        if (ret instanceof Boolean) {
            return "boolean=" + ret;
        }
        if (ret instanceof Integer) {
            return "int=" + ret;
        }
        if (ret instanceof Long) {
            long v = (Long) ret;
            String ts = interpretTimestamp(v);
            return ts == null ? ("long=" + v) : ("long=" + v + "  " + ts);
        }
        if (ret instanceof Short || ret instanceof Byte || ret instanceof Character) {
            return ret.getClass().getSimpleName().toLowerCase() + "=" + ret;
        }
        if (ret instanceof Float || ret instanceof Double) {
            return ret.getClass().getSimpleName().toLowerCase() + "=" + ret;
        }
        if (ret instanceof String) {
            return "String=\"" + ret + "\"";
        }
        if (ret instanceof Enum) {
            return "enum=" + ret.getClass().getSimpleName() + "." + ret;
        }
        return ret.getClass().getSimpleName() + "=" + ret;
    }

    /**
     * 判断 long 是否像时间戳：优先毫秒（接近当前毫秒或 13 位），其次秒（10 位）。
     * 返回 null 表示不是明显的时间戳。
     */
    private static String interpretTimestamp(long v) {
        if (v <= 100_000_000L) return null;
        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000;
        // 毫秒：接近当前毫秒时间（±50 年）或 13 位
        if (v > 1_000_000_000_000L && Math.abs(v - nowMs) < 50L * 365 * 24 * 3600 * 1000) {
            return "毫秒时间戳 ≈ " + fmt(v);
        }
        // 秒：接近当前秒时间
        if (v > 100_000_000L && Math.abs(v - nowSec) < 50L * 365 * 24 * 3600) {
            return "秒时间戳 ≈ " + fmt(v * 1000);
        }
        return null;
    }

    private static String fmt(long ms) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(ms));
        } catch (Throwable t) {
            return String.valueOf(ms);
        }
    }
}
