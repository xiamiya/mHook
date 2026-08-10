package cn.mhook.activity.xp;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.tamsiree.rxkit.RxEncryptTool;
import com.tamsiree.rxkit.RxTimeTool;
import com.tamsiree.rxkit.view.RxToast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import cn.mhook.BaseActivity;
import cn.mhook.ai.AiPrompt;
import cn.mhook.ai.AiSession;
import cn.mhook.ai.AiSetting;
import cn.mhook.ai.ResultParser;
import cn.mhook.analyze.XpExtract;
import cn.mhook.mhook.R;
import cn.mhook.mhook.contentprovider.jsonCfg;

/**
 * XP模块分析AI版：独立入口。dexlib2 提取 hook 点 → 调用 mHook 原有 AI 模块，
 * 在输出区实时展示分析过程（提取统计 / AI 流式输出 / 工具调用 / 解析结果）。
 */
public class XpModuleAiActivity extends BaseActivity {

    private static final int REQ_PICK_APK = 9012;

    private Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private TextView outputView;
    private ScrollView scrollView;
    private File apkFile;
    private boolean running = false;
    private boolean extracting = false;
    private String extractedDump;
    private List<JSONObject> parsedList;
    private SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_xp_module_ai);
        initView();
    }

    @SuppressLint("ResourceAsColor")
    private void initView() {
        statusView = findViewById(R.id.xpai_status);
        outputView = findViewById(R.id.xpai_output);
        scrollView = findViewById(R.id.xpai_scroll);

        findViewById(R.id.xpai_select_btn).setBackgroundColor(getResources().getColor(R.color.app_color_theme_7));
        findViewById(R.id.xpai_run_btn).setBackgroundColor(getResources().getColor(R.color.blue));
        findViewById(R.id.xpai_import_btn).setBackgroundColor(getResources().getColor(R.color.green));

        findViewById(R.id.xpai_select_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApk();
            }
        });
        findViewById(R.id.xpai_run_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAiAnalyze();
            }
        });
        findViewById(R.id.xpai_import_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importAll();
            }
        });
        findViewById(R.id.xpai_title).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        try {
            startActivityForResult(intent, REQ_PICK_APK);
        } catch (Throwable t) {
            intent.setType("*/*");
            try {
                startActivityForResult(intent, REQ_PICK_APK);
            } catch (Throwable t2) {
                RxToast.error("无法打开文件选择器");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_APK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            copyApk(data.getData());
        }
    }

    private void copyApk(final Uri uri) {
        appendLog("选择模块 APK，开始复制到缓存…");
        setStatus("正在复制 APK…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(getCacheDir(), "xp_ai");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new Exception("无法创建缓存目录");
                    }
                    String name = queryName(uri);
                    File apk = new File(dir, name.endsWith(".apk") ? name : (name + ".apk"));
                    copyUri(uri, apk);
                    apkFile = apk;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("✓ APK 已就绪：" + apkFile.getName());
                            setStatus("已选择：" + apkFile.getName() + "，正在提取 hook 点…");
                            extractAndShow();
                        }
                    });
                } catch (final Throwable t) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            appendLog("✗ 复制失败：" + t.getMessage());
                            setStatus("复制失败: " + t.getMessage());
                            RxToast.error("复制失败: " + t.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private String queryName(Uri uri) {
        try {
            android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    c.close();
                    return n;
                }
                c.close();
            }
        } catch (Throwable ignored) {
        }
        return "apk_" + System.currentTimeMillis();
    }

    private void copyUri(Uri uri, File out) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
    }

    private void startAiAnalyze() {
        if (running || extracting) {
            RxToast.info("正在处理中…");
            return;
        }
        if (apkFile == null || !apkFile.exists()) {
            RxToast.warning("请先选择 APK");
            return;
        }
        if (AiSetting.baseUrl(this).isEmpty() || AiSetting.apiKey(this).isEmpty() || AiSetting.model(this).isEmpty()) {
            RxToast.warning("请先完成 AI 设置");
            openAiSettings();
            return;
        }
        if (extractedDump != null) {
            runAi(extractedDump);
        } else {
            extractAndRunAi();
        }
    }

    private void extractAndShow() {
        if (extracting) return;
        extracting = true;
        outputView.setText("分析过程将在这里实时显示");
        appendLog("[提取] 用 dexlib2 提取 hook 点…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long t0 = System.currentTimeMillis();
                final String dump;
                try {
                    dump = XpExtract.extract(apkFile);
                } catch (final Throwable t) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            extracting = false;
                            appendLog("✗ 提取失败：" + t.getMessage());
                            setStatus("提取失败: " + t.getMessage());
                            RxToast.error("提取失败: " + t.getMessage());
                        }
                    });
                    return;
                }
                final long dt = System.currentTimeMillis() - t0;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        extracting = false;
                        extractedDump = dump;
                        showExtractResult(dump, dt);
                    }
                });
            }
        }).start();
    }

    private void extractAndRunAi() {
        if (extracting || running) return;
        running = true;
        parsedList = null;
        findViewById(R.id.xpai_import_btn).setEnabled(false);
        outputView.setText("分析过程将在这里实时显示");
        appendLog("[提取] 用 dexlib2 提取 hook 点…");
        setStatus("正在提取 hook 点…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String dump;
                try {
                    dump = XpExtract.extract(apkFile);
                } catch (final Throwable t) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            running = false;
                            appendLog("✗ 提取失败：" + t.getMessage());
                            setStatus("提取失败: " + t.getMessage());
                            RxToast.error("提取失败: " + t.getMessage());
                        }
                    });
                    return;
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        extractedDump = dump;
                        runAi(dump);
                    }
                });
            }
        }).start();
    }

    private void showExtractResult(String dump, long dt) {
        String[] lines = dump.split("\n");
        int hooks = 0, cbs = 0;
        java.util.List<String> hookLines = new ArrayList<String>();
        for (String l : lines) {
            if (l.startsWith("HOOK ")) { hooks++; hookLines.add(l); }
            if (l.startsWith("### CALLBACK ")) cbs++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("✓ 提取完成：检测到 ").append(hooks).append(" 个 hook 点 / ").append(cbs)
          .append(" 个回调（用时 ").append(dt / 1000.0).append("s）\n");
        if (XpExtract.heavyObfuscation) {
            sb.append("⚠ 检测到高强度混淆：hook 目标字符串加密/被混淆，静态分析可能无法还原。\n");
            for (String l : lines) {
                if (l.startsWith("=>")) { sb.append(l).append('\n'); break; }
            }
        } else {
            sb.append("✓ 未检测到明显混淆。\n");
        }
        sb.append("—— 提取结果预览 ——\n");
        for (int i = 0; i < Math.min(hookLines.size(), 15); i++) {
            sb.append(hookLines.get(i)).append('\n');
        }
        if (hookLines.size() > 15) sb.append("  … 共 ").append(hooks).append(" 条\n");
        outputView.setText(sb.toString());
        scrollBottom();
        if (XpExtract.heavyObfuscation) {
            setStatus("提取完成（含高强度混淆提示），可「开始AI分析」尝试，或直接放弃");
            RxToast.warning("检测到高强度混淆，hook 目标可能无法静态还原");
        } else {
            setStatus("提取完成，可点击「开始AI分析」生成配置");
            RxToast.success("提取完成：" + hooks + " 个 hook 点");
        }
    }

    private void runAi(final String dump) {
        appendLog("[步骤 1/2] 调用 AI 分析（模型 " + AiSetting.model(this) + "）…");
        setStatus("AI 分析中…");
        String system = AiPrompt.buildModule(this, apkFile.getName());
        String user = "XP 模块 APK 提取结果（文件 " + apkFile.getName() + "）：\n\n" + dump;

        AiSession.run(this, system, user, new AiSession.Listener() {
            @Override
            public void onDelta(String text) {
                outputView.append(text);
                scrollBottom();
            }

            @Override
            public void onToolEvent(String text) {
                appendLog("\n— 工具调用：\n" + text);
            }

            @Override
            public void onDone(String fullText) {
                running = false;
                if (fullText == null || fullText.trim().isEmpty()) {
                    appendLog("✗ AI 未返回内容（可能已停止或超限）");
                    setStatus("AI 未返回内容");
                    RxToast.warning("AI 未返回内容");
                    return;
                }
                appendLog("\n[步骤 2/2] 解析 AI 结果…");
                try {
                    parsedList = ResultParser.parseHookApps(fullText);
                    int apps = parsedList.size();
                    int hooks = 0;
                    for (JSONObject p : parsedList) {
                        JSONArray h = p.getJSONArray("hooks");
                        if (h != null) hooks += h.size();
                    }
                    StringBuilder sb = new StringBuilder("✓ 解析成功：" + apps + " 个应用 / " + hooks + " 条 hook：\n");
                    for (JSONObject p : parsedList) {
                        sb.append("  • ").append(p.getString("appPkg")).append("（")
                          .append(p.getJSONArray("hooks").size()).append(" 条）\n");
                    }
                    appendLog(sb.toString());
                    findViewById(R.id.xpai_import_btn).setEnabled(true);
                    setStatus("解析成功，可点击「导入」。");
                    RxToast.success("解析成功：" + apps + " 个应用 / " + hooks + " 条 hook");
                } catch (Exception e) {
                    appendLog("✗ 解析失败：" + e.getMessage());
                    setStatus("解析失败：" + e.getMessage());
                    RxToast.error("解析失败：" + e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                running = false;
                appendLog("✗ AI 请求失败：" + t.getMessage());
                setStatus("AI 请求失败：" + t.getMessage());
                RxToast.error("AI 请求失败：" + t.getMessage());
            }
        });
    }

    private void importAll() {
        if (parsedList == null || parsedList.isEmpty()) {
            RxToast.warning("没有可导入的配置");
            return;
        }
        int added = 0, skipped = 0;
        for (JSONObject p : parsedList) {
            String pkg = p.getString("appPkg");
            if (pkg == null || pkg.isEmpty()) {
                skipped++;
                continue;
            }
            JSONObject cfg = ResultParser.buildHookConfig(pkg, pkg, "", p);
            cfg.put("time", RxTimeTool.getCurTimeString());
            String key = RxEncryptTool.encryptMD5ToString(cfg.toJSONString());
            cfg.put("keyStr", key);
            Boolean ok = jsonCfg.addCfg(pkg, true, false, key, cfg, false);
            if (Boolean.TRUE.equals(ok)) added++; else skipped++;
        }
        appendLog("导入完成：新增 " + added + " 个，跳过 " + skipped + " 个（已存在或无效）。");
        parsedList = null;
        RxToast.success("导入完成：新增 " + added + " 个，跳过 " + skipped + " 个");
        setStatus("导入完成。");
    }

    private void openAiSettings() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("AI 设置")
                .setMessage("请先在 AI 设置里配置 base_url / API Key / 模型名。\n\n点击确定打开「AI 分析」页面完成设置。")
                .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            startActivity(new Intent(XpModuleAiActivity.this, cn.mhook.activity.ai.AiActivity.class));
                        } catch (Throwable t) {
                            RxToast.error("无法打开 AI 设置页");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .create().show();
    }

    private void appendLog(String s) {
        String tag = "[" + timeFmt.format(new Date()) + "] ";
        String text = outputView.getText().toString();
        if (text.equals("分析过程将在这里实时显示")) text = "";
        outputView.setText((text.isEmpty() ? "" : text + "\n") + tag + s);
        scrollBottom();
    }

    private void scrollBottom() {
        try {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void setStatus(String s) {
        statusView.setText(s);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AiSession.stop();
    }
}
