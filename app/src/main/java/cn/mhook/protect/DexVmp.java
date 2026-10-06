package cn.mhook.protect;

import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.builder.MethodImplementationBuilder;
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction11x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction21s;
import org.jf.dexlib2.builder.instruction.BuilderInstruction22c;
import org.jf.dexlib2.builder.instruction.BuilderInstruction23x;
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.MethodParameter;
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction;
import org.jf.dexlib2.iface.instruction.OffsetInstruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction;
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction;
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction;
import org.jf.dexlib2.iface.instruction.formats.ArrayPayload;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.immutable.ImmutableClassDef;
import org.jf.dexlib2.immutable.ImmutableMethod;
import org.jf.dexlib2.immutable.ImmutableMethodImplementation;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VMP：把可虚拟化的静态方法编译为自定义字节码（bundle），方法体替换为 Vmp.run(key,id,args)。
 * 支持方法间互调（invoke-static 到同样被虚拟化的方法）。
 */
public final class DexVmp {

    private static final String VMP = "Lcn/mhook/protect/rt/Vmp;";
    private static final int OP_END = 0x00, OP_MOVE = 0x02, OP_CONST = 0x01, OP_ADD = 0x03, OP_SUB = 0x04,
            OP_MUL = 0x05, OP_AND = 0x06, OP_OR = 0x07, OP_XOR = 0x08, OP_NEG = 0x09, OP_SHL = 0x0A,
            OP_SHR = 0x0B, OP_USHR = 0x0C, OP_NOT = 0x13, OP_GOTO = 0x26, OP_RET = 0x30, OP_RET_VOID = 0x31;
    private static final int OP_IF_EQZ = 0x20, OP_IF_NEZ = 0x21, OP_IF_LTZ = 0x22, OP_IF_GEZ = 0x23,
            OP_IF_GTZ = 0x24, OP_IF_LEZ = 0x25;
    private static final int OP_IF_EQ = 0x40, OP_IF_NE = 0x41, OP_IF_LT = 0x42,
            OP_IF_GE = 0x43, OP_IF_GT = 0x44, OP_IF_LE = 0x45;
    private static final int OP_ADD_LIT = 0x50, OP_SUB_LIT = 0x51, OP_RSUB_LIT = 0x52, OP_MUL_LIT = 0x53,
            OP_AND_LIT = 0x54, OP_OR_LIT = 0x55, OP_XOR_LIT = 0x56,
            OP_SHL_LIT = 0x57, OP_SHR_LIT = 0x58, OP_USHR_LIT = 0x59;
    private static final int OP_RET_LONG = 0x32, OP_ADD64 = 0x61, OP_SUB64 = 0x62, OP_MUL64 = 0x63,
            OP_AND64 = 0x64, OP_OR64 = 0x65, OP_XOR64 = 0x66, OP_SHL64 = 0x67, OP_SHR64 = 0x68,
            OP_USHR64 = 0x69, OP_NEG64 = 0x6A, OP_NOT64 = 0x6B, OP_CMP_LONG = 0x6C,
            OP_INT_TO_LONG = 0x6D, OP_LONG_TO_INT = 0x6E,
            OP_INT_TO_BYTE = 0x74, OP_INT_TO_CHAR = 0x75, OP_INT_TO_SHORT = 0x76,
            OP_NEWARRAY = 0x70, OP_AGET = 0x71, OP_APUT = 0x72, OP_ARRAYLEN = 0x73,
            OP_INVOKE_BUILTIN = 0x80, OP_FILLARRAY = 0x81, OP_CALL = 0x82,
            OP_CONST_STR = 0x90, OP_STR_LEN = 0x91, OP_STR_EQ = 0x92, OP_STR_CAT = 0x93,
            OP_STR_CHARAT = 0x94, OP_INT_PARSE = 0x95, OP_STR_VALOF = 0x96, OP_STR_CMP = 0x97;

    private DexVmp() {
    }

    public static final class Result {
        public byte[] dex;
        public byte[] bundle;
        public int count;
    }

    public static Result rewriteDex(byte[] dex, boolean scopeAll, String include, String exclude,
                                    String bundleKey, int[] cnt) throws Exception {
        DexBackedDexFile source = new DexBackedDexFile(Opcodes.getDefault(), dex);
        List<String> inc = split(include), exc = split(exclude);
        Map<String, Method> byMethod = new HashMap<>();
        List<Method> all = new ArrayList<>();
        for (ClassDef cd : source.getClasses()) {
            for (Method m : cd.getMethods()) {
                all.add(m);
                byMethod.put(key(m), m);
            }
        }
        // 定点迭代：判定可虚拟化方法
        Set<String> eligible = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Method m : all) {
                String k = key(m);
                if (eligible.contains(k)) continue;
                if (!methodAllowed(m, scopeAll, inc, exc)) continue;
                if (translateInternal(m, null, eligible, byMethod, null) != null) {
                    eligible.add(k);
                    changed = true;
                }
            }
        }
        if (eligible.isEmpty()) return null;
        Map<String, Integer> idMap = new HashMap<>();
        int id = 0;
        for (Method m : all) {
            String k = key(m);
            if (eligible.contains(k)) idMap.put(k, id++);
        }
        // 生成 bundle 与替换方法体
        ByteArrayOutputStream progBuf = new ByteArrayOutputStream();
        List<byte[]> progs = new ArrayList<>();
        for (Method m : all) {
            String k = key(m);
            if (!eligible.contains(k)) continue;
            byte[] prog = translateInternal(m, idMap, eligible, byMethod, null);
            progs.add(prog);
        }
        progBuf.write(progs.size() & 0xFF);
        for (byte[] prog : progs) {
            int len = prog.length;
            progBuf.write(len & 0xFF);
            progBuf.write((len >> 8) & 0xFF);
            progBuf.write((len >> 16) & 0xFF);
            progBuf.write((len >> 24) & 0xFF);
            progBuf.write(prog);
        }
        // 重建 dex
        List<ClassDef> out = new ArrayList<>();
        int count = 0;
        for (ClassDef cd : source.getClasses()) {
            List<Method> methods = new ArrayList<>();
            boolean ch = false;
            for (Method m : cd.getMethods()) {
                String k = key(m);
                if (eligible.contains(k)) {
                    methods.add(stub(m, bundleKey, idMap.get(k)));
                    ch = true;
                    count++;
                } else {
                    methods.add(m);
                }
            }
            if (ch) {
                out.add(new ImmutableClassDef(cd.getType(), cd.getAccessFlags(), cd.getSuperclass(),
                        cd.getInterfaces(), cd.getSourceFile(), cd.getAnnotations(), cd.getFields(), methods));
            } else {
                out.add(cd);
            }
        }
        Result r = new Result();
        r.bundle = progBuf.toByteArray();
        r.count = count;
        File tmp = File.createTempFile("vmp", ".dex");
        try {
            org.jf.dexlib2.iface.DexFile rewritten =
                    new org.jf.dexlib2.immutable.ImmutableDexFile(source.getOpcodes(), out);
            DexPool pool = new DexPool(rewritten.getOpcodes());
            for (ClassDef cd : rewritten.getClasses()) pool.internClass(cd);
            pool.writeTo(new FileDataStore(tmp));
            r.dex = java.nio.file.Files.readAllBytes(tmp.toPath());
        } finally {
            tmp.delete();
        }
        if (cnt != null) cnt[0] = count;
        return r;
    }

    private static String key(Method m) {
        StringBuilder sb = new StringBuilder(m.getDefiningClass()).append('|').append(m.getName()).append('(');
        for (CharSequence p : paramTypes(m)) sb.append(p).append(',');
        return sb.append(')').append(m.getReturnType()).toString();
    }

    private static List<String> paramTypes(Method m) {
        List<String> out = new ArrayList<>();
        for (MethodParameter p : m.getParameters()) out.add(p.getType());
        return out;
    }

    private static boolean methodAllowed(Method m, boolean all, List<String> inc, List<String> exc) {
        if ((m.getAccessFlags() & org.jf.dexlib2.AccessFlags.STATIC.getValue()) == 0) return false;
        if ((m.getAccessFlags() & org.jf.dexlib2.AccessFlags.NATIVE.getValue()) != 0) return false;
        String n = m.getName();
        if (n.startsWith("<")) return false;
        if (!allowed(m.getDefiningClass(), all, inc, exc)) return false;
        String ret = m.getReturnType();
        if (!ret.equals("I") && !ret.equals("Z") && !ret.equals("V") && !ret.equals("J")) return false;
        for (MethodParameter p : m.getParameters()) {
            String t = p.getType();
            if (!t.equals("I") && !t.equals("Z")) return false;
        }
        return true;
    }

    /** 若 invoke 目标是本 dex 内且已被虚拟化的方法，返回其 key；否则 null。 */
    private static String eligibleTarget(Instruction insn, Set<String> eligible, Map<String, Method> byMethod) {
        if (!(insn instanceof ReferenceInstruction)) return null;
        Opcode op = insn.getOpcode();
        if (op != Opcode.INVOKE_STATIC && op != Opcode.INVOKE_STATIC_RANGE) return null;
        org.jf.dexlib2.iface.reference.Reference ref = ((ReferenceInstruction) insn).getReference();
        if (!(ref instanceof MethodReference)) return null;
        MethodReference mr = (MethodReference) ref;
        StringBuilder sb = new StringBuilder(mr.getDefiningClass()).append('|').append(mr.getName()).append('(');
        for (CharSequence p : mr.getParameterTypes()) sb.append(p).append(',');
        sb.append(')').append(mr.getReturnType());
        String k = sb.toString();
        return (byMethod.containsKey(k) && eligible.contains(k)) ? k : null;
    }

    /** 生成程序字节；失败返回 null。idMap 为 null 时用占位 id（用于可行性判断）。 */
    private static byte[] translateInternal(Method m, Map<String, Integer> idMap, Set<String> eligible,
                                            Map<String, Method> byMethod, int[] dummy) {
        try {
            MethodImplementation impl = m.getImplementation();
            if (impl == null) return null;
            if (!impl.getTryBlocks().isEmpty()) return null;
            int pcount = 0;
            for (MethodParameter p : m.getParameters()) pcount++;
            String ret = m.getReturnType();
            int regCount = impl.getRegisterCount();
            int paramBase = regCount - pcount;
            if (paramBase < 0) return null;

            Map<Integer, Integer> addrMap = new HashMap<>();
            int codeUnits = 0;
            List<Instruction> insns = new ArrayList<>();
            List<Integer> dalvikAddrs = new ArrayList<>();
            Map<Integer, Integer> addrToIndex = new HashMap<>();
            for (Instruction insn : impl.getInstructions()) {
                insns.add(insn);
                dalvikAddrs.add(codeUnits);
                addrToIndex.put(codeUnits, insns.size() - 1);
                codeUnits += insn.getCodeUnits();
            }
            boolean[] consumed = new boolean[insns.size()];
            Map<Integer, ArrayPayload> fillPayload = new HashMap<>();
            for (int i = 0; i < insns.size(); i++) {
                if (insns.get(i).getOpcode() == Opcode.FILL_ARRAY_DATA) {
                    int pa = dalvikAddrs.get(i) + ((OffsetInstruction) insns.get(i)).getCodeOffset();
                    Integer pi = addrToIndex.get(pa);
                    if (pi == null || !(insns.get(pi) instanceof ArrayPayload)) return null;
                    fillPayload.put(i, (ArrayPayload) insns.get(pi));
                    consumed[pi] = true;
                }
                if ((builtinId(insns.get(i)) >= 0 || strOpId(insns.get(i)) >= 0
                        || eligibleTarget(insns.get(i), eligible, byMethod) != null) && i + 1 < insns.size()) {
                    Opcode no = insns.get(i + 1).getOpcode();
                    if (no == Opcode.MOVE_RESULT || no == Opcode.MOVE_RESULT_WIDE || no == Opcode.MOVE_RESULT_OBJECT)
                        consumed[i + 1] = true;
                }
            }
            int off = 2;
            for (int i = 0; i < insns.size(); i++) {
                addrMap.put(dalvikAddrs.get(i), off);
                if (consumed[i]) continue;
                int len = instrLen(insns.get(i), fillPayload.get(i), eligible, byMethod);
                if (len < 0) return null;
                off += len;
            }
            addrMap.put(codeUnits, off);

            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(regCount & 0xFF);
            b.write(paramBase & 0xFF);
            int cur = 2;
            for (int i = 0; i < insns.size(); i++) {
                if (consumed[i]) continue;
                int nextDst = (i + 1 < insns.size() && consumed[i + 1])
                        ? ((OneRegisterInstruction) insns.get(i + 1)).getRegisterA() : 0;
                int len;
                if (fillPayload.containsKey(i)) {
                    len = emitFill(insns.get(i), fillPayload.get(i), b);
                } else if (builtinId(insns.get(i)) >= 0) {
                    len = emitBuiltin(insns.get(i), nextDst, b);
                } else if (eligibleTarget(insns.get(i), eligible, byMethod) != null) {
                    int cid = (idMap != null) ? idMap.get(eligibleTarget(insns.get(i), eligible, byMethod)) : 0;
                    len = emitCall(insns.get(i), cid, nextDst, b);
                } else if (isConstString(insns.get(i))) {
                    len = emitConstStr(insns.get(i), b);
                } else if (strOpId(insns.get(i)) >= 0) {
                    len = emitStr(strOpId(insns.get(i)), nextDst, argRegisters(insns.get(i)), b);
                } else {
                    len = encode(insns.get(i), dalvikAddrs.get(i), cur, addrMap, b);
                }
                if (len < 0) return null;
                cur += len;
            }
            b.write(OP_END);
            return b.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int emitCall(Instruction insn, int id, int dst, ByteArrayOutputStream b) {
        List<Integer> regs = argRegisters(insn);
        b.write(OP_CALL);
        b.write(id & 0xFF);
        b.write(dst & 0xFF);
        b.write(regs.size() & 0xFF);
        for (int r : regs) b.write(r & 0xFF);
        return 4 + regs.size();
    }

    private static int instrLen(Instruction insn, ArrayPayload payload, Set<String> eligible, Map<String, Method> byMethod) {
        if (payload != null) return 5 + payload.getArrayElements().size() * 8;
        if (builtinId(insn) >= 0) return 4 + argRegisters(insn).size();
        if (eligibleTarget(insn, eligible, byMethod) != null) return 4 + argRegisters(insn).size();
        if (isConstString(insn)) { int u = utf8Len(insn); return u < 0 ? -1 : 4 + u; }
        int so = strOpId(insn);
        if (so >= 0) return 2 + strArgCount(so);
        return encodedLen(insn);
    }

    private static boolean isConstString(Instruction insn) {
        Opcode op = insn.getOpcode();
        return op == Opcode.CONST_STRING || op == Opcode.CONST_STRING_JUMBO;
    }

    private static int utf8Len(Instruction insn) {
        try {
            StringReference sr = (StringReference) ((ReferenceInstruction) insn).getReference();
            return sr.getString().getBytes("UTF-8").length;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int strOpId(Instruction insn) {
        if (!(insn instanceof ReferenceInstruction)) return -1;
        Opcode op = insn.getOpcode();
        if (op != Opcode.INVOKE_VIRTUAL && op != Opcode.INVOKE_VIRTUAL_RANGE
                && op != Opcode.INVOKE_STATIC && op != Opcode.INVOKE_STATIC_RANGE) return -1;
        org.jf.dexlib2.iface.reference.Reference ref = ((ReferenceInstruction) insn).getReference();
        if (!(ref instanceof MethodReference)) return -1;
        MethodReference mr = (MethodReference) ref;
        String dc = mr.getDefiningClass(), n = mr.getName();
        if (dc.equals("Ljava/lang/String;")) {
            if (n.equals("length")) return OP_STR_LEN;
            if (n.equals("equals")) return OP_STR_EQ;
            if (n.equals("compareTo")) return OP_STR_CMP;
            if (n.equals("concat")) return OP_STR_CAT;
            if (n.equals("charAt")) return OP_STR_CHARAT;
            if (n.equals("valueOf")) return OP_STR_VALOF;
        }
        if (dc.equals("Ljava/lang/Integer;")) {
            if (n.equals("parseInt")) return OP_INT_PARSE;
            if (n.equals("toString")) return OP_STR_VALOF;
        }
        return -1;
    }

    private static int strArgCount(int op) {
        return (op == OP_STR_LEN || op == OP_INT_PARSE || op == OP_STR_VALOF) ? 1 : 2;
    }

    private static int emitConstStr(Instruction insn, ByteArrayOutputStream b) {
        try {
            int reg = ((OneRegisterInstruction) insn).getRegisterA();
            byte[] u = ((StringReference) ((ReferenceInstruction) insn).getReference()).getString().getBytes("UTF-8");
            b.write(OP_CONST_STR);
            b.write(reg & 0xFF);
            b.write(u.length & 0xFF);
            b.write((u.length >> 8) & 0xFF);
            b.write(u);
            return 4 + u.length;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int emitStr(int op, int dst, List<Integer> regs, ByteArrayOutputStream b) {
        int c = strArgCount(op);
        b.write(op);
        b.write(dst & 0xFF);
        for (int i = 0; i < c && i < regs.size(); i++) b.write(regs.get(i) & 0xFF);
        return 2 + c;
    }

    private static int emitFill(Instruction insn, ArrayPayload payload, ByteArrayOutputStream b) {
        int reg = ((OneRegisterInstruction) insn).getRegisterA();
        List<? extends Number> elems = payload.getArrayElements();
        int count = elems.size();
        int w = payload.getElementWidth();
        b.write(OP_FILLARRAY);
        b.write(reg & 0xFF);
        b.write(count & 0xFF);
        b.write((count >> 8) & 0xFF);
        b.write(w & 0xFF);
        for (Object el : elems) {
            long v = (el instanceof Number) ? ((Number) el).longValue()
                    : (el instanceof Character ? (Character) el : 0L);
            for (int k = 0; k < 8; k++) b.write((int) ((v >> (8 * k)) & 0xFF));
        }
        return 5 + count * 8;
    }

    private static int builtinId(Instruction insn) {
        if (!(insn instanceof ReferenceInstruction)) return -1;
        Opcode op = insn.getOpcode();
        if (op != Opcode.INVOKE_STATIC && op != Opcode.INVOKE_STATIC_RANGE) return -1;
        org.jf.dexlib2.iface.reference.Reference ref = ((ReferenceInstruction) insn).getReference();
        if (!(ref instanceof MethodReference)) return -1;
        MethodReference mr = (MethodReference) ref;
        String dc = mr.getDefiningClass(), n = mr.getName(), r = mr.getReturnType();
        if (dc.equals("Ljava/lang/Math;")) {
            if (r.equals("I")) {
                if (n.equals("abs")) return 0;
                if (n.equals("min")) return 1;
                if (n.equals("max")) return 2;
            }
            if (r.equals("J")) {
                if (n.equals("abs")) return 3;
                if (n.equals("min")) return 4;
                if (n.equals("max")) return 5;
            }
        }
        if (dc.equals("Ljava/lang/Integer;") && n.equals("bitCount")) return 6;
        if (dc.equals("Ljava/lang/Long;") && n.equals("bitCount")) return 7;
        if (dc.equals("Ljava/lang/System;") && n.equals("arraycopy")) return 8;
        return -1;
    }

    private static List<Integer> argRegisters(Instruction insn) {
        List<Integer> r = new ArrayList<>();
        if (insn.getOpcode().format == org.jf.dexlib2.Format.Format35c) {
            FiveRegisterInstruction fi = (FiveRegisterInstruction) insn;
            int c = fi.getRegisterCount();
            if (c > 0) r.add(fi.getRegisterC());
            if (c > 1) r.add(fi.getRegisterD());
            if (c > 2) r.add(fi.getRegisterE());
            if (c > 3) r.add(fi.getRegisterF());
            if (c > 4) r.add(fi.getRegisterG());
        } else if (insn.getOpcode().format == org.jf.dexlib2.Format.Format3rc) {
            RegisterRangeInstruction rr = (RegisterRangeInstruction) insn;
            for (int i = 0; i < rr.getRegisterCount(); i++) r.add(rr.getStartRegister() + i);
        }
        return r;
    }

    private static Method stub(Method m, String key, int id) {
        int regCount = m.getImplementation().getRegisterCount();
        int pcount = 0;
        for (MethodParameter p : m.getParameters()) pcount++;
        // 增加 8 个寄存器作为临时区(0..7)，参数被推到 >=8，避免与临时寄存器冲突
        int newReg = regCount + 8;
        int paramBase = newReg - pcount;
        String ret = m.getReturnType();
        MethodImplementationBuilder mib = new MethodImplementationBuilder(newReg);
        mib.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 1, pcount));
        mib.addInstruction(new BuilderInstruction22c(Opcode.NEW_ARRAY, 0, 1, new ImmutableTypeReference("[I")));
        for (int i = 0; i < pcount; i++) {
            mib.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 1, i));
            mib.addInstruction(new BuilderInstruction23x(Opcode.APUT, paramBase + i, 0, 1));
        }
        mib.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 2, new ImmutableStringReference(key)));
        if (id >= -8 && id <= 7) {
            mib.addInstruction(new org.jf.dexlib2.builder.instruction.BuilderInstruction11n(Opcode.CONST_4, 3, id));
        } else {
            mib.addInstruction(new BuilderInstruction21s(Opcode.CONST_16, 3, id));
        }
        if (ret.equals("J")) {
            mib.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 3, 2, 3, 0, 0, 0,
                    new ImmutableMethodReference(VMP, "runL",
                            java.util.Arrays.asList("Ljava/lang/String;", "I", "[I"), "J")));
            mib.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_WIDE, 4));
            mib.addInstruction(new BuilderInstruction11x(Opcode.RETURN_WIDE, 4));
        } else {
            mib.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 3, 2, 3, 0, 0, 0,
                    new ImmutableMethodReference(VMP, "run",
                            java.util.Arrays.asList("Ljava/lang/String;", "I", "[I"), "I")));
            mib.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 4));
            if (ret.equals("V")) {
                mib.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            } else {
                mib.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 4));
            }
        }
        ImmutableMethodImplementation nimpl = ImmutableMethodImplementation.of(mib.getMethodImplementation());
        return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(),
                m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), nimpl);
    }

    private static int encodedLen(Instruction insn) {
        Opcode op = insn.getOpcode();
        switch (op) {
            case NOP: return 0;
            case MOVE: case MOVE_WIDE: case MOVE_WIDE_16: case MOVE_WIDE_FROM16:
            case MOVE_OBJECT: case MOVE_OBJECT_16: case MOVE_OBJECT_FROM16: return 3;
            case CONST_4: case CONST_16: case CONST:
            case CONST_WIDE: case CONST_WIDE_16: case CONST_WIDE_32: case CONST_WIDE_HIGH16: return 10;
            case ADD_INT: case SUB_INT: case MUL_INT: case AND_INT: case OR_INT: case XOR_INT:
            case SHL_INT: case SHR_INT: case USHR_INT: return 4;
            case NEG_INT: case NOT_INT: case NEG_LONG: case NOT_LONG:
            case INT_TO_LONG: case LONG_TO_INT: return 3;
            case ADD_LONG: case SUB_LONG: case MUL_LONG: case AND_LONG: case OR_LONG: case XOR_LONG:
            case SHL_LONG: case SHR_LONG: case USHR_LONG: case CMP_LONG: return 4;
            case RETURN: return 2;
            case RETURN_WIDE: return 2;
            case IF_EQZ: case IF_NEZ: case IF_LTZ: case IF_GEZ: case IF_GTZ: case IF_LEZ: return 4;
            case IF_EQ: case IF_NE: case IF_LT: case IF_GE: case IF_GT: case IF_LE: return 5;
            case ADD_INT_LIT8: case ADD_INT_LIT16: case RSUB_INT: case RSUB_INT_LIT8:
            case MUL_INT_LIT8: case MUL_INT_LIT16: case AND_INT_LIT8: case AND_INT_LIT16:
            case OR_INT_LIT8: case OR_INT_LIT16: case XOR_INT_LIT8: case XOR_INT_LIT16:
            case SHL_INT_LIT8: case SHR_INT_LIT8: case USHR_INT_LIT8: return 5;
            case GOTO: case GOTO_16: case GOTO_32: return 3;
            case INT_TO_BYTE: case INT_TO_CHAR: case INT_TO_SHORT: return 3;
            case NEW_ARRAY: return 3;
            case AGET: case AGET_WIDE: case APUT: case APUT_WIDE: return 4;
            case ARRAY_LENGTH: return 3;
            case RETURN_VOID: return 1;
            default: return -1;
        }
    }

    private static int encode(Instruction insn, int dalvikAddr, int cur, Map<Integer, Integer> addrMap, ByteArrayOutputStream b) {
        Opcode op = insn.getOpcode();
        int base = cur;
        switch (op) {
            case NOP: return 0;
            case MOVE: case MOVE_WIDE: case MOVE_WIDE_16: case MOVE_WIDE_FROM16:
            case MOVE_OBJECT: case MOVE_OBJECT_16: case MOVE_OBJECT_FROM16: {
                b.write(OP_MOVE);
                b.write(((OneRegisterInstruction) insn).getRegisterA());
                b.write(((TwoRegisterInstruction) insn).getRegisterB());
                return 3;
            }
            case CONST_4: case CONST_16: case CONST: {
                b.write(OP_CONST);
                b.write(((OneRegisterInstruction) insn).getRegisterA());
                long v = ((NarrowLiteralInstruction) insn).getNarrowLiteral();
                for (int i = 0; i < 8; i++) b.write((int) ((v >> (8 * i)) & 0xFF));
                return 10;
            }
            case CONST_WIDE: case CONST_WIDE_16: case CONST_WIDE_32: case CONST_WIDE_HIGH16: {
                long v;
                if (op == Opcode.CONST_WIDE) v = ((org.jf.dexlib2.iface.instruction.WideLiteralInstruction) insn).getWideLiteral();
                else if (op == Opcode.CONST_WIDE_16) v = (short) ((NarrowLiteralInstruction) insn).getNarrowLiteral();
                else if (op == Opcode.CONST_WIDE_32) v = ((NarrowLiteralInstruction) insn).getNarrowLiteral();
                else v = ((long) (short) ((NarrowLiteralInstruction) insn).getNarrowLiteral()) << 48;
                b.write(OP_CONST);
                b.write(((OneRegisterInstruction) insn).getRegisterA());
                for (int i = 0; i < 8; i++) b.write((int) ((v >> (8 * i)) & 0xFF));
                return 10;
            }
            case ADD_LONG: case SUB_LONG: case MUL_LONG: case AND_LONG: case OR_LONG: case XOR_LONG:
            case SHL_LONG: case SHR_LONG: case USHR_LONG: case CMP_LONG: {
                int code;
                switch (op) {
                    case ADD_LONG: code = OP_ADD64; break;
                    case SUB_LONG: code = OP_SUB64; break;
                    case MUL_LONG: code = OP_MUL64; break;
                    case AND_LONG: code = OP_AND64; break;
                    case OR_LONG: code = OP_OR64; break;
                    case XOR_LONG: code = OP_XOR64; break;
                    case SHL_LONG: code = OP_SHL64; break;
                    case SHR_LONG: code = OP_SHR64; break;
                    case USHR_LONG: code = OP_USHR64; break;
                    default: code = OP_CMP_LONG; break;
                }
                b.write(code);
                b.write(((ThreeRegisterInstruction) insn).getRegisterA());
                b.write(((ThreeRegisterInstruction) insn).getRegisterB());
                b.write(((ThreeRegisterInstruction) insn).getRegisterC());
                return 4;
            }
            case NEG_LONG: case NOT_LONG: case INT_TO_LONG: case LONG_TO_INT: {
                int code;
                switch (op) {
                    case NEG_LONG: code = OP_NEG64; break;
                    case NOT_LONG: code = OP_NOT64; break;
                    case INT_TO_LONG: code = OP_INT_TO_LONG; break;
                    default: code = OP_LONG_TO_INT; break;
                }
                b.write(code);
                b.write(((OneRegisterInstruction) insn).getRegisterA());
                b.write(((TwoRegisterInstruction) insn).getRegisterB());
                return 3;
            }
            case ADD_INT: case SUB_INT: case MUL_INT: case AND_INT: case OR_INT: case XOR_INT:
            case SHL_INT: case SHR_INT: case USHR_INT: {
                int code;
                switch (op) {
                    case ADD_INT: code = OP_ADD; break;
                    case SUB_INT: code = OP_SUB; break;
                    case MUL_INT: code = OP_MUL; break;
                    case AND_INT: code = OP_AND; break;
                    case OR_INT: code = OP_OR; break;
                    case XOR_INT: code = OP_XOR; break;
                    case SHL_INT: code = OP_SHL; break;
                    case SHR_INT: code = OP_SHR; break;
                    default: code = OP_USHR; break;
                }
                b.write(code);
                b.write(((ThreeRegisterInstruction) insn).getRegisterA());
                b.write(((ThreeRegisterInstruction) insn).getRegisterB());
                b.write(((ThreeRegisterInstruction) insn).getRegisterC());
                return 4;
            }
            case NEG_INT: {
                b.write(OP_NEG);
                b.write(((OneRegisterInstruction) insn).getRegisterA());
                b.write(((TwoRegisterInstruction) insn).getRegisterB());
                return 3;
            }
            case NOT_INT: {
                b.write(OP_NOT);
                b.write(((OneRegisterInstruction) insn).getRegisterA());
                b.write(((TwoRegisterInstruction) insn).getRegisterB());
                return 3;
            }
            case INT_TO_BYTE: case INT_TO_CHAR: case INT_TO_SHORT: {
                int code = op == Opcode.INT_TO_BYTE ? OP_INT_TO_BYTE : (op == Opcode.INT_TO_CHAR ? OP_INT_TO_CHAR : OP_INT_TO_SHORT);
                b.write(code);
                b.write(((OneRegisterInstruction) insn).getRegisterA());
                b.write(((TwoRegisterInstruction) insn).getRegisterB());
                return 3;
            }
            case NEW_ARRAY: {
                org.jf.dexlib2.iface.reference.TypeReference tr =
                        (org.jf.dexlib2.iface.reference.TypeReference) ((ReferenceInstruction) insn).getReference();
                String t = tr.getType();
                if (!t.equals("[I") && !t.equals("[J") && !t.equals("[S") && !t.equals("[C")
                        && !t.equals("[B") && !t.equals("[Z")) return -1;
                b.write(OP_NEWARRAY);
                b.write(((TwoRegisterInstruction) insn).getRegisterA());
                b.write(((TwoRegisterInstruction) insn).getRegisterB());
                return 3;
            }
            case AGET: case AGET_WIDE: {
                b.write(OP_AGET);
                b.write(((ThreeRegisterInstruction) insn).getRegisterA());
                b.write(((ThreeRegisterInstruction) insn).getRegisterB());
                b.write(((ThreeRegisterInstruction) insn).getRegisterC());
                return 4;
            }
            case APUT: case APUT_WIDE: {
                b.write(OP_APUT);
                b.write(((ThreeRegisterInstruction) insn).getRegisterB());
                b.write(((ThreeRegisterInstruction) insn).getRegisterC());
                b.write(((ThreeRegisterInstruction) insn).getRegisterA());
                return 4;
            }
            case ARRAY_LENGTH: {
                b.write(OP_ARRAYLEN);
                b.write(((OneRegisterInstruction) insn).getRegisterA());
                b.write(((TwoRegisterInstruction) insn).getRegisterB());
                return 3;
            }
            case IF_EQZ: case IF_NEZ: case IF_LTZ: case IF_GEZ: case IF_GTZ: case IF_LEZ: {
                int code;
                switch (op) {
                    case IF_EQZ: code = OP_IF_EQZ; break;
                    case IF_NEZ: code = OP_IF_NEZ; break;
                    case IF_LTZ: code = OP_IF_LTZ; break;
                    case IF_GEZ: code = OP_IF_GEZ; break;
                    case IF_GTZ: code = OP_IF_GTZ; break;
                    default: code = OP_IF_LEZ; break;
                }
                Integer t = addrMap.get(dalvikAddr + ((OffsetInstruction) insn).getCodeOffset());
                if (t == null) return -1;
                int off = t - base;
                if (off < Short.MIN_VALUE || off > Short.MAX_VALUE) return -1;
                b.write(code);
                b.write(((OneRegisterInstruction) insn).getRegisterA());
                b.write(off & 0xFF);
                b.write((off >> 8) & 0xFF);
                return 4;
            }
            case IF_EQ: case IF_NE: case IF_LT: case IF_GE: case IF_GT: case IF_LE: {
                int code;
                switch (op) {
                    case IF_EQ: code = OP_IF_EQ; break;
                    case IF_NE: code = OP_IF_NE; break;
                    case IF_LT: code = OP_IF_LT; break;
                    case IF_GE: code = OP_IF_GE; break;
                    case IF_GT: code = OP_IF_GT; break;
                    default: code = OP_IF_LE; break;
                }
                Integer t = addrMap.get(dalvikAddr + ((OffsetInstruction) insn).getCodeOffset());
                if (t == null) return -1;
                int off = t - base;
                if (off < Short.MIN_VALUE || off > Short.MAX_VALUE) return -1;
                b.write(code);
                b.write(((TwoRegisterInstruction) insn).getRegisterA());
                b.write(((TwoRegisterInstruction) insn).getRegisterB());
                b.write(off & 0xFF);
                b.write((off >> 8) & 0xFF);
                return 5;
            }
            case ADD_INT_LIT8: case ADD_INT_LIT16: case RSUB_INT: case RSUB_INT_LIT8:
            case MUL_INT_LIT8: case MUL_INT_LIT16: case AND_INT_LIT8: case AND_INT_LIT16:
            case OR_INT_LIT8: case OR_INT_LIT16: case XOR_INT_LIT8: case XOR_INT_LIT16:
            case SHL_INT_LIT8: case SHR_INT_LIT8: case USHR_INT_LIT8: {
                int code;
                switch (op) {
                    case ADD_INT_LIT8: case ADD_INT_LIT16: code = OP_ADD_LIT; break;
                    case RSUB_INT: case RSUB_INT_LIT8: code = OP_RSUB_LIT; break;
                    case MUL_INT_LIT8: case MUL_INT_LIT16: code = OP_MUL_LIT; break;
                    case AND_INT_LIT8: case AND_INT_LIT16: code = OP_AND_LIT; break;
                    case OR_INT_LIT8: case OR_INT_LIT16: code = OP_OR_LIT; break;
                    case XOR_INT_LIT8: case XOR_INT_LIT16: code = OP_XOR_LIT; break;
                    case SHL_INT_LIT8: code = OP_SHL_LIT; break;
                    case SHR_INT_LIT8: code = OP_SHR_LIT; break;
                    default: code = OP_USHR_LIT; break;
                }
                int lit = ((NarrowLiteralInstruction) insn).getNarrowLiteral();
                b.write(code);
                b.write(((TwoRegisterInstruction) insn).getRegisterA());
                b.write(((TwoRegisterInstruction) insn).getRegisterB());
                b.write(lit & 0xFF);
                b.write((lit >> 8) & 0xFF);
                return 5;
            }
            case GOTO: case GOTO_16: case GOTO_32: {
                Integer t = addrMap.get(dalvikAddr + ((OffsetInstruction) insn).getCodeOffset());
                if (t == null) return -1;
                int off = t - base;
                if (off < Short.MIN_VALUE || off > Short.MAX_VALUE) return -1;
                b.write(OP_GOTO);
                b.write(off & 0xFF);
                b.write((off >> 8) & 0xFF);
                return 3;
            }
            case RETURN: {
                b.write(OP_RET);
                b.write(((OneRegisterInstruction) insn).getRegisterA());
                return 2;
            }
            case RETURN_WIDE: {
                b.write(OP_RET_LONG);
                b.write(((OneRegisterInstruction) insn).getRegisterA());
                return 2;
            }
            case RETURN_VOID:
                b.write(OP_RET_VOID);
                return 1;
            default:
                return -1;
        }
    }

    private static int emitBuiltin(Instruction insn, int dst, ByteArrayOutputStream b) {
        int bid = builtinId(insn);
        List<Integer> regs = argRegisters(insn);
        b.write(OP_INVOKE_BUILTIN);
        b.write(bid & 0xFF);
        b.write(dst & 0xFF);
        b.write(regs.size() & 0xFF);
        for (int reg : regs) b.write(reg & 0xFF);
        return 4 + regs.size();
    }

    private static boolean allowed(String type, boolean all, List<String> inc, List<String> exc) {
        if (type == null || type.length() < 3) return false;
        if (type.startsWith("Landroid/") || type.startsWith("Ljava/") || type.startsWith("Ldalvik/")
                || type.startsWith("Lorg/") || type.startsWith("Lkotlin/") || type.startsWith("Lcom/android/")
                || type.startsWith("Landroidx/") || type.startsWith("Lcn/mhook/")) return false;
        String cls = type.substring(1, type.length() - 1).replace('/', '.');
        if (!all && !matchPrefix(cls, inc)) return false;
        return !matchPrefix(cls, exc);
    }

    private static boolean matchPrefix(String cls, List<String> list) {
        for (String p : list) if (!p.isEmpty() && cls.startsWith(p)) return true;
        return false;
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split("[,\\s;]+")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }
}
