package cn.mhook.protect.rt;

/**
 * 运行时防护入口（类名固定，不参与重命名；native 通过 JNI_OnLoad 注册）。
 * 启动一个常驻 native 线程做 调试器/Frida/Hook库/Root 检测。
 */
public class Rasp {

    static {
        System.loadLibrary("rasp");
    }

    public static native void nativeStart(int flags, int action);
}
