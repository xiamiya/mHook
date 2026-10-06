package cn.mhook.protect;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.jf.dexlib2.Format;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.Label;
import org.jf.dexlib2.builder.MethodImplementationBuilder;
import org.jf.dexlib2.builder.SwitchLabelElement;
import org.jf.dexlib2.builder.instruction.BuilderArrayPayload;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11n;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction12x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction20t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21ih;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21lh;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21s;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22b;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22s;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction23x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction30t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction31c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction31i;
import org.jf.dexlib2.builder.instruction.BuilderInstruction31t;
import org.jf.dexlib2.builder.instruction.BuilderInstruction32x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction3rc;
import org.jf.dexlib2.builder.instruction.BuilderInstruction51l;
import org.jf.dexlib2.builder.instruction.BuilderPackedSwitchPayload;
import org.jf.dexlib2.builder.instruction.BuilderSparseSwitchPayload;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.ExceptionHandler;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.TryBlock;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.instruction.SwitchElement;
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.instruction.WideLiteralInstruction;
import org.jf.dexlib2.iface.instruction.formats.ArrayPayload;
import org.jf.dexlib2.iface.instruction.formats.PackedSwitchPayload;
import org.jf.dexlib2.iface.instruction.formats.SparseSwitchPayload;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.reference.TypeReference;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import cn.mhook.npatch.IProgress;

/**
 * 基础档 · dex 组合混淆：
 *   A. 字符串加密：const-string vX,"s" → const-string vX,"<enc>"; invoke-static/range {vX},StrDec->d; move-result-object vX
 *   B. 控制流混淆（不透明谓词）：方法入口插入 `const/4 v0,1; if-eqz v0,dead; goto real; dead:nop; real:`（恒真，改变 CFG）
 * 加密：每串随机 IV + SHA256(KEY‖IV) 链式密钥流 + 位置偏移 + 自定义 Base64。
 * 保守：含 try/catch 或 switch/fill 载荷的方法不做控制流；不支持指令格式的方法整方法跳过（不改）。
 */
public final class DexStringEncrypt {

    static volatile byte[] KEY = {
            (byte) 0x3B, (byte) 0x7F, (byte) 0x12, (byte) 0xC4, (byte) 0x9A, (byte) 0x55, (byte) 0xE0, (byte) 0x2D,
            (byte) 0x88, (byte) 0x61, (byte) 0x0F, (byte) 0xF3, (byte) 0x4C, (byte) 0xA9, (byte) 0x17, (byte) 0x6E,
            (byte) 0xD2, (byte) 0x30, (byte) 0xBB, (byte) 0x74, (byte) 0x09, (byte) 0xE8, (byte) 0x5D, (byte) 0x93,
            (byte) 0x26, (byte) 0xAF, (byte) 0x41, (byte) 0xC1, (byte) 0x6B, (byte) 0x18, (byte) 0xFE, (byte) 0x57
    };

    static final char[] AL = "/+9876543210zyxwvutsrqponmlkjihgfedcbaZYXWVUTSRQPONMLKJIHGFEDCBA".toCharArray();

    /** 字符串解密器类描述符（可被重命名混淆）。 */
    public static volatile String STRDEC_CLASS = "Lcn/mhook/protect/rt/StrDec;";

    /** 设置字符串加密密钥（wbc 白盒化时每次构建随机）。 */
    public static void setKey(byte[] k) {
        if (k != null && k.length == 32) KEY = k;
    }

    /** 复原默认密钥。 */
    public static void resetKey() {
        KEY = new byte[]{
                (byte) 0x3B, (byte) 0x7F, (byte) 0x12, (byte) 0xC4, (byte) 0x9A, (byte) 0x55, (byte) 0xE0, (byte) 0x2D,
                (byte) 0x88, (byte) 0x61, (byte) 0x0F, (byte) 0xF3, (byte) 0x4C, (byte) 0xA9, (byte) 0x17, (byte) 0x6E,
                (byte) 0xD2, (byte) 0x30, (byte) 0xBB, (byte) 0x74, (byte) 0x09, (byte) 0xE8, (byte) 0x5D, (byte) 0x93,
                (byte) 0x26, (byte) 0xAF, (byte) 0x41, (byte) 0xC1, (byte) 0x6B, (byte) 0x18, (byte) 0xFE, (byte) 0x57
        };
    }

    private static ImmutableMethodReference dec() {
        return new ImmutableMethodReference(STRDEC_CLASS, "d",
                Collections.singletonList("Ljava/lang/String;"), "Ljava/lang/String;");
    }

    private DexStringEncrypt() {
    }

    public static File encryptApk(File inApk, File workDir, File decryptorDex, IProgress prog) throws Exception {
        // 兼容旧调用（仅字符串加密）
        File out = new File(workDir, "protect_obf.apk");
        byte[] dec = readFile(decryptorDex);
        try (ZipFile zip = new ZipFile(inApk);
             ProtectEngine.CountingOutputStream counting = new ProtectEngine.CountingOutputStream(new FileOutputStream(out));
             ZipOutputStream zout = new ZipOutputStream(counting)) {
            byte[] buf = new byte[65536];
            for (var e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                String name = ze.getName();
                byte[] data = null;
                if (classIndex(name) > 0) {
                    int[] c = new int[]{0};
                    byte[] nd = safe(() -> obfuscateDex(readAll(zip, ze), true, false, c, null));
                    if (nd != null) data = nd;
                }
                writeEntry(zout, counting, ze, name, data, buf, zip);
            }
            writeRaw(zout, "classesExtra.dex", dec);
        }
        return out;
    }

    /** 组合混淆：encrypt=字符串加密；cflow=控制流。返回新 dex 字节；无改动返回 null。 */
    public static byte[] obfuscateDex(byte[] dex, boolean encrypt, boolean cflow, int[] encCnt, int[] cfCnt) throws Exception {
        return obfuscateDex(dex, encrypt, cflow, false, true, "", "", encCnt, cfCnt);
    }

    /** scopeAll=混淆范围「全部」；include/exclude=自定义混淆类/排除类（逗号分隔前缀）。 */
    public static byte[] obfuscateDex(byte[] dex, boolean encrypt, boolean cflow,
                                      boolean scopeAll, String include, String exclude,
                                      int[] encCnt, int[] cfCnt) throws Exception {
        return obfuscateDex(dex, encrypt, cflow, false, scopeAll, include, exclude, encCnt, cfCnt);
    }

    /** arith=算术/表达式混淆。 */
    public static byte[] obfuscateDex(byte[] dex, boolean encrypt, boolean cflow, boolean arith,
                                      boolean scopeAll, String include, String exclude,
                                      int[] encCnt, int[] cfCnt) throws Exception {
        DexBackedDexFile source = new DexBackedDexFile(Opcodes.getDefault(), dex);
        final int[] ec = {0}, cc = {0};
        final List<String> inc = split(include), exc = split(exclude);
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                return m -> classAllowed(m.getDefiningClass(), scopeAll, inc, exc)
                        ? rewriteMethod(m, encrypt, cflow, arith, ec, cc) : m;
            }
        });
        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(source);
        List<ClassDef> classes = new ArrayList<>();
        for (ClassDef cd : rewritten.getClasses()) {
            for (Method ignored : cd.getMethods()) {
            }
            classes.add(cd);
        }
        if (ec[0] == 0 && cc[0] == 0) return null;
        if (encCnt != null) encCnt[0] = ec[0];
        if (cfCnt != null) cfCnt[0] = cc[0];
        File tmp = File.createTempFile("strdex", ".dex");
        try {
            DexPool pool = new DexPool(rewritten.getOpcodes());
            for (ClassDef cd : classes) pool.internClass(cd);
            pool.writeTo(new FileDataStore(tmp));
            return readFile(tmp);
        } finally {
            tmp.delete();
        }
    }

    private static Method rewriteMethod(Method m, boolean encrypt, boolean cflow, boolean arith, int[] eco, int[] cco) {
        MethodImplementation impl = m.getImplementation();
        if (impl == null) return m;

        List<Instruction> list = new ArrayList<>();
        List<Integer> addrs = new ArrayList<>();
        Set<Integer> targets = new HashSet<>();
        List<TryBlock<? extends ExceptionHandler>> tries = new ArrayList<>();
        int addr = 0;
        boolean hasStr = false, hasPayload = false, hasConst = false;
        for (Instruction insn : impl.getInstructions()) {
            list.add(insn);
            addrs.add(addr);
            Format f = insn.getOpcode().format;
            if (f == Format.PackedSwitchPayload || f == Format.SparseSwitchPayload
                    || f == Format.ArrayPayload || f == Format.Format31t) {
                hasPayload = true;
            }
            if (f == Format.PackedSwitchPayload) {
                for (SwitchElement se : ((PackedSwitchPayload) insn).getSwitchElements())
                    targets.add(addr + se.getOffset());
            } else if (f == Format.SparseSwitchPayload) {
                for (SwitchElement se : ((SparseSwitchPayload) insn).getSwitchElements())
                    targets.add(addr + se.getOffset());
            }
            if (insn instanceof OffsetInstruction) targets.add(addr + ((OffsetInstruction) insn).getCodeOffset());
            if (insn.getOpcode() == Opcode.CONST_STRING || insn.getOpcode() == Opcode.CONST_STRING_JUMBO) hasStr = true;
            Opcode iop = insn.getOpcode();
            if ((iop == Opcode.CONST_4 || iop == Opcode.CONST_16 || iop == Opcode.CONST)
                    && insn instanceof NarrowLiteralInstruction
                    && ((NarrowLiteralInstruction) insn).getNarrowLiteral() != 0) hasConst = true;
            addr += insn.getCodeUnits();
        }
        int codeSize = addr;
        for (TryBlock<? extends ExceptionHandler> tb : impl.getTryBlocks()) {
            tries.add(tb);
            targets.add(tb.getStartCodeAddress());
            targets.add(tb.getStartCodeAddress() + tb.getCodeUnitCount());
            for (ExceptionHandler eh : tb.getExceptionHandlers()) targets.add(eh.getHandlerCodeAddress());
        }

        int params = 0;
        for (Object ignored : m.getParameters()) params++;
        int insSize = params + (((m.getAccessFlags() & org.jf.dexlib2.AccessFlags.STATIC.getValue()) != 0) ? 0 : 1);
        boolean cfEligible = cflow && tries.isEmpty() && !hasPayload && impl.getRegisterCount() > insSize;
        boolean encNeeded = encrypt && hasStr;
        boolean arithEligible = arith && hasConst && !hasPayload;
        if (!cfEligible && !encNeeded && !arithEligible) return m;

        MethodImplementationBuilder mib = new MethodImplementationBuilder(impl.getRegisterCount());
        int encLocal = 0;
        if (cfEligible) {
            // const/4 v0, 1 ; if-eqz v0, dead ; goto real ; dead: nop ; real:
            mib.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 1));
            mib.addInstruction(new BuilderInstruction21t(Opcode.IF_EQZ, 0, mib.getLabel("profDead")));
            mib.addInstruction(new BuilderInstruction30t(Opcode.GOTO_32, mib.getLabel("profReal")));
            mib.addLabel("profDead");
            mib.addInstruction(new BuilderInstruction10x(Opcode.NOP));
            mib.addLabel("profReal");
        }
        for (int i = 0; i < list.size(); i++) {
            Instruction insn = list.get(i);
            int a = addrs.get(i);
            if (targets.contains(a)) mib.addLabel("L" + a);
            if (encrypt && (insn.getOpcode() == Opcode.CONST_STRING || insn.getOpcode() == Opcode.CONST_STRING_JUMBO)) {
                int regA = ((OneRegisterInstruction) insn).getRegisterA();
                Reference ref = ((ReferenceInstruction) insn).getReference();
                if (!(ref instanceof StringReference)) return m;
                String s = ((StringReference) ref).getString();
                mib.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, regA,
                        new ImmutableStringReference(enc(s))));
                mib.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, regA, 1, dec()));
                mib.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, regA));
                encLocal++;
            } else if (arith && (insn.getOpcode() == Opcode.CONST_4 || insn.getOpcode() == Opcode.CONST_16
                    || insn.getOpcode() == Opcode.CONST) && insn instanceof NarrowLiteralInstruction
                    && ((NarrowLiteralInstruction) insn).getNarrowLiteral() != 0) {
                int regA = regA(insn);
                int nVal = ((NarrowLiteralInstruction) insn).getNarrowLiteral();
                int k = 1 + (int) (Math.random() * 126);   // 1..126
                int mVal = nVal ^ k;                        // (n^k) ^ k == n
                if (mVal >= -32768 && mVal <= 32767) {
                    mib.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, regA, (short) mVal));
                } else {
                    mib.addInstruction(new BuilderInstruction31i(Opcode.CONST, regA, mVal));
                }
                mib.addInstruction(new BuilderInstruction22b(Opcode.XOR_INT_LIT8, regA, regA, (byte) k));
                cco[0]++;
            } else {
                BuilderInstruction bi = convert(insn, a, mib);
                if (bi == null) return m;
                mib.addInstruction(bi);
            }
        }
        if (targets.contains(codeSize)) mib.addLabel("L" + codeSize);
        for (TryBlock<? extends ExceptionHandler> tb : tries) {
            Label from = mib.getLabel("L" + tb.getStartCodeAddress());
            Label to = mib.getLabel("L" + (tb.getStartCodeAddress() + tb.getCodeUnitCount()));
            for (ExceptionHandler eh : tb.getExceptionHandlers()) {
                Label handler = mib.getLabel("L" + eh.getHandlerCodeAddress());
                String type = eh.getExceptionType();
                if (type == null) mib.addCatch(from, to, handler);
                else mib.addCatch(type, from, to, handler);
            }
        }
        ImmutableMethodImplementation nimpl = ImmutableMethodImplementation.of(mib.getMethodImplementation());
        ImmutableMethod nm = new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(),
                m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), nimpl);
        eco[0] += encLocal;
        if (cfEligible) cco[0]++;
        return nm;
    }

    private static BuilderInstruction convert(Instruction insn, int addr, MethodImplementationBuilder mib) {
        Opcode op = insn.getOpcode();
        OffsetInstruction oi = insn instanceof OffsetInstruction ? (OffsetInstruction) insn : null;
        ReferenceInstruction ri = insn instanceof ReferenceInstruction ? (ReferenceInstruction) insn : null;
        switch (op.format) {
            case Format10x: return new BuilderInstruction10x(op);
            case Format10t: return new BuilderInstruction10t(op, mib.getLabel("L" + (addr + oi.getCodeOffset())));
            case Format11n: return new BuilderInstruction11n(op, regA(insn), ((NarrowLiteralInstruction) insn).getNarrowLiteral());
            case Format11x: return new BuilderInstruction11x(op, regA(insn));
            case Format12x: return new BuilderInstruction12x(op, regA(insn), regB(insn));
            case Format20t: return new BuilderInstruction20t(op, mib.getLabel("L" + (addr + oi.getCodeOffset())));
            case Format21c: return new BuilderInstruction21c(op, regA(insn), immRef(ri.getReference()));
            case Format21ih: return new BuilderInstruction21ih(op, regA(insn), ((NarrowLiteralInstruction) insn).getNarrowLiteral());
            case Format21lh: return new BuilderInstruction21lh(op, regA(insn), ((WideLiteralInstruction) insn).getWideLiteral());
            case Format21s: return new BuilderInstruction21s(op, regA(insn), ((NarrowLiteralInstruction) insn).getNarrowLiteral());
            case Format21t: return new BuilderInstruction21t(op, regA(insn), mib.getLabel("L" + (addr + oi.getCodeOffset())));
            case Format22b: return new BuilderInstruction22b(op, regA(insn), regB(insn), ((NarrowLiteralInstruction) insn).getNarrowLiteral());
            case Format22c: return new BuilderInstruction22c(op, regA(insn), regB(insn), immRef(ri.getReference()));
            case Format22s: return new BuilderInstruction22s(op, regA(insn), regB(insn), ((NarrowLiteralInstruction) insn).getNarrowLiteral());
            case Format22t: return new BuilderInstruction22t(op, regA(insn), regB(insn), mib.getLabel("L" + (addr + oi.getCodeOffset())));
            case Format22x: return new BuilderInstruction22x(op, regA(insn), regB(insn));
            case Format23x: return new BuilderInstruction23x(op, regA(insn), regB(insn), ((ThreeRegisterInstruction) insn).getRegisterC());
            case Format30t: return new BuilderInstruction30t(op, mib.getLabel("L" + (addr + oi.getCodeOffset())));
            case Format31c: return new BuilderInstruction31c(op, regA(insn), immRef(ri.getReference()));
            case Format31i: return new BuilderInstruction31i(op, regA(insn), ((NarrowLiteralInstruction) insn).getNarrowLiteral());
            case Format31t: return new BuilderInstruction31t(op, regA(insn), mib.getLabel("L" + (addr + oi.getCodeOffset())));
            case Format32x: return new BuilderInstruction32x(op, regA(insn), regB(insn));
            case Format35c: {
                FiveRegisterInstruction fi = (FiveRegisterInstruction) insn;
                return new BuilderInstruction35c(op, fi.getRegisterCount(), fi.getRegisterC(), fi.getRegisterD(),
                        fi.getRegisterE(), fi.getRegisterF(), fi.getRegisterG(), immRef(ri.getReference()));
            }
            case Format3rc: {
                RegisterRangeInstruction rr = (RegisterRangeInstruction) insn;
                return new BuilderInstruction3rc(op, rr.getStartRegister(), rr.getRegisterCount(), immRef(ri.getReference()));
            }
            case Format51l: return new BuilderInstruction51l(op, regA(insn), ((WideLiteralInstruction) insn).getWideLiteral());
            case ArrayPayload: {
                ArrayPayload ap = (ArrayPayload) insn;
                return new BuilderArrayPayload(ap.getElementWidth(), ap.getArrayElements());
            }
            case PackedSwitchPayload: {
                PackedSwitchPayload pp = (PackedSwitchPayload) insn;
                List<? extends SwitchElement> ses = pp.getSwitchElements();
                List<Label> labels = new ArrayList<>();
                int startKey = ses.isEmpty() ? 0 : ses.get(0).getKey();
                for (SwitchElement se : ses) labels.add(mib.getLabel("L" + (addr + se.getOffset())));
                return new BuilderPackedSwitchPayload(startKey, labels);
            }
            case SparseSwitchPayload: {
                SparseSwitchPayload sp = (SparseSwitchPayload) insn;
                List<SwitchLabelElement> els = new ArrayList<>();
                for (SwitchElement se : sp.getSwitchElements())
                    els.add(new SwitchLabelElement(se.getKey(), mib.getLabel("L" + (addr + se.getOffset()))));
                return new BuilderSparseSwitchPayload(els);
            }
            default: return null;
        }
    }

    private static int regA(Instruction i) {
        return ((OneRegisterInstruction) i).getRegisterA();
    }

    private static int regB(Instruction i) {
        return ((TwoRegisterInstruction) i).getRegisterB();
    }

    private static Reference immRef(Reference r) {
        if (r instanceof StringReference) return new ImmutableStringReference(((StringReference) r).getString());
        if (r instanceof TypeReference) return new ImmutableTypeReference(((TypeReference) r).getType());
        if (r instanceof FieldReference) {
            FieldReference f = (FieldReference) r;
            return new ImmutableFieldReference(f.getDefiningClass(), f.getName(), f.getType());
        }
        if (r instanceof MethodReference) {
            MethodReference m = (MethodReference) r;
            return new ImmutableMethodReference(m.getDefiningClass(), m.getName(), m.getParameterTypes(), m.getReturnType());
        }
        return r;
    }

    // ---------------- 类范围过滤 ----------------
    private static List<String> split(String csv) {
        List<String> r = new ArrayList<>();
        if (csv == null) return r;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) r.add(t);
        }
        return r;
    }

    private static boolean classAllowed(String definingClass, boolean scopeAll, List<String> inc, List<String> exc) {
        if (definingClass == null) return false;
        String dotted = definingClass;
        if (dotted.startsWith("L") && dotted.endsWith(";")) dotted = dotted.substring(1, dotted.length() - 1);
        dotted = dotted.replace('/', '.');
        for (String p : exc) if (prefixMatch(p, dotted)) return false;
        if (scopeAll || inc.isEmpty()) return true;
        for (String p : inc) if (prefixMatch(p, dotted)) return true;
        return false;
    }

    private static boolean prefixMatch(String pat, String cls) {
        String p = pat.trim();
        while (p.endsWith("*")) p = p.substring(0, p.length() - 1);
        while (p.endsWith(".")) p = p.substring(0, p.length() - 1);
        return !p.isEmpty() && cls.startsWith(p);
    }

    // ---------------- 加密 ----------------
    private static final SecureRandom RND = new SecureRandom();

    static String enc(String s) {
        try {
            byte[] pt = s.getBytes("UTF-8");
            byte[] iv = new byte[4];
            RND.nextBytes(iv);
            byte[] ks = keystream(iv, pt.length);
            byte[] ct = new byte[pt.length];
            for (int i = 0; i < pt.length; i++) {
                int c = pt[i] & 0xFF;
                c ^= ks[i] & 0xFF;
                c = (c + ((i * 7) & 0xFF)) & 0xFF;
                ct[i] = (byte) c;
            }
            byte[] data = new byte[4 + ct.length];
            System.arraycopy(iv, 0, data, 0, 4);
            System.arraycopy(ct, 0, data, 4, ct.length);
            return b64(data);
        } catch (Throwable t) {
            return s;
        }
    }

    static byte[] keystream(byte[] iv, int len) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] seed = new byte[36];
        System.arraycopy(KEY, 0, seed, 0, 32);
        System.arraycopy(iv, 0, seed, 32, 4);
        byte[] blk = md.digest(seed);
        byte[] out = new byte[len];
        int p = 0;
        while (p < len) {
            int c = Math.min(blk.length, len - p);
            System.arraycopy(blk, 0, out, p, c);
            p += c;
            blk = md.digest(blk);
        }
        return out;
    }

    static String b64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i + 3 <= d.length) {
            int b = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8) | (d[i + 2] & 0xFF);
            sb.append(AL[(b >> 18) & 63]).append(AL[(b >> 12) & 63]).append(AL[(b >> 6) & 63]).append(AL[b & 63]);
            i += 3;
        }
        int rem = d.length - i;
        if (rem == 1) {
            int b = (d[i] & 0xFF) << 16;
            sb.append(AL[(b >> 18) & 63]).append(AL[(b >> 12) & 63]).append('=').append('=');
        } else if (rem == 2) {
            int b = ((d[i] & 0xFF) << 16) | ((d[i + 1] & 0xFF) << 8);
            sb.append(AL[(b >> 18) & 63]).append(AL[(b >> 12) & 63]).append(AL[(b >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    // ---------------- 工具 ----------------
    private static <T> T safe(Throwing<T> t) {
        try {
            return t.get();
        } catch (Throwable e) {
            return null;
        }
    }

    interface Throwing<T> {
        T get() throws Exception;
    }

    private static void writeEntry(ZipOutputStream zout, ProtectEngine.CountingOutputStream counting,
                                   ZipEntry ze, String name, byte[] data, byte[] buf, ZipFile zip) throws Exception {
        ZipEntry ne = new ZipEntry(name);
        if (data != null) {
            ne.setMethod(ZipEntry.DEFLATED);
        } else if (ze.getMethod() == ZipEntry.STORED) {
            ne.setMethod(ZipEntry.STORED);
            ne.setSize(ze.getSize());
            ne.setCompressedSize(ze.getCompressedSize());
            ne.setCrc(ze.getCrc());
        } else {
            ne.setMethod(ZipEntry.DEFLATED);
        }
        ProtectEngine.alignStoreEntry(counting.count(), ne, name);
        zout.putNextEntry(ne);
        if (data != null) {
            zout.write(data);
        } else {
            try (InputStream is = zip.getInputStream(ze)) {
                int n;
                while ((n = is.read(buf)) != -1) zout.write(buf, 0, n);
            }
        }
        zout.closeEntry();
    }

    private static void writeRaw(ZipOutputStream zout, String name, byte[] data) throws Exception {
        ZipEntry ne = new ZipEntry(name);
        ne.setMethod(ZipEntry.DEFLATED);
        zout.putNextEntry(ne);
        zout.write(data);
        zout.closeEntry();
    }

    private static int classIndex(String name) {
        if (!name.endsWith(".dex") || !name.startsWith("classes")) return 0;
        String mid = name.substring(7, name.length() - 4);
        if (mid.isEmpty()) return 1;
        try {
            return Integer.parseInt(mid);
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] readAll(ZipFile zip, ZipEntry e) throws Exception {
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static byte[] readFile(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
