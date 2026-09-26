package cn.mhook.activity.debug;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import cn.mhook.debug.FridaServerManager;
import cn.mhook.debug.GadgetManager;
import cn.mhook.debug.NativeScriptBuilder;
import cn.mhook.mhook.R;
import cn.mhook.widget.GlassToast;

/**
 * Native 调试（阶段 2）：so 枚举 / 导出符号 / inline hook / 内存搜索 / 反调试绕过。
 */
public class FridaNativeActivity extends Activity {

    private TextView tvTarget, tvOut;
    private EditText etModule, etSymbol, etScan, etScript, etSize, etFilter;
    private ScrollView scrollLog;
    private ScrollView scrollOut;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;
    private String pkg = "";
    private String filterMode = "all";
    private String appName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_frida_native);

        tvTarget = findViewById(R.id.tv_target);
        tvOut = findViewById(R.id.tv_out);
        etModule = findViewById(R.id.et_module);
        etSymbol = findViewById(R.id.et_symbol);
        etScan = findViewById(R.id.et_scan);
        etScript = findViewById(R.id.et_script);
        etSize = findViewById(R.id.et_size);
        etFilter = findViewById(R.id.et_filter);
        scrollLog = findViewById(R.id.scroll_log);
        scrollOut = findViewById(R.id.scroll_out);
        bindScriptTools();

        Intent it = getIntent();
        pkg = it.getStringExtra("pkg");
        appName = it.getStringExtra("name");
        if (pkg == null) {
            pkg = "";
        }
        tvTarget.setText(appName != null && !appName.isEmpty() ? (appName + "  ·  " + pkg) : pkg);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_modules).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gen(NativeScriptBuilder.listModules());
            }
        });
        findViewById(R.id.btn_exports).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String m = etModule.getText().toString().trim();
                if (m.isEmpty()) {
                    GlassToast.warning(FridaNativeActivity.this, "请先填模块名");
                    return;
                }
                gen(NativeScriptBuilder.listExports(m));
            }
        });
        findViewById(R.id.btn_hook_sym).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String m = etModule.getText().toString().trim();
                String s = etSymbol.getText().toString().trim();
                if (m.isEmpty() || s.isEmpty()) {
                    GlassToast.warning(FridaNativeActivity.this, "请填模块名和符号名");
                    return;
                }
                gen(NativeScriptBuilder.hookExport(m, s, true, true));
            }
        });
        findViewById(R.id.btn_hook_off).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String m = etModule.getText().toString().trim();
                String off = etSymbol.getText().toString().trim();
                if (m.isEmpty() || off.isEmpty()) {
                    GlassToast.warning(FridaNativeActivity.this, "请填模块名和偏移(0x..)");
                    return;
                }
                gen(NativeScriptBuilder.hookOffset(m, off));
            }
        });
        findViewById(R.id.btn_calls).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String m = etModule.getText().toString().trim();
                String sym = etSymbol.getText().toString().trim();
                if (m.isEmpty() || sym.isEmpty()) {
                    GlassToast.warning(FridaNativeActivity.this, "请填模块名和符号名");
                    return;
                }
                gen(NativeScriptBuilder.captureCalls(m, sym));
            }
        });
        findViewById(R.id.btn_hex).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String m = etModule.getText().toString().trim();
                if (m.isEmpty()) {
                    GlassToast.warning(FridaNativeActivity.this, "请填模块名");
                    return;
                }
                gen(NativeScriptBuilder.hexDump(m, etSymbol.getText().toString(), etSize.getText().toString()));
            }
        });
        findViewById(R.id.btn_memdiff).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String m = etModule.getText().toString().trim();
                if (m.isEmpty()) {
                    GlassToast.warning(FridaNativeActivity.this, "请填模块名");
                    return;
                }
                gen(NativeScriptBuilder.memDiff(m, etSymbol.getText().toString(), etSize.getText().toString()));
            }
        });
        findViewById(R.id.btn_export_json).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTask("导出调用日志JSON", new Runnable() {
                    @Override
                    public void run() {
                        GadgetManager.exportCallLogJson(pkg, logger());
                    }
                });
            }
        });
        findViewById(R.id.btn_mode_all).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode("all");
            }
        });
        findViewById(R.id.btn_mode_call).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode("call");
            }
        });
        findViewById(R.id.btn_mode_return).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode("return");
            }
        });
        findViewById(R.id.btn_mode_error).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode("error");
            }
        });
        findViewById(R.id.btn_scan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String t = etScan.getText().toString().trim();
                if (t.isEmpty()) {
                    GlassToast.warning(FridaNativeActivity.this, "请填搜索关键字");
                    return;
                }
                gen(NativeScriptBuilder.scanString(t, 100));
            }
        });
        findViewById(R.id.btn_bypass).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gen(NativeScriptBuilder.bypassAntiDebug());
            }
        });

        findViewById(R.id.btn_dump).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String m = etModule.getText().toString().trim();
                if (m.isEmpty()) {
                    GlassToast.warning(FridaNativeActivity.this, "请填模块名");
                    return;
                }
                gen(NativeScriptBuilder.dumpModule(m, etSymbol.getText().toString(),
                        etSize.getText().toString(), pkg));
            }
        });
        findViewById(R.id.btn_write).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String addr = etSymbol.getText().toString().trim();
                String hex = etScan.getText().toString().trim();
                if (addr.isEmpty() || hex.isEmpty()) {
                    GlassToast.warning(FridaNativeActivity.this, "写入需填地址(符号框)与字节(搜索框)");
                    return;
                }
                gen(NativeScriptBuilder.writeMemory(addr, hex));
            }
        });

        findViewById(R.id.btn_export_dump).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTask("导出 Dump", new Runnable() {
                    @Override
                    public void run() {
                        String dst = GadgetManager.exportDumps(pkg, logger());
                        if (dst != null) {
                            appendLog("完成：" + dst);
                        }
                    }
                });
            }
        });

        findViewById(R.id.btn_jdwp_on).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTask("开启 JDWP", new Runnable() {
                    @Override
                    public void run() {
                        GadgetManager.enableJdwp(pkg, logger());
                    }
                });
            }
        });
        findViewById(R.id.btn_jdwp_off).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTask("关闭 JDWP", new Runnable() {
                    @Override
                    public void run() {
                        GadgetManager.disableJdwp(logger());
                    }
                });
            }
        });
        findViewById(R.id.btn_dex_zip).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTask("一键动态分析导出ZIP", new Runnable() {
                    @Override
                    public void run() {
                        GadgetManager.cleanDexDump(pkg);
                        String script = NativeScriptBuilder.dumpDex(pkg);
                        if (!GadgetManager.deploy(FridaNativeActivity.this, script, logger())) {
                            appendLog("部署失败，已中止");
                            return;
                        }
                        if (!GadgetManager.inject(FridaNativeActivity.this, pkg, logger())) {
                            appendLog("注入失败，已中止");
                            return;
                        }
                        appendLog("重启目标 App 触发动态分析 ...");
                        GadgetManager.restartApp(pkg);
                        // 把本页拉回前台，避免等待/打包时被系统后台限制
                        try {
                            cn.mhook.msu.su.getOutput("am start -n cn.mhook.mhook/cn.mhook.activity.debug.FridaNativeActivity"
                                    + " --es pkg " + pkg + " --es name " + (appName == null ? "" : appName));
                        } catch (Throwable ignored) {
                        }
                        appendLog("等待动态分析完成（App 会自动启动，轮询 dexdump）...");
                        int last = -1, stable = 0;
                        for (int i = 1; i <= 60; i++) {
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException ignored) {
                            }
                            int cnt = GadgetManager.countDexDump(pkg);
                            if (cnt == 0 && i % 3 == 0) {
                                String raw = cn.mhook.msu.su.getOutput("ls /data/data/" + pkg + "/files/dexdump 2>&1");
                                appendLog("  diag: [" + (raw == null ? "null" : raw.trim()) + "]");
                            }
                            if (cnt > 0 && cnt == last) {
                                stable++;
                                if (stable >= 2) {
                                    appendLog("  dexdump 稳定在 " + cnt + " 个 dex");
                                    break;
                                }
                            } else {
                                stable = 0;
                            }
                            last = cnt;
                            if (i % 3 == 0 || cnt > 0) {
                                appendLog("  " + (i * 2) + "s: dexdump=" + cnt);
                            }
                        }
                        java.io.File zip = GadgetManager.packageDexDump(FridaNativeActivity.this, pkg, logger());
                        if (zip != null) {
                            appendLog("完成：" + zip.getAbsolutePath());
                        }
                    }
                });
            }
        });
        findViewById(R.id.btn_inject).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doInject();
            }
        });
        findViewById(R.id.btn_uninject).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTask("取消注入", new Runnable() {
                    @Override
                    public void run() {
                        GadgetManager.uninject(pkg, logger());
                    }
                });
            }
        });
        findViewById(R.id.btn_readout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                readOut();
            }
        });
    }

    /** 脚本框：直接读剪贴板粘贴（绕过输入法，避免粘贴不全）、清空、实时字符数。 */
    private void bindScriptTools() {
        final android.widget.TextView len = findViewById(R.id.tv_script_len);
        findViewById(R.id.btn_import_script).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickScriptFile();
            }
        });
        findViewById(R.id.btn_paste_script).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteIntoScript();
            }
        });
        findViewById(R.id.btn_clear_script).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etScript.setText("");
            }
        });
        etScript.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (len != null) {
                    len.setText(s.length() + " 字符");
                }
            }
        });
        if (len != null) {
            len.setText(etScript.getText().length() + " 字符");
        }
    }

    /** 从系统剪贴板整段读取并写入脚本框（不经过输入法，可避免长脚本被截断）。 */
    private void pasteIntoScript() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
                cn.mhook.widget.GlassToast.warning(this, "剪贴板为空");
                return;
            }
            android.content.ClipData clip = cm.getPrimaryClip();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < clip.getItemCount(); i++) {
                CharSequence cs = clip.getItemAt(i).coerceToText(this);
                if (cs != null) {
                    sb.append(cs);
                }
            }
            String text = sb.toString();
            if (text.isEmpty()) {
                cn.mhook.widget.GlassToast.warning(this, "剪贴板为空");
                return;
            }
            if (text.length() > MAX_SCRIPT_BYTES) {
                cn.mhook.widget.GlassToast.error(this, "剪贴板内容过大（上限 2048 KB）");
                return;
            }
            etScript.setText(text);
            etScript.setSelection(text.length());
            cn.mhook.widget.GlassToast.success(this, "已粘贴 " + text.length() + " 字符");
        } catch (Throwable t) {
            cn.mhook.widget.GlassToast.error(this, "粘贴失败：" + t);
        }
    }

    private static final int REQ_SCRIPT_FILE = 9310;
    private static final long MAX_SCRIPT_BYTES = 2 * 1024 * 1024; // 2MB

    /** 从文件导入脚本（完全绕过剪贴板，长脚本不会丢）。 */
    private void pickScriptFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/plain", "text/javascript", "application/javascript",
                    "application/x-javascript", "application/json"});
            startActivityForResult(i, REQ_SCRIPT_FILE);
        } catch (Throwable t) {
            try {
                Intent i2 = new Intent(Intent.ACTION_GET_CONTENT);
                i2.addCategory(Intent.CATEGORY_OPENABLE);
                i2.setType("text/*");
                startActivityForResult(i2, REQ_SCRIPT_FILE);
            } catch (Throwable t2) {
                cn.mhook.widget.GlassToast.error(this, "无法打开文件选择器：" + t2);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCRIPT_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadScriptFromUri(data.getData());
        }
    }

    /** 读取脚本文件：校验扩展名/大小/是否二进制，避免误选 APK 等大文件导致崩溃。 */
    private void loadScriptFromUri(android.net.Uri uri) {
        try {
            String name = null;
            long size = -1;
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                        int si = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                        if (ni >= 0) {
                            name = c.getString(ni);
                        }
                        if (si >= 0 && !c.isNull(si)) {
                            size = c.getLong(si);
                        }
                    }
                } finally {
                    c.close();
                }
            }
            if (name != null && !isScriptName(name)) {
                cn.mhook.widget.GlassToast.error(this, "不是脚本文件：" + name + "（仅支持 .js/.txt/.json）");
                return;
            }
            if (size > MAX_SCRIPT_BYTES) {
                cn.mhook.widget.GlassToast.error(this, "文件过大：" + (size / 1024) + " KB（上限 2048 KB）");
                return;
            }
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_SCRIPT_BYTES) {
                    in.close();
                    cn.mhook.widget.GlassToast.error(this, "文件过大（上限 2048 KB）");
                    return;
                }
                bos.write(buf, 0, n);
            }
            in.close();
            byte[] bytes = bos.toByteArray();
            for (byte b : bytes) {
                if (b == 0) {
                    cn.mhook.widget.GlassToast.error(this, "不是文本脚本（二进制文件）");
                    return;
                }
            }
            String text = new String(bytes, "UTF-8");
            etScript.setText(text);
            etScript.setSelection(text.length());
            cn.mhook.widget.GlassToast.success(this, "已导入 " + text.length() + " 字符");
        } catch (Throwable t) {
            cn.mhook.widget.GlassToast.error(this, "读取文件失败：" + t);
        }
    }

    private static boolean isScriptName(String name) {
        String l = name.toLowerCase(java.util.Locale.ROOT);
        return l.endsWith(".js") || l.endsWith(".txt") || l.endsWith(".json")
                || l.endsWith(".ts") || l.endsWith(".mjs");
    }

    private FridaServerManager.Progress logger() {
        return new FridaServerManager.Progress() {
            @Override
            public void onLog(String s) {
                appendLog(s);
            }
        };
    }

    private void gen(String script) {
        etScript.setText(script);
        appendLog("已生成脚本（可编辑后注入）");
    }

    private void doInject() {
        if (pkg.isEmpty()) {
            GlassToast.warning(this, "无目标应用");
            return;
        }
        final String script = etScript.getText().toString();
        if (script.trim().isEmpty()) {
            GlassToast.warning(this, "脚本为空，先点上面任一操作生成");
            return;
        }
        runTask("注入并运行", new Runnable() {
            @Override
            public void run() {
                if (!GadgetManager.deploy(FridaNativeActivity.this, script, logger())) {
                    appendLog("部署失败，已中止");
                    return;
                }
                GadgetManager.inject(FridaNativeActivity.this, pkg, logger());
                appendLog("请重启目标 App，等其启动完成后再点「刷新输出」");
            }
        });
    }

    private void setMode(String mode) {
        filterMode = mode;
        int on = getResources().getColor(R.color.glass_accent_cyan);
        int off = getResources().getColor(R.color.glass_text_secondary);
        ((android.widget.TextView) findViewById(R.id.btn_mode_all)).setTextColor("all".equals(mode) ? on : off);
        ((android.widget.TextView) findViewById(R.id.btn_mode_call)).setTextColor("call".equals(mode) ? on : off);
        ((android.widget.TextView) findViewById(R.id.btn_mode_return)).setTextColor("return".equals(mode) ? on : off);
        ((android.widget.TextView) findViewById(R.id.btn_mode_error)).setTextColor("error".equals(mode) ? on : off);
    }

    private void readOut() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String s = GadgetManager.readLogcatOutput(etFilter.getText().toString(), filterMode);
                if (s == null || s.trim().isEmpty()) {
                    s = "(暂无输出)\n提示：重启目标 App 并等启动完成后点刷新";
                }
                final String f = s;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        tvOut.setText(f);
                    }
                });
            }
        }).start();
    }

    private void runTask(final String name, final Runnable r) {
        if (busy) {
            GlassToast.info(this, "正在执行，请稍候");
            return;
        }
        busy = true;
        appendLog("==== " + name + " ====");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    appendLog("异常：" + t);
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    private void appendLog(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                tvOut.append(s + "\n");
                if (scrollLog != null) {
                    scrollLog.post(new Runnable() {
                        @Override
                        public void run() {
                            scrollOut.fullScroll(View.FOCUS_DOWN);
                        }
                    });
                }
            }
        });
    }
}
