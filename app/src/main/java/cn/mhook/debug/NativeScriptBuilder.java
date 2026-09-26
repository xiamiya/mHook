package cn.mhook.debug;

/**
 * Native 调试脚本生成器（阶段 2）。
 * 生成的脚本统一「等 ART 就绪 → 用 android.util.Log(tag MHKDBG) 输出」，
 * 因为 gadget 在 App 进程内时 console.log 不可见（stdout 被丢弃）。
 */
public class NativeScriptBuilder {

    private static final String HEAD =
            "function log(s){ try{ Java.use(\"android.util.Log\").i(\"MHKDBG\", s); }catch(e){} }\n"
            + "function hex(a){ try{ return a.toString(); }catch(e){ return String(a); } }\n";

    private static final String TAIL =
            "\nvar _t=0,_tm=setInterval(function(){\n"
            + "  _t++;\n"
            + "  if(Java.available){ clearInterval(_tm); Java.perform(function(){ try{ run(); }catch(e){ log(\"run err: \"+e); } }); }\n"
            + "  else if(_t>=60){ clearInterval(_tm); }\n"
            + "},200);\n";

    private static String wrap(String body) {
        return HEAD + "function run(){\n" + body + "\n}\n" + TAIL;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 列出所有已加载模块（名字/基址/大小）。 */
    public static String listModules() {
        return wrap(
                "  var mods = Process.enumerateModules();\n"
                + "  log(\"modules=\" + mods.length);\n"
                + "  mods.forEach(function(m){ log(m.name + \"  base=\" + m.base + \"  size=\" + m.size); });\n");
    }

    /** 列出指定模块的导出符号。 */
    public static String listExports(String module) {
        return wrap(
                "  var name = \"" + esc(module) + "\";\n"
                + "  var mod = Process.findModuleByName(name);\n"
                + "  if(!mod){ log(\"module not found: \" + name); return; }\n"
                + "  var ex = mod.enumerateExports();\n"
                + "  log(\"exports=\" + ex.length + \" in \" + name);\n"
                + "  ex.forEach(function(e){ log(e.type + \" \" + e.name + \" \" + e.address); });\n");
    }

    /** 列出指定模块的导入符号（含来源库）。 */
    public static String listImports(String module) {
        return wrap(
                "  var name = \"" + esc(module) + "\";\n"
                + "  var mod = Process.findModuleByName(name);\n"
                + "  if(!mod){ log(\"module not found: \" + name); return; }\n"
                + "  var im = mod.enumerateImports();\n"
                + "  log(\"imports=\" + im.length + \" in \" + name);\n"
                + "  im.forEach(function(e){ log(e.type + \" \" + e.name + \" <- \" + (e.module||\"?\")); });\n");
    }

    /** 按导出符号 inline hook，打印参数与返回值。 */
    public static String hookExport(String module, String symbol, boolean printArgs, boolean printRet) {
        StringBuilder b = new StringBuilder();
        b.append("  var addr = Module.findExportByName(\"").append(esc(module)).append("\", \"").append(esc(symbol)).append("\");\n");
        b.append("  if(!addr){ log(\"symbol not found: ").append(esc(symbol)).append("\"); return; }\n");
        b.append("  log(\"hooking ").append(esc(module)).append("!").append(esc(symbol)).append(" @ \" + addr);\n");
        b.append("  Interceptor.attach(addr, {\n");
        b.append("    onEnter: function(args){\n");
        if (printArgs) {
            b.append("      log(\"-> ").append(esc(symbol)).append(" a0=\" + args[0] + \" a1=\" + args[1] + \" a2=\" + args[2]);\n");
        } else {
            b.append("      log(\"-> ").append(esc(symbol)).append("\");\n");
        }
        b.append("    },\n");
        b.append("    onLeave: function(retval){\n");
        if (printRet) {
            b.append("      log(\"<- ").append(esc(symbol)).append(" ret=\" + retval);\n");
        }
        b.append("    }\n");
        b.append("  });\n");
        return wrap(b.toString());
    }

    /** 按模块基址 + 偏移 inline hook（十六进制偏移，如 0x1a2b0）。 */
    public static String hookOffset(String module, String offsetHex) {
        return wrap(
                "  var mod = Process.findModuleByName(\"" + esc(module) + "\");\n"
                + "  if(!mod){ log(\"module not found: " + esc(module) + "\"); return; }\n"
                + "  var off = " + esc(offsetHex) + ";\n"
                + "  var addr = mod.base.add(off);\n"
                + "  log(\"hooking " + esc(module) + "+0x\" + off.toString(16) + \" @ \" + addr);\n"
                + "  Interceptor.attach(addr, {\n"
                + "    onEnter: function(args){ log(\"-> off a0=\" + args[0] + \" a1=\" + args[1]); },\n"
                + "    onLeave: function(retval){ log(\"<- ret=\" + retval); }\n"
                + "  });\n");
    }

    /** 内存搜索：把字符串转成十六进制模式后在可读内存中扫描。 */
    public static String scanString(String text, int limit) {
        StringBuilder hex = new StringBuilder();
        byte[] bs;
        try {
            bs = text.getBytes("UTF-8");
        } catch (Throwable t) {
            bs = text.getBytes();
        }
        for (int i = 0; i < bs.length; i++) {
            if (i > 0) {
                hex.append(' ');
            }
            hex.append(String.format("%02x", bs[i] & 0xFF));
        }
        return wrap(
                "  var pat = \"" + hex + "\";\n"
                + "  var found = 0, lim = " + limit + ";\n"
                + "  var ranges = Process.enumerateRanges(\"r--\");\n"
                + "  log(\"scanning \" + ranges.length + \" ranges for pattern...\");\n"
                + "  ranges.forEach(function(r){\n"
                + "    if(found >= lim) return;\n"
                + "    try {\n"
                + "      Memory.scanSync(r.base, r.size, pat).forEach(function(m){\n"
                + "        if(found < lim){ found++; log(\"found @ \" + m.address + \"  (\" + r.base + \")\"); }\n"
                + "      });\n"
                + "    } catch(e){}\n"
                + "  });\n"
                + "  log(\"scan done, matches=\" + found);\n");
    }

    /** 反调试绕过：ptrace 占用 + TracerPid 伪造 + Frida 线程名隐藏。 */
    public static String bypassAntiDebug() {
        return wrap(
                "  log(\"bypass: hooking libc ptrace\");\n"
                + "  var ptrace = Module.findExportByName(\"libc.so\", \"ptrace\");\n"
                + "  if(ptrace){\n"
                + "    Interceptor.attach(ptrace, {\n"
                + "      onEnter: function(args){ this.req = args[0]; },\n"
                + "      onLeave: function(ret){ if(this.req.toInt32() === 0){ ret.replace(ptr(0)); log(\"ptrace(PTRACE_TRACEME) -> 0\"); } }\n"
                + "    });\n"
                + "    log(\"ptrace hooked\");\n"
                + "  } else { log(\"ptrace not found\"); }\n"
                // TracerPid 伪造：hook fgets，读到 TracerPid 行时改写为 0
                + "  var fgets = Module.findExportByName(\"libc.so\", \"fgets\");\n"
                + "  if(fgets){\n"
                + "    Interceptor.attach(fgets, {\n"
                + "      onEnter: function(args){ this.buf = args[0]; },\n"
                + "      onLeave: function(ret){\n"
                + "        if(ret.isNull()) return;\n"
                + "        try {\n"
                + "          var s = this.buf.readCString();\n"
                + "          if(s && s.indexOf(\"TracerPid\") >= 0 && s.indexOf(\"\\t0\") < 0){\n"
                + "            this.buf.writeUtf8String(\"TracerPid:\\t0\\n\");\n"
                + "            log(\"TracerPid -> 0\");\n"
                + "          }\n"
                + "        } catch(e){}\n"
                + "      }\n"
                + "    });\n"
                + "    log(\"fgets hooked (TracerPid)\");\n"
                + "  }\n"
                // Frida 线程名隐藏
                + "  var psn = Module.findExportByName(\"libc.so\", \"pthread_setname_np\");\n"
                + "  if(psn){\n"
                + "    Interceptor.attach(psn, {\n"
                + "      onEnter: function(args){\n"
                + "        try {\n"
                + "          var nm = args[1].readCString();\n"
                + "          if(nm && (nm.indexOf(\"gum\") >= 0 || nm.indexOf(\"frida\") >= 0 || nm.indexOf(\"gmain\") >= 0 || nm.indexOf(\"gdbus\") >= 0)){\n"
                + "            log(\"hide thread: \" + nm);\n"
                + "            args[1].writeUtf8String(\"worker\");\n"
                + "          }\n"
                + "        } catch(e){}\n"
                + "      }\n"
                + "    });\n"
                + "    log(\"pthread_setname_np hooked\");\n"
                + "  }\n"
                // 常见 frida 字符串检测（strstr）过滤
                + "  var strstr = Module.findExportByName(\"libc.so\", \"strstr\");\n"
                + "  if(strstr){\n"
                + "    Interceptor.attach(strstr, {\n"
                + "      onEnter: function(args){ this.needle = args[1].readCString(); },\n"
                + "      onLeave: function(ret){\n"
                + "        if(this.needle && (this.needle.indexOf(\"frida\") >= 0 || this.needle.indexOf(\"gum-js\") >= 0 || this.needle.indexOf(\"linjector\") >= 0)){\n"
                + "          ret.replace(ptr(0));\n"
                + "        }\n"
                + "      }\n"
                + "    });\n"
                + "    log(\"strstr hooked (frida strings)\");\n"
                + "  }\n");
    }

    /** Dump 模块内存（偏移 + 长度）到目标 App 私有目录，并回显路径与头部 hex。 */
    public static String dumpModule(String module, String offsetExpr, String sizeExpr, String pkg) {        String off = (offsetExpr == null || offsetExpr.trim().isEmpty()) ? "0" : offsetExpr.trim();
        String size = (sizeExpr == null || sizeExpr.trim().isEmpty()) ? "0x1000" : sizeExpr.trim();
        String dir = "/data/data/" + esc(pkg) + "/files";
        return wrap(
                "  var mod = Process.findModuleByName(\"" + esc(module) + "\");\n"
                + "  if(!mod){ log(\"module not found: " + esc(module) + "\"); return; }\n"
                + "  var addr = mod.base.add(" + off + ");\n"
                + "  var size = " + size + ";\n"
                + "  var data = addr.readByteArray(size);\n"
                + "  var path = \"" + dir + "/mhook_dump_\" + Date.now() + \".bin\";\n"
                + "  var f = new File(path, \"wb\"); f.write(data); f.close();\n"
                + "  log(\"dumped \" + size + \" bytes @ \" + addr + \" -> \" + path);\n"
                + "  var u8 = new Uint8Array(data); var s = \"\";\n"
                + "  for(var i=0;i<Math.min(64,u8.length);i++){ s += (u8[i]<16?\"0\":\"\") + u8[i].toString(16) + \" \"; }\n"
                + "  log(\"head: \" + s);\n");
    }

    /** 向绝对地址写入字节（hex 形如 "41 42 43"）。 */
    public static String writeMemory(String addrExpr, String hex) {        StringBuilder arr = new StringBuilder();
        if (hex != null) {
            for (String tok : hex.trim().split("[\\s,]+")) {
                if (tok.isEmpty()) {
                    continue;
                }
                String t = (tok.startsWith("0x") || tok.startsWith("0X")) ? tok.substring(2) : tok;
                int v;
                try {
                    v = Integer.parseInt(t, 16);
                } catch (Throwable e) {
                    continue;
                }
                if (arr.length() > 0) {
                    arr.append(',');
                }
                arr.append("0x").append(Integer.toHexString(v & 0xFF));
            }
        }
        return wrap(
                "  var addr = ptr(" + esc(addrExpr) + ");\n"
                + "  var bytes = [" + arr + "];\n"
                + "  if(bytes.length === 0){ log(\"no bytes to write\"); return; }\n"
                + "  Memory.writeByteArray(addr, bytes);\n"
                + "  log(\"wrote \" + bytes.length + \" bytes to \" + addr);\n");
    }

    /** DEX 动态分析：内存扫描 dex magic，dump 所有解密后的 dex 到目标 App 私有目录。 */
    public static String dumpDex(String pkg) {
        String outDir = "/data/data/" + esc(pkg) + "/files/dexdump";
        return wrap(
                "  var outDir = \"" + outDir + "\";\n"
                + "  Java.use(\"java.io.File\").$new(outDir).mkdirs();\n"
                + "  var count = 0;\n"
                + "  var ranges = Process.enumerateRanges(\"r--\");\n"
                + "  log(\"scanning \" + ranges.length + \" ranges for dex magic...\");\n"
                + "  ranges.forEach(function(r){\n"
                + "    try {\n"
                + "      Memory.scanSync(r.base, r.size, \"64 65 78 0a\").forEach(function(m){\n"
                + "        var addr = m.address;\n"
                + "        try {\n"
                + "          var ver = addr.add(4).readU8();\n"
                + "          if (ver !== 0x30) return;\n"
                + "          var size = addr.add(0x20).readU32();\n"
                + "          if (size < 0x70 || size > 0x8000000) return;\n"
                + "          var data = addr.readByteArray(size);\n"
                + "          var path = outDir + \"/dex_\" + count + \"_\" + addr + \".dex\";\n"
                + "          var f = new File(path, \"wb\"); f.write(data); f.close();\n"
                + "          log(\"dumped dex size=\" + size + \" @ \" + addr);\n"
                + "          count++;\n"
                + "        } catch(e){}\n"
                + "      });\n"
                + "    } catch(e){}\n"
                + "  });\n"
                + "  log(\"total dex dumped=\" + count + \" -> \" + outDir);\n");
    }

    /** BL/BR 调用捕获：Stalker 限定 trace 目标函数执行期间的所有 call（BL/BR）。 */
    public static String captureCalls(String module, String symbol) {
        return wrap(
                "  var addr = Module.findExportByName(\"" + esc(module) + "\", \"" + esc(symbol) + "\");\n"
                + "  if(!addr){ log(\"symbol not found: " + esc(symbol) + "\"); return; }\n"
                + "  log(\"tracing calls in " + esc(module) + "!" + esc(symbol) + " @ \" + addr);\n"
                + "  Interceptor.attach(addr, {\n"
                + "    onEnter: function(args){\n"
                + "      var tid = Process.getCurrentThreadId();\n"
                + "      this.tid = tid;\n"
                + "      log(\"== trace start tid=\" + tid);\n"
                + "      Stalker.follow(tid, {\n"
                + "        events: { call: true },\n"
                + "        onReceive: function(events){\n"
                + "          var evs = Stalker.parse(events, { annotate: true });\n"
                + "          evs.forEach(function(e){\n"
                + "            if(e[0] === \"call\"){\n"
                + "              var to = e[2];\n"
                + "              var nm = \"\";\n"
                + "              try { var ds = DebugSymbol.fromAddress(to); if(ds) nm = ds.name; } catch(err){}\n"
                + "              log(\"  call -> \" + to + \"  \" + nm);\n"
                + "            }\n"
                + "          });\n"
                + "        }\n"
                + "      });\n"
                + "    },\n"
                + "    onLeave: function(ret){\n"
                + "      try { Stalker.unfollow(this.tid); Stalker.flush(); } catch(e){}\n"
                + "      log(\"== trace end\");\n"
                + "    }\n"
                + "  });\n"
                + "  log(\"call-capture installed\");\n");
    }

    /** 内存 Hex 视图：按模块 + 偏移读取并格式化输出（地址 + hex + ascii）。 */
    public static String hexDump(String module, String offsetExpr, String sizeExpr) {
        String off = (offsetExpr == null || offsetExpr.trim().isEmpty()) ? "0" : offsetExpr.trim();
        String size = (sizeExpr == null || sizeExpr.trim().isEmpty()) ? "256" : sizeExpr.trim();
        return wrap(
                "  var mod = Process.findModuleByName(\"" + esc(module) + "\");\n"
                + "  if(!mod){ log(\"module not found: " + esc(module) + "\"); return; }\n"
                + "  var addr = mod.base.add(" + off + ");\n"
                + "  var size = " + size + ";\n"
                + "  var u8 = new Uint8Array(addr.readByteArray(size));\n"
                + "  log(\"hex dump @ \" + addr + \" size=\" + size);\n"
                + "  for(var i=0;i<u8.length;i+=16){\n"
                + "    var h=\"\", a=\"\";\n"
                + "    for(var j=0;j<16 && i+j<u8.length;j++){\n"
                + "      var b=u8[i+j];\n"
                + "      h += (b<16?\"0\":\"\") + b.toString(16) + \" \";\n"
                + "      a += (b>=32 && b<127) ? String.fromCharCode(b) : \".\";\n"
                + "    }\n"
                + "    log(\"  \" + addr.add(i) + \"  \" + h + \"  \" + a);\n"
                + "  }\n");
    }

    /** 内存对比：读取同一范围两次（间隔 5 秒），输出变化的字节偏移。 */
    public static String memDiff(String module, String offsetExpr, String sizeExpr) {
        String off = (offsetExpr == null || offsetExpr.trim().isEmpty()) ? "0" : offsetExpr.trim();
        String size = (sizeExpr == null || sizeExpr.trim().isEmpty()) ? "256" : sizeExpr.trim();
        return wrap(
                "  var mod = Process.findModuleByName(\"" + esc(module) + "\");\n"
                + "  if(!mod){ log(\"module not found: " + esc(module) + "\"); return; }\n"
                + "  var addr = mod.base.add(" + off + ");\n"
                + "  var size = " + size + ";\n"
                + "  var a = new Uint8Array(addr.readByteArray(size));\n"
                + "  log(\"snapshot1 done @ \" + addr + \" size=\" + size + \", waiting 5s...\");\n"
                + "  setTimeout(function(){\n"
                + "    var b = new Uint8Array(addr.readByteArray(size));\n"
                + "    var diff = 0;\n"
                + "    for(var i=0;i<a.length;i++){\n"
                + "      if(a[i] !== b[i]){\n"
                + "        log(\"  +0x\" + i.toString(16) + \": \" + a[i].toString(16) + \" -> \" + b[i].toString(16));\n"
                + "        diff++;\n"
                + "        if(diff >= 200){ log(\"  ...(more)\"); break; }\n"
                + "      }\n"
                + "    }\n"
                + "    log(\"diff bytes=\" + diff);\n"
                + "  }, 5000);\n");
    }
}
