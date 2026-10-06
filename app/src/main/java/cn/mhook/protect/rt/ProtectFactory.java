package cn.mhook.protect.rt;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DPT 入口工厂：被 manifest 的 android:appComponentFactory 指向。
 * 用自定义 ProtectClassLoader 替换框架默认加载器，从而加载被抽取的类。
 * 若目标原本已有 appComponentFactory，则从 assets/mhook_protect/dpt.cfg 读取并转发。
 */
public class ProtectFactory extends AppComponentFactory {

    private static ApplicationInfo AI;
    private static String AF;
    private static boolean afLoaded;
    private static ClassLoader CL;

    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo ai) {
        AI = ai;
        AF = null;
        afLoaded = false;
        try {
            ProtectClassLoader pcl = new ProtectClassLoader(ai, cl);
            CL = pcl;
            return pcl;
        } catch (Throwable t) {
            CL = cl;
            return cl;
        }
    }

    private static String originalFactory() {
        if (afLoaded) return AF;
        afLoaded = true;
        try (ZipFile z = new ZipFile(AI.sourceDir)) {
            ZipEntry e = z.getEntry("assets/mhook_protect/dpt.cfg");
            if (e != null) {
                String cfg;
                try (InputStream in = z.getInputStream(e)) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
                    cfg = new String(b.toByteArray(), "UTF-8");
                }
                String[] p = cfg.split("\\|");
                if (p.length > 2 && !p[2].isEmpty()
                        && !"cn.mhook.protect.rt.ProtectFactory".equals(p[2])) {
                    AF = p[2];
                }
            }
        } catch (Throwable ignored) {
        }
        return AF;
    }

    private AppComponentFactory orig() {
        String n = originalFactory();
        if (n == null) return null;
        try {
            Class<?> c = CL != null ? Class.forName(n, true, CL) : Class.forName(n);
            return (AppComponentFactory) c.newInstance();
        } catch (Throwable t) {
            Log.e("mhook.dpt", "orig factory " + n, t);
            return null;
        }
    }

    public Application instantiateApplication(ClassLoader cl, String cn) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory o = orig();
        return o != null ? o.instantiateApplication(cl, cn) : super.instantiateApplication(cl, cn);
    }

    public Activity instantiateActivity(ClassLoader cl, String cn, Intent i) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory o = orig();
        return o != null ? o.instantiateActivity(cl, cn, i) : super.instantiateActivity(cl, cn, i);
    }

    public Service instantiateService(ClassLoader cl, String cn, Intent i) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory o = orig();
        return o != null ? o.instantiateService(cl, cn, i) : super.instantiateService(cl, cn, i);
    }

    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String cn, Intent i) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory o = orig();
        return o != null ? o.instantiateReceiver(cl, cn, i) : super.instantiateReceiver(cl, cn, i);
    }

    public ContentProvider instantiateProvider(ClassLoader cl, String cn) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory o = orig();
        return o != null ? o.instantiateProvider(cl, cn) : super.instantiateProvider(cl, cn);
    }
}
