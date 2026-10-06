package cn.mhook.protect;

import java.io.File;

import org.jf.dexlib2.Format;
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
 * 通用静态调用重定向：把 invoke 到 (fromClass,fromName) 的引用改为 (toClass,toName)（同签名）。
 */
public final class DexInvokeRedirect {

    private DexInvokeRedirect() {
    }

    public static byte[] rewriteDex(byte[] dex, final String fromClass, final String fromName,
                                    final String toClass, final String toName, int[] cnt) throws Exception {
        if (indexOf(dex, fromClass) < 0) return null;
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
                    if (!fromClass.equals(mr.getDefiningClass()) || !fromName.equals(mr.getName())) return insn;
                    count[0]++;
                    MethodReference to = new ImmutableMethodReference(toClass, toName,
                            mr.getParameterTypes(), mr.getReturnType());
                    org.jf.dexlib2.Opcode op = insn.getOpcode();
                    if (op.format == Format.Format35c) {
                        FiveRegisterInstruction fi = (FiveRegisterInstruction) insn;
                        return new BuilderInstruction35c(op, fi.getRegisterCount(), fi.getRegisterC(), fi.getRegisterD(),
                                fi.getRegisterE(), fi.getRegisterF(), fi.getRegisterG(), to);
                    }
                    if (op.format == Format.Format3rc) {
                        RegisterRangeInstruction rr = (RegisterRangeInstruction) insn;
                        return new BuilderInstruction3rc(op, rr.getStartRegister(), rr.getRegisterCount(), to);
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
                }
            }
        }
        if (count[0] == 0) return null;
        if (cnt != null) cnt[0] = count[0];
        File tmp = File.createTempFile("invredir", ".dex");
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
