package cn.mhook.protect;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.jf.dexlib2.Format;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/**
 * 移除日志/调试代码（指令级）：把 invoke 到 Landroid/util/Log;->{v,d,i,w,e,wtf,println} 的
 * 方法引用改成注入的 Lcn/mhook/protect/rt/LogKill;（同签名空实现）。只换引用，不动寄存器/指令长度。
 */
public final class DexLogKill {

    private static final String LOG = "Landroid/util/Log;";
    /** 空日志类描述符（可被重命名混淆）。 */
    public static volatile String KILL_CLASS = "Lcn/mhook/protect/rt/LogKill;";
    private static final Set<String> NAMES = new HashSet<>(Arrays.asList(
            "v", "d", "i", "w", "e", "wtf", "println"));

    private DexLogKill() {
    }

    /** 重写单个 dex，返回新字节；无改动返回 null。cnt[0] = 重定向的调用数。 */
    public static byte[] rewriteDex(byte[] dex, int[] cnt) throws Exception {
        if (indexOf(dex, "android/util/Log") < 0) return null;
        DexBackedDexFile source = new DexBackedDexFile(Opcodes.getDefault(), dex);
        final int[] count = {0};
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return insn -> {
                    if (!(insn instanceof ReferenceInstruction)) return insn;
                    Reference ref = ((ReferenceInstruction) insn).getReference();
                    if (!(ref instanceof MethodReference)) return insn;
                    MethodReference mr = (MethodReference) ref;
                    if (!LOG.equals(mr.getDefiningClass()) || !NAMES.contains(mr.getName())) return insn;
                    count[0]++;
                    MethodReference kill = new ImmutableMethodReference(KILL_CLASS, mr.getName(),
                            mr.getParameterTypes(), mr.getReturnType());
                    Opcode op = insn.getOpcode();
                    if (op.format == Format.Format35c) {
                        FiveRegisterInstruction fi = (FiveRegisterInstruction) insn;
                        return new BuilderInstruction35c(op, fi.getRegisterCount(), fi.getRegisterC(), fi.getRegisterD(),
                                fi.getRegisterE(), fi.getRegisterF(), fi.getRegisterG(), kill);
                    }
                    if (op.format == Format.Format3rc) {
                        RegisterRangeInstruction rr = (RegisterRangeInstruction) insn;
                        return new BuilderInstruction3rc(op, rr.getStartRegister(), rr.getRegisterCount(), kill);
                    }
                    return insn;
                };
            }
        });
        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(source);
        for (ClassDef cd : rewritten.getClasses()) {
            for (Method m : cd.getMethods()) {
                MethodImplementation mi = m.getImplementation();
                if (mi != null) for (Instruction ignored : mi.getInstructions()) {
                    // 触发指令重写
                }
            }
        }
        if (count[0] == 0) return null;
        if (cnt != null) cnt[0] = count[0];
        File tmp = File.createTempFile("logkill", ".dex");
        try {
            DexPool pool = new DexPool(rewritten.getOpcodes());
            for (ClassDef cd : rewritten.getClasses()) pool.internClass(cd);
            pool.writeTo(new FileDataStore(tmp));
            return java.nio.file.Files.readAllBytes(tmp.toPath());
        } finally {
            tmp.delete();
        }
    }

    private static int indexOf(byte[] d, String s) {
        byte[] n = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + n.length <= d.length; i++) {
            for (int j = 0; j < n.length; j++) if (d[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }
}
