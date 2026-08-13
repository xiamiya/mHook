package cn.mhook.msu;

import android.content.Context;
import android.os.Looper;
import android.util.Log;
import com.tamsiree.rxkit.RxFileTool;
import com.tamsiree.rxkit.view.RxToast;
import java.io.File;
import eu.darken.rxshell.cmd.Cmd;
import eu.darken.rxshell.cmd.RxCmdShell;
import eu.darken.rxshell.root.Root;

public class su {

    public static RxCmdShell.Session session;

    public static void init(Context context){
        new Thread(new Runnable(){
            @Override
            public void run(){
                Root root = new Root.Builder().build().blockingGet();
                if(root.getState() == Root.State.ROOTED){
                    session = RxCmdShell.builder().build().open().blockingGet();
                    Cmd.builder(
                            "su",
                            "setenforce 0",
                            "mount -o remount /data",
                            "cd /data/",
                            "mkdir mHook",
                            "chmod -R 777 mHook")
                            .execute(session);
                    initPath(context);
                } else {
                    // 无 root 设备静默跳过：沙箱免root脱壳不依赖 root，不再弹提示
                }
            }
        }).start();
    }

    static void initPath(Context context){
        if (!RxFileTool.fileExists("/data/mHook/mHookApp/")){
            RxFileTool.writeFileFromString("/data/mHook/mHookApp/dump","balabala",false);
            RxFileTool.deleteFile("/data/mHook/mHookApp/dump");
            File file = new File("/data/mHook/mHookApp/lib/");
            file.mkdir();
            AssetsCopyer.releaseAssets(context,"mk","/data/mHook/mHookApp/lib/");
            set777();
        }
    }

    public static void set777(){
        new Thread(new Runnable(){
            @Override
            public void run(){
               Cmd.builder("chmod -R 777 /data/mHook").execute(session);
            }
        }).start();
    }

    /** 同步执行一条 su 命令（阻塞到执行完），用于需要立即生效的目录创建/权限设置。 */
    public static void exec(String cmd){
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            while (r.readLine() != null) {
                // drain
            }
            p.waitFor();
        } catch (Throwable t) {
            Log.e("su", "exec err: " + t.getMessage());
        }
    }

    /** 同步执行 su 命令并返回标准输出（去首尾空白）。 */
    public static String getOutput(String cmd){
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
        } catch (Throwable t) {
            Log.e("su", "getOutput err: " + t.getMessage());
        }
        return sb.toString().trim();
    }

    /** 判断目标包名是否有运行中的进程（su 查 pidof）。 */
    public static boolean hasProcess(String pkg){
        try {
            String out = getOutput("pidof '" + pkg + "'");
            return out != null && !out.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
