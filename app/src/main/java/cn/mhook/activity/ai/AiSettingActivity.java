package cn.mhook.activity.ai;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.alibaba.fastjson.JSONArray;

import cn.mhook.ai.AiClient;
import cn.mhook.ai.AiSetting;
import cn.mhook.mhook.R;
import cn.mhook.widget.GlassToast;

public class AiSettingActivity extends Activity {

    private EditText etBaseUrl, etApiKey, etModel, etMaxTokens, etMaxSteps;
    private TextView testResult;
    private boolean keyVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_setting);

        etBaseUrl = findViewById(R.id.et_base_url);
        etApiKey = findViewById(R.id.et_api_key);
        etModel = findViewById(R.id.et_model);
        etMaxTokens = findViewById(R.id.et_max_tokens);
        etMaxSteps = findViewById(R.id.et_max_steps);
        testResult = findViewById(R.id.test_result);

        etBaseUrl.setText(AiSetting.baseUrl(this));
        etApiKey.setText(AiSetting.apiKey(this));
        etModel.setText(AiSetting.model(this));
        etMaxTokens.setText(String.valueOf(AiSetting.maxTokens(this)));
        etMaxSteps.setText(String.valueOf(AiSetting.maxSteps(this)));

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_eye).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keyVisible = !keyVisible;
                etApiKey.setInputType(keyVisible
                        ? InputType.TYPE_CLASS_TEXT
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                etApiKey.setSelection(etApiKey.getText().length());
            }
        });
        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
                testConnection();
            }
        });
        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSettings();
                GlassToast.success(AiSettingActivity.this, "已保存");
                finish();
            }
        });
    }

    private void saveSettings() {
        AiSetting.setBaseUrl(this, etBaseUrl.getText().toString());
        AiSetting.setApiKey(this, etApiKey.getText().toString());
        AiSetting.setModel(this, etModel.getText().toString());
        try {
            AiSetting.setMaxTokens(this, Integer.parseInt(etMaxTokens.getText().toString().trim()));
        } catch (Throwable ignored) {
        }
        try {
            AiSetting.setMaxSteps(this, Integer.parseInt(etMaxSteps.getText().toString().trim()));
        } catch (Throwable ignored) {
        }
    }

    private void testConnection() {
        if (AiSetting.baseUrl(this).isEmpty() || AiSetting.apiKey(this).isEmpty() || AiSetting.model(this).isEmpty()) {
            showResult("请先填写 API 地址 / Key / 模型", false);
            return;
        }
        showResult("正在测试连接...", true);
        AiClient.stream(this,
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
                        showResult("连接成功：" + (r.isEmpty() ? "已响应" : r), true);
                    }

                    @Override
                    public void onError(Throwable t) {
                        showResult("连接失败：" + t.getMessage(), false);
                    }
                });
    }

    private void showResult(String msg, boolean success) {
        testResult.setVisibility(View.VISIBLE);
        testResult.setText(msg);
        testResult.setTextColor(getResources().getColor(
                success ? R.color.glass_accent_green : R.color.glass_accent_red));
    }
}
