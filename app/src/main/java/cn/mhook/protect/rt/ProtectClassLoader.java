package cn.mhook.protect.rt;

import android.content.pm.ApplicationInfo;

/**
 * DPT 自定义类加载器：对主 dex 中不存在的类（已抽取），从加密 payload 还原加载。
 * 父加载器为框架默认加载器（pathClassLoader），父优先；仅当父找不到时才查 payload。
 */
public class ProtectClassLoader extends ClassLoader {

    private final ClassLoader payload;

    public ProtectClassLoader(ApplicationInfo ai, ClassLoader parent) {
        super(parent);
        ClassLoader p = null;
        try {
            p = DptPayload.create(ai, parent);
        } catch (Throwable ignored) {
        }
        this.payload = p;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (payload != null) {
            try {
                return payload.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException(name);
    }
}
