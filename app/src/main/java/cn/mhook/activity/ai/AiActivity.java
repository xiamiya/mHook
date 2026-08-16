package cn.mhook.activity.ai;

import android.app.Activity;
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
import com.rengwuxian.materialedittext.MaterialEditText;
import com.tamsiree.rxkit.RxActivityTool;
import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.RxClipboardTool;
import com.tamsiree.rxkit.RxEncryptTool;
import com.tamsiree.rxkit.RxTimeTool;
import com.tamsiree.rxkit.view.RxToast;

import java.io.File;

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

public class AiActivity extends Activity {

    private TextView aiAppName;
    private TextView aiOutput;
    private EditText aiInput;

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
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.aiClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                aiOutput.setText("AI 输出将在这里显示...");
            }
        });
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
                cn.mhook.widget.GlassToast.success(AiActivity.this, "已复制 AI 输出");
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
                aiAppName.setTextColor(getResources().getColor(R.color.glass_accent_blue));
            }
        }
    }

    private void startAnalysis(){
        if (running){
            cn.mhook.widget.GlassToast.info(AiActivity.this, "正在分析中…");
            return;
        }
        if (appPkg == null || appPkg.isEmpty()){
            cn.mhook.widget.GlassToast.warning(AiActivity.this, "请先选择目标应用");
            return;
        }
        String requirement = aiInput.getText().toString().trim();
        if (requirement.isEmpty()){
            cn.mhook.widget.GlassToast.warning(AiActivity.this, "请输入需求描述");
            return;
        }
        if (AiSetting.baseUrl(this).isEmpty() || AiSetting.apiKey(this).isEmpty() || AiSetting.model(this).isEmpty()){
            cn.mhook.widget.GlassToast.warning(AiActivity.this, "请先完成 AI 设置");
            showSettings();
            return;
        }
        running = true;
        parsed = null;
        aiOutput.setText("");
        setActionButtonsEnabled(false);
        cn.mhook.widget.GlassToast.info(AiActivity.this, "AI 分析中…");

        String system = AiPrompt.build(this, appName + "（" + appPkg + "）");
        String user = "目标应用：" + appName + "（" + appPkg + "）\n需求：" + requirement;

        AiSession.run(this, system, user, new AiSession.Listener() {
            @Override
            public void onDelta(String text) {
                aiOutput.append(text);
                scrollBottom();
            }

            public void onReasoning(String text) {
                appendThinking(text);
                scrollBottom();
            }

            @Override
            public void onToolEvent(String text) {
                appendToolEvent(text);
            }

            @Override
            public void onDone(String fullText) {
                running = false;
                if (fullText == null || fullText.trim().isEmpty()){
                    return;
                }
                try {
                    JSONObject obj = ResultParser.parseRaw(fullText);
                    String action = obj.getString("action");
                    if ("saveHook".equals(action)){
                        parsed = ResultParser.parseAndNormalize(fullText);
                        cn.mhook.widget.GlassToast.success(AiActivity.this, "解析成功：可导入的 Hook 配置");
                        enableActions();
                    }else if ("fixDone".equals(action)){
                        parsed = null;
                        cn.mhook.widget.GlassToast.success(AiActivity.this, "AI 已通过 MCP 完成改包");
                        handleFixDoneResult(obj);
                    }else if ("patchPlan".equals(action)){
                        parsed = null;
                        renderPatchPlan(obj);
                    }else {
                        parsed = null;
                        cn.mhook.widget.GlassToast.error(AiActivity.this, "无法识别的结果 action：" + action);
                    }
                }catch (Throwable e){
                    parsed = null;
                    cn.mhook.widget.GlassToast.error(AiActivity.this, "解析失败：" + e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                running = false;
                cn.mhook.widget.GlassToast.error(AiActivity.this, "AI 请求失败：" + t.getMessage());
            }
        });
    }

    private void appendToolEvent(String text){
        aiOutput.append("\n\n— " + text + "\n\n");
        if (text != null && text.contains("[MCP 连接断开]")){
            cn.mhook.widget.GlassToast.error(AiActivity.this, "MCP 服务连接断开，本次会话已终止");
        }
        scrollBottom();
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

    private void appendThinking(String text){
        try {
            android.text.SpannableString sp = new android.text.SpannableString(text);
            sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF7DD3FC),
                    0, sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            aiOutput.append(sp);
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
            cn.mhook.widget.GlassToast.warning(AiActivity.this, "当前结果不是 Hook 配置");
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
            cn.mhook.widget.GlassToast.warning(AiActivity.this, "当前结果不是 Hook 配置");
            return;
        }
        final JSONObject cfg = buildHookConfig();
        final String newKey = RxEncryptTool.encryptMD5ToString(cfg.toJSONString());
        cfg.put("keyStr", newKey);
        if (jsonCfg.getCfgByKey(newKey) != null){
            confirmOverwrite("已存在相同配置，是否覆盖？", cfg, newKey, null);
            return;
        }
        final java.util.List<JSONObject> samePkg = getCfgByPkg(appPkg);
        if (samePkg.isEmpty()){
            doAddConfig(cfg, newKey);
        }else {
            confirmOverwrite("该软件已存在 " + samePkg.size() + " 个配置，是否覆盖？", cfg, newKey, samePkg);
        }
    }

    private void confirmOverwrite(String message, final JSONObject cfg, final String newKey,
                                  final java.util.List<JSONObject> samePkg){
        new AlertDialog.Builder(this)
                .setTitle("重复配置")
                .setMessage(message)
                .setNegativeButton("跳过", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .setPositiveButton("覆盖", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        jsonCfg.delConfig(appPkg, newKey);
                        if (samePkg != null){
                            for (JSONObject j : samePkg){
                                jsonCfg.delConfig(appPkg, j.getString("KeyStr"));
                            }
                        }
                        doAddConfig(cfg, newKey);
                        dialog.dismiss();
                    }
                })
                .create().show();
    }

    private void doAddConfig(JSONObject cfg, String key){
        Boolean success = jsonCfg.addCfg(appPkg, true, false, key, cfg, false);
        if (success){
            jsonCfg.getAllCfg();
            cn.mhook.widget.GlassToast.success(AiActivity.this, "保存成功，已启用");
        }else {
            cn.mhook.widget.GlassToast.warning(AiActivity.this, "已存在相同配置");
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
            cn.mhook.widget.GlassToast.info(AiActivity.this, "正在处理中…");
            return;
        }
        if (appPkg == null || appPkg.isEmpty()){
            cn.mhook.widget.GlassToast.warning(AiActivity.this, "请先选择目标应用");
            return;
        }
        if (AiSetting.baseUrl(this).isEmpty() || AiSetting.apiKey(this).isEmpty() || AiSetting.model(this).isEmpty()){
            cn.mhook.widget.GlassToast.warning(AiActivity.this, "请先完成 AI 设置");
            showSettings();
            return;
        }
        if (McpSetting.enabledCount(this) == 0){
            cn.mhook.widget.GlassToast.warning(AiActivity.this, "请先在 MCP 设置中启用 MT 服务器");
            startActivity(new Intent(this, McpSettingActivity.class));
            return;
        }
        String requirement = aiInput.getText().toString().trim();
        if (requirement.isEmpty()){
            cn.mhook.widget.GlassToast.warning(AiActivity.this, "请输入要修改的需求");
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
        cn.mhook.widget.GlassToast.info(AiActivity.this, "AI 改包中…");

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

            public void onReasoning(String text) {
                appendThinking(text);
                scrollBottom();
            }

            @Override
            public void onToolEvent(String text) {
                appendToolEvent(text);
            }

            @Override
            public void onDone(String fullText) {
                running = false;
                try {
                    handleFixDoneResult(ResultParser.parseRaw(fullText));
                }catch (Throwable t){
                    cn.mhook.widget.GlassToast.error(AiActivity.this, "解析改包结果失败：" + t.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                running = false;
                cn.mhook.widget.GlassToast.error(AiActivity.this, "改包失败：" + t.getMessage());
            }
        });
    }

    private void handleFixDoneResult(JSONObject res){
        try {
            String action = res.getString("action");
            if (!"fixDone".equals(action)){
                cn.mhook.widget.GlassToast.error(AiActivity.this, "改包未完成：" + res.getString("reason"));
                return;
            }
            String outputName = res.getString("outputName");
            String detail = res.getString("detail");
            if (detail == null){
                detail = "";
            }
            String changesText = appendChanges(res);
            String built = findBuiltApk();
            if (built == null){
                showFixDialog("AI 改包完成",
                        "AI 已在 MT 端构建出签名 APK（" + (outputName == null ? "见 MT MCP 输出目录" : outputName)
                                + "），但本机未定位到该文件。\n\n若 MT 与 mHook 不在同一设备，请到 MT 管理器 MCP 目录取回该 APK 使用；修改摘要：" + detail);
                return;
            }
            String saved = saveBuiltApk(appPkg, built);
            if (saved == null){
                cn.mhook.widget.GlassToast.error(AiActivity.this, "定位到产物但拷贝失败：" + built);
                return;
            }
            RxClipboardTool.copyText(AiActivity.this, saved);
            showFixDialog("AI 改包完成",
                    "修改后的签名 APK 已保存：\n" + saved + "\n\n修改摘要：" + detail
                            + changesText
                            + "\n\n可用 MT管理器/玄星逆核 安装或继续二次修改。路径已复制到剪贴板。");
        } catch (Throwable t) {
            cn.mhook.widget.GlassToast.error(AiActivity.this, "处理改包结果失败：" + t.getMessage());
        }
    }

    private String appendChanges(JSONObject res){
        try {
            JSONArray changes = res.getJSONArray("changes");
            if (changes == null || changes.isEmpty()){
                return "";
            }
            StringBuilder sb = new StringBuilder("\n\n【修改清单】\n");
            for (int i = 0; i < changes.size(); i++){
                JSONObject c = changes.getJSONObject(i);
                sb.append(i + 1).append(". ");
                String file = strOr(c.getString("file"), "");
                String loc = strOr(c.getString("location"), "");
                if (!file.isEmpty()){
                    sb.append(file).append("  ");
                }
                if (!loc.isEmpty()){
                    sb.append(loc);
                }
                sb.append('\n');
                String before = strOr(c.getString("before"), "");
                String after = strOr(c.getString("after"), "");
                if (!before.isEmpty()){
                    sb.append("   改前: ").append(before).append('\n');
                }
                if (!after.isEmpty()){
                    sb.append("   改后: ").append(after).append('\n');
                }
                String desc = strOr(c.getString("desc"), "");
                if (!desc.isEmpty()){
                    sb.append("   说明: ").append(desc).append('\n');
                }
            }
            aiOutput.append(sb.toString());
            scrollBottom();
            return sb.toString();
        }catch (Throwable ignored){
            return "";
        }
    }

    private void renderPatchPlan(JSONObject res){
        String detail = strOr(res.getString("detail"), "该修改无法用 Hook 配置实现，需要改包");
        aiOutput.append("\n\n— " + detail + " —\n");
        appendChanges(res);
        scrollBottom();
        cn.mhook.widget.GlassToast.warning(AiActivity.this, "该需求无法用 Hook 配置实现，已给出改包方案");
    }

    private static String strOr(String s, String def){
        return s == null || s.trim().isEmpty() ? def : s;
    }

    private void showFixDialog(String title, String message){
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create().show();
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
        int dp = (int) (getResources().getDisplayMetrics().density);

        final EditText baseUrl = addSettingField(container, "接口地址 base_url", "如 https://api.openai.com/v1",
                AiSetting.baseUrl(this), InputType.TYPE_CLASS_TEXT);
        final EditText apiKey = addSettingField(container, "API Key", "用于调用接口的密钥",
                AiSetting.apiKey(this), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText model = addSettingField(container, "模型名", "如 gpt-4o-mini / deepseek-chat",
                AiSetting.model(this), InputType.TYPE_CLASS_TEXT);
        final EditText maxTokens = addSettingField(container, "最大输出 Tokens", null,
                String.valueOf(AiSetting.maxTokens(this)), InputType.TYPE_CLASS_NUMBER);
        final EditText maxSteps = addSettingField(container, "最大工具调用轮数", "AI 可连续调用工具的轮次上限（默认 32）",
                String.valueOf(AiSetting.maxSteps(this)), InputType.TYPE_CLASS_NUMBER);

        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(dp * 20, dp * 8, dp * 20, 0);
        wrap.addView(container);

        new AlertDialog.Builder(this)
                .setTitle("AI 设置")
                .setView(wrap)
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
                        cn.mhook.widget.GlassToast.success(AiActivity.this, "已保存");
                    }
                })
                .create().show();
    }

    private EditText addSettingField(LinearLayout container, String label, String helper, String def, int inputType){
        int dp = (int) (getResources().getDisplayMetrics().density);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(getResources().getColor(R.color.glass_text_secondary));
        tv.setTextSize(13);
        tv.setPadding(0, dp * 8, 0, dp * 4);
        container.addView(tv);

        EditText et = new EditText(this);
        et.setHint(helper == null ? label : helper);
        et.setHintTextColor(getResources().getColor(R.color.glass_text_tertiary));
        et.setTextColor(getResources().getColor(R.color.glass_text_primary));
        et.setText(def == null ? "" : def);
        et.setBackgroundResource(R.drawable.bg_input_glass);
        et.setPadding(dp * 14, dp * 10, dp * 14, dp * 10);
        et.setInputType(inputType);
        container.addView(et);

        if (helper != null) {
            TextView h = new TextView(this);
            h.setText(helper);
            h.setTextColor(getResources().getColor(R.color.glass_text_tertiary));
            h.setTextSize(11);
            h.setPadding(0, dp * 4, 0, dp * 2);
            container.addView(h);
        }
        return et;
    }

    private void saveSettings(EditText baseUrl, EditText apiKey, EditText model,
                              EditText maxTokens, EditText maxSteps){
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
            cn.mhook.widget.GlassToast.warning(AiActivity.this, "请先填写接口地址 / API Key / 模型");
            return;
        }
        cn.mhook.widget.GlassToast.info(AiActivity.this, "正在测试连接…");
        AiClient.stream(AiActivity.this,
                "你是连通性测试助手，只回复 OK 即可，不要输出其他内容。",
                "测试连接，请回复 OK。",
                new AiClient.Listener() {
                    @Override
                    public void onDelta(String text) {
                    }

                    @Override
                    public void onReasoning(String text) {
                    }

                    @Override
                    public void onToolCalls(JSONArray toolCalls) {
                    }

                    @Override
                    public void onDone(String fullText) {
                        String r = fullText == null ? "" : fullText.trim();
                        android.util.Log.i("XpAiTest", "conn ok: " + r);
                        cn.mhook.widget.GlassToast.success(AiActivity.this, "连接成功：" + (r.isEmpty() ? "已响应" : r));
                    }

                    @Override
                    public void onError(Throwable t) {
                        android.util.Log.w("XpAiTest", "conn fail: " + t.getMessage(), t);
                        cn.mhook.widget.GlassToast.error(AiActivity.this, "连接失败：" + t.getMessage());
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
