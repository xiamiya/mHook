package cn.mhook.activity.ai;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.rengwuxian.materialedittext.MaterialEditText;
import com.tamsiree.rxkit.RxActivityTool;
import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.RxClipboardTool;
import com.tamsiree.rxkit.RxEncryptTool;
import com.tamsiree.rxkit.RxTimeTool;
import com.tamsiree.rxkit.view.RxToast;

import java.io.File;

import cn.mhook.BaseActivity;
import cn.mhook.activity.editcfg.EditHookActivity;
import cn.mhook.activity.selectapp.SelectActivity;
import cn.mhook.ai.AiClient;
import cn.mhook.ai.AiPrompt;
import cn.mhook.ai.AiSession;
import cn.mhook.ai.AiSetting;
import cn.mhook.ai.McpSetting;
import cn.mhook.ai.ResultParser;
import cn.mhook.mhook.R;
import cn.mhook.mhook.contentprovider.jsonCfg;

import static cn.mhook.mData.mDir;
import static cn.mhook.msu.su.set777;

public class AiActivity extends BaseActivity {

    private TextView aiAppName;
    private TextView aiOutput;
    private MaterialEditText aiInput;

    private String appPkg;
    private String appName;
    private JSONObject parsed;
    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai);
        aiAppName = findViewById(R.id.aiAppName);
        aiOutput = findViewById(R.id.aiOutput);
        aiInput = findViewById(R.id.aiInput);
        initButtons();
    }

    private void initButtons(){
        findViewById(R.id.aiSelectAppCard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectApp();
            }
        });
        findViewById(R.id.aiStart).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startAnalysis();
            }
        });
        findViewById(R.id.aiStop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AiClient.stop();
                AiSession.stop();
            }
        });
        findViewById(R.id.aiSettingBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
            }
        });
        findViewById(R.id.aiMcpBtn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMcpSettings();
            }
        });
        findViewById(R.id.aiFill).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fillEditPage();
            }
        });
        findViewById(R.id.aiSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
            }
        });
        findViewById(R.id.aiFix).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startFix();
            }
        });
        findViewById(R.id.aiCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RxClipboardTool.copyText(AiActivity.this, aiOutput.getText().toString());
                RxToast.success("已复制 AI 输出");
            }
        });
    }

    private void selectApp(){
        Bundle bundle = new Bundle();
        bundle.putString("appType", "all");
        RxActivityTool.skipActivityForResult(AiActivity.this, SelectActivity.class, bundle, 9008);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 9008 && resultCode == RESULT_OK){
            String pkg = data.getStringExtra("pkg");
            if (pkg != null && !pkg.isEmpty()){
                appPkg = pkg;
                appName = RxAppTool.getAppName(AiActivity.this, pkg);
                aiAppName.setText(appName + "\n" + pkg);
            }
        }
    }

    private void startAnalysis(){
        if (running){
            RxToast.info("正在分析中…");
            return;
        }
        if (appPkg == null || appPkg.isEmpty()){
            RxToast.warning("请先选择目标应用");
            return;
        }
        String requirement = aiInput.getText().toString().trim();
        if (requirement.isEmpty()){
            RxToast.warning("请输入需求描述");
            return;
        }
        if (AiSetting.baseUrl(this).isEmpty() || AiSetting.apiKey(this).isEmpty() || AiSetting.model(this).isEmpty()){
            RxToast.warning("请先完成 AI 设置");
            showSettings();
            return;
        }
        running = true;
        parsed = null;
        aiOutput.setText("");
        setActionButtonsEnabled(false);
        RxToast.info("AI 分析中…");

        String system = AiPrompt.build(this, appName + "（" + appPkg + "）");
        String user = "目标应用：" + appName + "（" + appPkg + "）\n需求：" + requirement;

        AiSession.run(this, system, user, new AiSession.Listener() {
            @Override
            public void onDelta(String text) {
                aiOutput.append(text);
                scrollBottom();
            }

            @Override
            public void onToolEvent(String text) {
                aiOutput.append("\n\n— " + text + "\n\n");
                scrollBottom();
            }

            @Override
            public void onDone(String fullText) {
                running = false;
                try {
                    parsed = ResultParser.parseAndNormalize(fullText);
                    RxToast.success("解析成功：" + parsed.getString("action"));
                    enableActions();
                }catch (Exception e){
                    RxToast.error("解析失败：" + e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                running = false;
                RxToast.error("AI 请求失败：" + t.getMessage());
            }
        });
    }

    private void scrollBottom(){
        try {
            final ScrollView sv = (ScrollView) aiOutput.getParent();
            sv.post(new Runnable() {
                @Override
                public void run() {
                    sv.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        }catch (Throwable ignored){
        }
    }

    private void setActionButtonsEnabled(boolean enable){
        findViewById(R.id.aiFill).setEnabled(enable);
        findViewById(R.id.aiSave).setEnabled(enable);
    }

    private void enableActions(){
        if (parsed == null){
            return;
        }
        String action = parsed.getString("action");
        findViewById(R.id.aiFill).setEnabled("saveHook".equals(action));
        findViewById(R.id.aiSave).setEnabled("saveHook".equals(action));
    }

    private void fillEditPage(){
        if (parsed == null || !"saveHook".equals(parsed.getString("action"))){
            RxToast.warning("当前结果不是 Hook 配置");
            return;
        }
        JSONObject cfg = buildHookConfig();
        Intent intent = new Intent(AiActivity.this, EditHookActivity.class);
        intent.putExtra("AiCfg", cfg.toJSONString());
        startActivity(intent);
    }

    private JSONObject buildHookConfig(){
        JSONObject cfg = ResultParser.buildHookConfig(appPkg, appName,
                RxAppTool.getAppVersionName(AiActivity.this, appPkg), parsed);
        cfg.put("time", RxTimeTool.getCurTimeString());
        return cfg;
    }

    private void saveConfig(){
        if (parsed == null || !"saveHook".equals(parsed.getString("action"))){
            RxToast.warning("当前结果不是 Hook 配置");
            return;
        }
        final JSONObject cfg = buildHookConfig();
        final String newKey = RxEncryptTool.encryptMD5ToString(cfg.toJSONString());
        cfg.put("keyStr", newKey);
        if (jsonCfg.getCfgByKey(newKey) != null){
            new QMUIDialog.MessageDialogBuilder(AiActivity.this)
                    .setTitle("重复配置")
                    .setMessage("已存在相同配置，是否覆盖？")
                    .setSkinManager(QMUISkinManager.defaultInstance(AiActivity.this))
                    .addAction("跳过", new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
                            dialog.dismiss();
                        }
                    })
                    .addAction(0, "覆盖", QMUIDialogAction.ACTION_PROP_NEGATIVE, new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
                            jsonCfg.delConfig(appPkg, newKey);
                            doAddConfig(cfg, newKey);
                            dialog.dismiss();
                        }
                    })
                    .create().show();
            return;
        }
        final java.util.List<JSONObject> samePkg = getCfgByPkg(appPkg);
        if (samePkg.isEmpty()){
            doAddConfig(cfg, newKey);
        }else {
            new QMUIDialog.MessageDialogBuilder(AiActivity.this)
                    .setTitle("重复配置")
                    .setMessage("该软件已存在 " + samePkg.size() + " 个配置，是否覆盖？")
                    .setSkinManager(QMUISkinManager.defaultInstance(AiActivity.this))
                    .addAction("跳过", new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
                            dialog.dismiss();
                        }
                    })
                    .addAction(0, "覆盖", QMUIDialogAction.ACTION_PROP_NEGATIVE, new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
                            for (JSONObject j : samePkg){
                                jsonCfg.delConfig(appPkg, j.getString("KeyStr"));
                            }
                            doAddConfig(cfg, newKey);
                            dialog.dismiss();
                        }
                    })
                    .create().show();
        }
    }

    private void doAddConfig(JSONObject cfg, String key){
        Boolean success = jsonCfg.addCfg(appPkg, true, false, key, cfg, false);
        if (success){
            jsonCfg.getAllCfg();
            RxToast.success("保存成功，已启用");
        }else {
            RxToast.warning("已存在相同配置");
        }
    }

    private java.util.List<JSONObject> getCfgByPkg(String pkg){
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        com.alibaba.fastjson.JSONArray all = jsonCfg.getAllCfg();
        for (Object o : all){
            JSONObject j = JSONObject.parseObject(o.toString());
            if (pkg.equals(j.getString("pkg"))){
                list.add(j);
            }
        }
        return list;
    }

    private void startFix(){
        if (running){
            RxToast.info("正在处理中…");
            return;
        }
        if (appPkg == null || appPkg.isEmpty()){
            RxToast.warning("请先选择目标应用");
            return;
        }
        if (AiSetting.baseUrl(this).isEmpty() || AiSetting.apiKey(this).isEmpty() || AiSetting.model(this).isEmpty()){
            RxToast.warning("请先完成 AI 设置");
            showSettings();
            return;
        }
        if (McpSetting.enabledCount(this) == 0){
            RxToast.warning("请先在 MCP 设置中启用 MT 服务器");
            startActivity(new Intent(this, McpSettingActivity.class));
            return;
        }
        String requirement = aiInput.getText().toString().trim();
        if (requirement.isEmpty()){
            RxToast.warning("请输入要修改的需求");
            return;
        }
        String prevAnalysis = aiOutput.getText().toString();
        if (prevAnalysis == null || prevAnalysis.trim().isEmpty()
                || prevAnalysis.contains("AI 输出将在这里显示")){
            prevAnalysis = null;
        }
        running = true;
        parsed = null;
        aiOutput.setText("");
        findViewById(R.id.aiFill).setEnabled(false);
        findViewById(R.id.aiSave).setEnabled(false);
        RxToast.info("AI 改包中…");

        String system = AiPrompt.buildFix(this, appName + "（" + appPkg + "）", requirement);
        String user = "目标应用：" + appName + "（" + appPkg + "）\n需求：" + requirement
                + "\n请调用 MT MCP 打开该 APK 完成修改并构建签名 APK。";
        if (prevAnalysis != null){
            user += "\n\n【上一次分析的结论（已定位到修改方法及位置，直接沿用，不要再从头分析）】\n"
                    + prevAnalysis;
        }

        AiSession.run(this, system, user, new AiSession.Listener() {
            @Override
            public void onDelta(String text) {
                aiOutput.append(text);
                scrollBottom();
            }

            @Override
            public void onToolEvent(String text) {
                aiOutput.append("\n\n— " + text + "\n\n");
                scrollBottom();
            }

            @Override
            public void onDone(String fullText) {
                running = false;
                handleFixDone(fullText);
            }

            @Override
            public void onError(Throwable t) {
                running = false;
                RxToast.error("改包失败：" + t.getMessage());
            }
        });
    }

    private void handleFixDone(String fullText){
        try {
            String json = extractJsonBlock(fullText);
            if (json == null){
                RxToast.error("未识别到改包结果 JSON");
                return;
            }
            JSONObject res = JSONObject.parseObject(json);
            String action = res.getString("action");
            if (!"fixDone".equals(action)){
                RxToast.error("改包未完成：" + res.getString("reason"));
                return;
            }
            String outputName = res.getString("outputName");
            String built = findBuiltApk();
            if (built == null){
                new QMUIDialog.MessageDialogBuilder(AiActivity.this)
                        .setTitle("AI 改包完成")
                        .setMessage("AI 已在 MT 端构建出签名 APK（" + (outputName == null ? "见 MT MCP 输出目录" : outputName)
                                + "），但本机未定位到该文件。\n\n若 MT 与 mHook 不在同一设备，请到 MT 管理器 MCP 目录取回该 APK 使用；修改摘要：" + res.getString("detail"))
                        .setSkinManager(QMUISkinManager.defaultInstance(AiActivity.this))
                        .addAction("知道了", new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                dialog.dismiss();
                            }
                        })
                        .create().show();
                return;
            }
            String saved = saveBuiltApk(appPkg, built);
            if (saved == null){
                RxToast.error("定位到产物但拷贝失败：" + built);
                return;
            }
            RxClipboardTool.copyText(AiActivity.this, saved);
            new QMUIDialog.MessageDialogBuilder(AiActivity.this)
                    .setTitle("AI 改包完成")
                    .setMessage("修改后的签名 APK 已保存：\n" + saved + "\n\n修改摘要：" + res.getString("detail")
                            + "\n\n可用 MT管理器/玄星逆核 安装或继续二次修改。路径已复制到剪贴板。")
                    .setSkinManager(QMUISkinManager.defaultInstance(AiActivity.this))
                    .addAction("知道了", new QMUIDialogAction.ActionListener() {
                        @Override
                        public void onClick(QMUIDialog dialog, int index) {
                            dialog.dismiss();
                        }
                    })
                    .create().show();
        } catch (Throwable t) {
            RxToast.error("处理改包结果失败：" + t.getMessage());
        }
    }

    private String extractJsonBlock(String text){
        if (text == null){
            return null;
        }
        int s = text.indexOf("```json");
        if (s >= 0){
            s = text.indexOf('\n', s);
            int e = text.indexOf("```", s + 1);
            if (e > s){
                return text.substring(s + 1, e).trim();
            }
        }
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        if (a >= 0 && b > a){
            return text.substring(a, b + 1);
        }
        return null;
    }

    private String findBuiltApk(){
        String[] bases = new String[]{
                "/sdcard/Android/data/bin.mt.plus/mcp",
                "/sdcard/Android/data/bin.mt.plus.canary/mcp"
        };
        long newest = 0;
        String pick = null;
        for (String base : bases){
            File d = new File(base);
            File[] files = d.isDirectory() ? d.listFiles() : null;
            if (files != null){
                for (File f : files){
                    if (f.isFile() && f.getName().endsWith("_sign.apk") && f.lastModified() > newest){
                        newest = f.lastModified();
                        pick = f.getAbsolutePath();
                    }
                }
            }
        }
        if (pick != null){
            return pick;
        }
        String out = rootExec("su", "-c",
                "ls -t '" + bases[0] + "/*_sign.apk' '" + bases[1] + "/*_sign.apk' 2>/dev/null | head -n 1");
        if (out != null && !out.trim().isEmpty()){
            return out.trim();
        }
        return null;
    }

    private String saveBuiltApk(String pkg, String src){
        try {
            String destDir = mDir + pkg + "/成品";
            String dest = destDir + "/" + new File(src).getName();
            rootExec("su", "-c", "mkdir -p '" + destDir + "'");
            rootExec("su", "-c", "cp -f '" + src + "' '" + dest + "'");
            set777();
            return new File(dest).exists() ? dest : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String rootExec(String... cmd){
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null){
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void showSettings(){
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        container.setPadding(pad, pad / 2, pad, 0);

        final MaterialEditText baseUrl = new MaterialEditText(this);
        baseUrl.setHint("接口地址 base_url");
        baseUrl.setHelperText("如 https://api.openai.com/v1");
        baseUrl.setText(AiSetting.baseUrl(this));
        container.addView(baseUrl);

        final MaterialEditText apiKey = new MaterialEditText(this);
        apiKey.setHint("API Key");
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setText(AiSetting.apiKey(this));
        container.addView(apiKey);

        final MaterialEditText model = new MaterialEditText(this);
        model.setHint("模型名");
        model.setHelperText("如 gpt-4o-mini");
        model.setText(AiSetting.model(this));
        container.addView(model);

        final MaterialEditText maxTokens = new MaterialEditText(this);
        maxTokens.setHint("最大输出 Tokens");
        maxTokens.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxTokens.setText(String.valueOf(AiSetting.maxTokens(this)));
        container.addView(maxTokens);

        final MaterialEditText maxSteps = new MaterialEditText(this);
        maxSteps.setHint("最大工具调用轮数");
        maxSteps.setHelperText("AI 可连续调用工具的轮次上限（默认 32）");
        maxSteps.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxSteps.setText(String.valueOf(AiSetting.maxSteps(this)));
        container.addView(maxSteps);

        new AlertDialog.Builder(this)
                .setTitle("AI 设置")
                .setView(container)
                .setNeutralButton("连接测试", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        saveSettings(baseUrl, apiKey, model, maxTokens, maxSteps);
                        testConnection();
                    }
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        saveSettings(baseUrl, apiKey, model, maxTokens, maxSteps);
                        RxToast.success("已保存");
                    }
                })
                .show();
    }

    private void saveSettings(MaterialEditText baseUrl, MaterialEditText apiKey, MaterialEditText model,
                              MaterialEditText maxTokens, MaterialEditText maxSteps){
        AiSetting.setBaseUrl(AiActivity.this, baseUrl.getText().toString());
        AiSetting.setApiKey(AiActivity.this, apiKey.getText().toString());
        AiSetting.setModel(AiActivity.this, model.getText().toString());
        try {
            AiSetting.setMaxTokens(AiActivity.this, Integer.parseInt(maxTokens.getText().toString().trim()));
        }catch (Throwable ignored){
        }
        try {
            AiSetting.setMaxSteps(AiActivity.this, Integer.parseInt(maxSteps.getText().toString().trim()));
        }catch (Throwable ignored){
        }
    }

    private void testConnection(){
        if (AiSetting.baseUrl(this).isEmpty() || AiSetting.apiKey(this).isEmpty() || AiSetting.model(this).isEmpty()){
            RxToast.warning("请先填写接口地址 / API Key / 模型");
            return;
        }
        RxToast.info("正在测试连接…");
        AiClient.stream(AiActivity.this,
                "你是连通性测试助手，只回复 OK 即可，不要输出其他内容。",
                "测试连接，请回复 OK。",
                new AiClient.Listener() {
                    @Override
                    public void onDelta(String text) {
                    }

                    @Override
                    public void onToolCalls(JSONArray toolCalls) {
                    }

                    @Override
                    public void onDone(String fullText) {
                        String r = fullText == null ? "" : fullText.trim();
                        android.util.Log.i("XpAiTest", "conn ok: " + r);
                        RxToast.success("连接成功：" + (r.isEmpty() ? "已响应" : r));
                    }

                    @Override
                    public void onError(Throwable t) {
                        android.util.Log.w("XpAiTest", "conn fail: " + t.getMessage(), t);
                        RxToast.error("连接失败：" + t.getMessage());
                    }
                });
    }

    private void showMcpSettings(){
        Intent intent = new Intent(AiActivity.this, McpSettingActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AiClient.stop();
        AiSession.stop();
    }
}
