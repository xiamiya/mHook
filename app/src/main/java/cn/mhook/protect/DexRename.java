package cn.mhook.protect;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DEX 重命名：类名（typeMap，描述符->新描述符，含内部/合成类自动映射）
 * + 方法名（methodNames，原名->新名，仅对 methodRenamable 中的类生效）
 * + 字段名（fieldNames）。
 */
public final class DexRename {

    private DexRename() {
    }

    public static byte[] rename(byte[] dex, Map<String, String> typeMap, File tmpDir) throws IOException {
        return rename(dex, typeMap, java.util.Collections.emptySet(), java.util.Collections.emptyMap(),
                java.util.Collections.emptyMap(), tmpDir);
    }

    public static byte[] rename(byte[] dex, Map<String, String> typeMap,
                                final java.util.Set<String> methodRenamable,
                                final Map<String, String> methodNames,
                                final Map<String, String> fieldNames, File tmpDir) throws IOException {
        DexBackedDexFile src = new DexBackedDexFile(Opcodes.getDefault(), dex);
        // 自动映射内部类/合成类（Outer$Inner;、Outer$$ExternalSyntheticLambda0;）
        final Map<String, String> eff = new java.util.HashMap<>(typeMap);
        for (ClassDef cd : src.getClasses()) {
            String t = cd.getType();
            if (eff.containsKey(t)) continue;
            for (Map.Entry<String, String> e : typeMap.entrySet()) {
                String base = e.getKey();
                if (base.endsWith(";")) base = base.substring(0, base.length() - 1);
                if (t.startsWith(base + "$")) {
                    String suffix = t.substring(base.length());
                    String nb = e.getValue();
                    if (nb.endsWith(";")) nb = nb.substring(0, nb.length() - 1);
                    eff.put(t, nb + suffix);
                    break;
                }
            }
        }
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<String> getTypeRewriter(Rewriters rewriters) {
                return new Rewriter<String>() {
                    @Override
                    public String rewrite(String value) {
                        String n = eff.get(value);
                        return n != null ? n : value;
                    }
                };
            }

            @Override
            public Rewriter<MethodReference> getMethodReferenceRewriter(Rewriters rewriters) {
                final Rewriter<String> tr = rewriters.getTypeRewriter();
                return ref -> {
                    String dc = tr.rewrite(ref.getDefiningClass());
                    String name = ref.getName();
                    if (methodRenamable.contains(ref.getDefiningClass()) && methodNames.containsKey(name)) {
                        name = methodNames.get(name);
                    }
                    List<String> params = new ArrayList<>();
                    for (CharSequence p : ref.getParameterTypes()) params.add(tr.rewrite(p.toString()));
                    return new ImmutableMethodReference(dc, name, params, tr.rewrite(ref.getReturnType()));
                };
            }

            @Override
            public Rewriter<FieldReference> getFieldReferenceRewriter(Rewriters rewriters) {
                final Rewriter<String> tr = rewriters.getTypeRewriter();
                return ref -> {
                    String dc = tr.rewrite(ref.getDefiningClass());
                    String name = ref.getName();
                    if (methodRenamable.contains(ref.getDefiningClass()) && fieldNames.containsKey(name)) {
                        name = fieldNames.get(name);
                    }
                    return new ImmutableFieldReference(dc, name, tr.rewrite(ref.getType()));
                };
            }
        });
        org.jf.dexlib2.iface.DexFile rewritten = rw.getDexFileRewriter().rewrite(src);
        DexPool pool = new DexPool(rewritten.getOpcodes());
        for (ClassDef cd : rewritten.getClasses()) pool.internClass(cd);
        File f = new File(tmpDir, "ren_" + System.nanoTime() + ".dex");
        pool.writeTo(new FileDataStore(f));
        byte[] out = readFile(f);
        f.delete();
        return out;
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) != -1) off += n;
            return b;
        }
    }
}
