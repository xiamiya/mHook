package cn.mhook.protect.rt;

/**
 * 日志空实现：与 android.util.Log 的常用静态方法同签名，全部不做任何事。
 * 加固时把 Landroid/util/Log;->{d,v,i,w,e,wtf,println} 的方法引用重定向到这里 → 运行期不产出日志。
 */
public class LogKill {

    public static int v(String tag, String msg) { return 0; }
    public static int v(String tag, String msg, Throwable tr) { return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable tr) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int i(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, Throwable tr) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable tr) { return 0; }
    public static int wtf(String tag, String msg) { return 0; }
    public static int wtf(String tag, String msg, Throwable tr) { return 0; }
    public static int wtf(String tag, Throwable tr) { return 0; }
    public static void println(int priority, String tag, String msg) { }
}
