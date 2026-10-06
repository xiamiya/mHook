package cn.mhook.protect;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.function.Predicate;

/**
 * DPT 方法/类抽取：把 dex 按谓词拆成「保留」与「抽取(payload)」两个 dex。
 * 抽取出的类从主 dex 移除，改由运行时自定义 ClassLoader 从加密 payload 还原。
 */
public final class DexClassSplit {

    private DexClassSplit() {
    }

    public static final class Result {
        public byte[] keep;
        public byte[] payload;
        public int extracted;
        public int kept;
    }

    public static Result split(byte[] dex, Predicate<String> extract, File tmpDir) throws IOException {
        DexBackedDexFile src = new DexBackedDexFile(Opcodes.getDefault(), dex);
        java.util.List<ClassDef> kept = new java.util.ArrayList<>();
        java.util.List<ClassDef> pay = new java.util.ArrayList<>();
        for (ClassDef cd : src.getClasses()) {
            if (extract.test(cd.getType())) pay.add(cd);
            else kept.add(cd);
        }
        DexPool keepPool = new DexPool(src.getOpcodes());
        DexPool payPool = new DexPool(src.getOpcodes());
        for (ClassDef cd : kept) keepPool.internClass(cd);
        for (ClassDef cd : pay) payPool.internClass(cd);
        Result r = new Result();
        r.extracted = pay.size();
        r.kept = kept.size();
        try {
            File kf = new File(tmpDir, "keep_" + System.nanoTime() + ".dex");
            keepPool.writeTo(new FileDataStore(kf));
            r.keep = readFile(kf);
            kf.delete();
        } catch (Throwable t) {
            r.keep = null;
        }
        if (!pay.isEmpty()) {
            File pf = new File(tmpDir, "pay_" + System.nanoTime() + ".dex");
            payPool.writeTo(new FileDataStore(pf));
            r.payload = readFile(pf);
            pf.delete();
        }
        return r;
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
