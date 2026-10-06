package cn.mhook.protect;

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;

import java.io.ByteArrayInputStream;

/**
 * 基于 ARSCLib 的 AndroidManifest.xml 编辑：框架属性带 resId，PM 才能正确识别。
 */
public final class AxmlLib {

    private AxmlLib() {
    }

    public static final int ID_name = 0x01010003;
    public static final int ID_authorities = 0x01010018;
    public static final int ID_exported = 0x01010010;
    public static final int ID_appComponentFactory = 0x0101057A;

    private static AndroidManifestBlock load(byte[] axml) throws Exception {
        AndroidManifestBlock m = new AndroidManifestBlock();
        m.readBytes(new ByteArrayInputStream(axml));
        return m;
    }

    public static byte[] setAppComponentFactory(byte[] axml, String cls) throws Exception {
        AndroidManifestBlock m = load(axml);
        ResXmlElement app = m.getApplicationElement();
        if (app == null) return axml;
        app.getOrCreateAndroidAttribute("appComponentFactory", ID_appComponentFactory).setValueAsString(cls);
        m.refreshFull();
        return m.getBytes();
    }

    public static String getAppComponentFactory(byte[] axml) {
        try {
            AndroidManifestBlock m = load(axml);
            ResXmlElement app = m.getApplicationElement();
            if (app == null) return null;
            ResXmlAttribute a = app.searchAttributeByResourceId(ID_appComponentFactory);
            return a != null ? a.getValueAsString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 收集清单里所有 android:name 的值（组件/application 等，用于重命名时保留）。 */
    public static java.util.Set<String> listAndroidNames(byte[] axml) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            AndroidManifestBlock m = load(axml);
            ResXmlElement root = m.getDocumentElement();
            if (root == null) return out;
            java.util.Iterator<ResXmlElement> it = root.recursiveElements();
            while (it.hasNext()) {
                ResXmlElement el = it.next();
                java.util.Iterator<ResXmlAttribute> ai = el.getAttributes();
                while (ai.hasNext()) {
                    ResXmlAttribute at = ai.next();
                    if ("name".equals(at.getName())) {
                        String v = at.getValueAsString();
                        if (v != null && v.indexOf('.') > 0) out.add(v);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static byte[] addProvider(byte[] axml, String name, String authorities, boolean exported) throws Exception {
        AndroidManifestBlock m = load(axml);
        ResXmlElement app = m.getApplicationElement();
        if (app == null) return axml;
        ResXmlElement p = app.newElement("provider");
        p.getOrCreateAndroidAttribute("name", ID_name).setValueAsString(name);
        p.getOrCreateAndroidAttribute("authorities", ID_authorities).setValueAsString(authorities);
        p.getOrCreateAndroidAttribute("exported", ID_exported).setValueAsBoolean(exported);
        m.refreshFull();
        return m.getBytes();
    }
}
