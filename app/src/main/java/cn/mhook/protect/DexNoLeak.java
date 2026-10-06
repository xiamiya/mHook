package cn.mhook.protect;

import java.io.File;

import org.jf.dexlib2.Format;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/**
 * 日志防泄漏：把 System.out/err 重定向到注入类的空输出流字段；
 * 把 Throwable.printStackTrace() 重定向到空实现。只换引用，不改寄存器布局。
 */
public final class DexNoLeak {

    private static final String SYS = "Ljava/lang/System;";
    private static final String THROW = "Ljava/lang/Throwable;";
    private static final String PS = "Ljava/io/PrintStream;";
    /** 空输出/空栈类描述符（可被重命名混淆）。 */
    public static volatile String NOLEAK_CLASS = "Lcn/mhook/protect/rt/NoLeak;";

    private DexNoLeak() {
    }

    public static byte[] rewriteDex(byte[] dex, int[] cnt) throws Exception {
        if (indexOf(dex, "java/lang/System") < 0 && indexOf(dex, "printStackTrace") < 0) return null;
        DexBackedDexFile source = new DexBackedDexFile(Opcodes.getDefault(), dex);
        final int[] count = {0};
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return insn -> {
                    if (!(insn instanceof ReferenceInstruction)) return insn;
                    Reference ref = ((ReferenceInstruction) insn).getReference();
                    if (ref instanceof FieldReference) {
                        FieldReference fr = (FieldReference) ref;
                        if (insn.getOpcode() == Opcode.SGET_OBJECT && SYS.equals(fr.getDefiningClass())
                                && PS.equals(fr.getType()) && ("out".equals(fr.getName()) || "err".equals(fr.getName()))) {
                            count[0]++;
                            int reg = ((OneRegisterInstruction) insn).getRegisterA();
                            return new BuilderInstruction21c(Opcode.SGET_OBJECT, reg,
                                    new ImmutableFieldReference(NOLEAK_CLASS, fr.getName(), PS));
                        }
                        return insn;
                    }
                    if (ref instanceof MethodReference) {
                        MethodReference mr = (MethodReference) ref;
                        if (THROW.equals(mr.getDefiningClass()) && "printStackTrace".equals(mr.getName())
                                && mr.getParameterTypes().isEmpty() && "V".equals(mr.getReturnType())) {
                            count[0]++;
                            MethodReference tr = new ImmutableMethodReference(NOLEAK_CLASS, "tr",
                                    java.util.Collections.singletonList(THROW), "V");
                            Opcode op = insn.getOpcode();
                            if (op.format == Format.Format35c) {
                                FiveRegisterInstruction fi = (FiveRegisterInstruction) insn;
                                return new BuilderInstruction35c(Opcode.INVOKE_STATIC, fi.getRegisterCount(),
                                        fi.getRegisterC(), fi.getRegisterD(), fi.getRegisterE(),
                                        fi.getRegisterF(), fi.getRegisterG(), tr);
                            }
                            if (op.format == Format.Format3rc) {
                                RegisterRangeInstruction rr = (RegisterRangeInstruction) insn;
                                return new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE,
                                        rr.getStartRegister(), rr.getRegisterCount(), tr);
                            }
                        }
                        return insn;
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
        File tmp = File.createTempFile("noleak", ".dex");
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
