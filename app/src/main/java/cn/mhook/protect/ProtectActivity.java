package cn.mhook.protect;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import cn.mhook.mhook.R;
import cn.mhook.npatch.IProgress;

/**
 * 应用保护（多档位加固）· P1 骨架：选 APK → 档位/选项/签名配置 → 开始加固。
 * 真实可用：重打包 + resources.arsc 对齐 + V2/V3 签名（内置 / 自有证书）。
 */
public class ProtectActivity extends Activity {

    private static final int REQ_APK = 0x7001;
    private static final int REQ_KS = 0x7002;
    private static final int REQ_PK8 = 0x7003;
    private static final int REQ_CERT = 0x7004;

    private final Handler UI = new Handler(Looper.getMainLooper());
    private final ProtectConfig cfg = ProtectConfig.createDefault();
    private final LinkedHashMap<String, View> switchViews = new LinkedHashMap<>();
    private final LinkedHashMap<String, LinkedHashMap<String, View>> choiceViews = new LinkedHashMap<>();
    private final LinkedHashMap<String, EditText> editViews = new LinkedHashMap<>();

    private LinearLayout content, tierRow, sigRow;
    private TextView fileInfo, tierNote, log;
    private File apk;
    private boolean running;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_protect);
        content = findViewById(R.id.content);
        ((View) findViewById(R.id.btn_back)).setOnClickListener(v -> finish());
        ensureStoragePermission();
        build();
        renderTier();
    }

    /** 申请存储权限：API30+ 需“所有文件访问”，低版本申请 READ/WRITE_EXTERNAL_STORAGE。 */
    private void ensureStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    try {
                        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Throwable t) {
                        try {
                            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                java.util.List<String> need = new java.util.ArrayList<>();
                if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    need.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
                }
                if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    need.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
                if (!need.isEmpty()) {
                    requestPermissions(need.toArray(new String[0]), 9002);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- UI 构建 ----------------
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView glassBtn(String text, boolean accent) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(getColor(accent ? R.color.glass_accent_orange : R.color.glass_text_primary));
        t.setBackgroundResource(R.drawable.bg_glass_btn);
        t.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(12);
        t.setLayoutParams(lp);
        t.setClickable(true);
        return t;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(getColor(R.color.glass_text_tertiary));
        t.setPadding(dp(4), dp(20), dp(4), dp(2));
        return t;
    }

    private void addLabel(String s) {
        content.addView(label(s));
    }

    private TextView card(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(getColor(R.color.glass_text_secondary));
        t.setBackgroundResource(R.drawable.bg_glass_card);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        content.addView(t);
        return t;
    }

    /** 开关行：标题 + 真机开关（bg_toggle_track + bg_toggle_knob）。 */
    private View switchRow(final String key, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setBackgroundResource(R.drawable.bg_glass_btn);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(12);
        row.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(getColor(R.color.glass_text_primary));
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(t);

        FrameLayout sw = new FrameLayout(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(44), dp(26));
        sw.setLayoutParams(slp);
        sw.setBackgroundResource(R.drawable.bg_toggle_track);
        View knob = new View(this);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(dp(22), dp(22));
        klp.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
        klp.leftMargin = dp(2);
        knob.setLayoutParams(klp);
        knob.setBackgroundResource(R.drawable.bg_toggle_knob);
        sw.addView(knob);
        row.addView(sw);
        row.setTag(new Object[]{sw, knob});
        switchViews.put(key, row);
        updateSwitch(row, cfg.on(key));

        row.setOnClickListener(v -> {
            int min = minTier(key);
            if (cfg.tier < min) return;
            cfg.opts.put(key, !cfg.on(key));
            updateSwitch(row, cfg.on(key));
        });
        return row;
    }

    private void refreshSwitches() {
        for (Map.Entry<String, View> e : switchViews.entrySet()) {
            updateSwitch(e.getValue(), cfg.on(e.getKey()));
        }
    }

    private void updateSwitch(View row, boolean on) {
        Object[] tag = (Object[]) row.getTag();
        FrameLayout sw = (FrameLayout) tag[0];
        View knob = (View) tag[1];
        sw.getBackground().setTint(on ? getColor(R.color.glass_accent_green) : 0x80B4AA9B);
        knob.animate().translationX(on ? dp(18) : 0).setDuration(150).start();
    }

    /** 单选项：标签 + 一行 chips。 */
    private void choiceRow(final ProtectConfig.Choice c) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(0, dp(12), 0, 0);

        TextView l = new TextView(this);
        l.setText(c.title);
        l.setTextSize(12);
        l.setTextColor(getColor(R.color.glass_text_secondary));
        l.setLayoutParams(new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(l);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinkedHashMap<String, View> map = new LinkedHashMap<>();
        for (final String v : c.values) {
            TextView chip = glassChip(v);
            bar.addView(chip);
            map.put(v, chip);
            chip.setOnClickListener(x -> {
                cfg.choices.put(c.key, v);
                selectChip(map, v);
            });
        }
        choiceViews.put(c.key, map);
        wrap.addView(bar);
        content.addView(wrap);
    }

    private TextView glassChip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.bg_glass_btn);
        t.setPadding(dp(10), dp(12), dp(10), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void selectChip(LinkedHashMap<String, View> map, String val) {
        for (Map.Entry<String, View> e : map.entrySet()) {
            TextView t = (TextView) e.getValue();
            boolean on = e.getKey().equals(val);
            t.setTextColor(getColor(on ? R.color.glass_accent_orange : R.color.glass_text_primary));
            t.getBackground().setTint(on ? 0x14EA580C : Color.TRANSPARENT);
        }
    }

    private void textRow(final ProtectConfig.Text t) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(0, dp(12), 0, 0);

        TextView l = new TextView(this);
        l.setText(t.title);
        l.setTextSize(12);
        l.setTextColor(getColor(R.color.glass_text_secondary));
        l.setLayoutParams(new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(l);

        EditText et = new EditText(this);
        et.setHint(t.hint);
        et.setTextSize(13);
        et.setSingleLine(true);
        et.setTextColor(getColor(R.color.glass_text_primary));
        et.setHintTextColor(getColor(R.color.glass_text_tertiary));
        et.setBackgroundResource(R.drawable.bg_input_glass);
        et.setPadding(dp(14), 0, dp(14), 0);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1));
        editViews.put(t.key, et);
        wrap.addView(et);
        content.addView(wrap);
    }

    private void build() {
        fileInfo = card("未选择 APK · 点击下方选择");
        TextView choose = glassBtn("选择 APK", false);
        choose.setOnClickListener(v -> pickFile("application/vnd.android.package-archive", REQ_APK));
        content.addView(choose);

        addLabel("加固档位");
        tierRow = new LinearLayout(this);
        tierRow.setOrientation(LinearLayout.HORIZONTAL);
        int[] tiers = {ProtectConfig.TIER_BASIC, ProtectConfig.TIER_ADV, ProtectConfig.TIER_STRONG};
        String[] names = {"基础", "进阶", "高强度"};
        for (int i = 0; i < 3; i++) {
            TextView btn = glassBtn(names[i], false);
            btn.setTag(tiers[i]);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) btn.getLayoutParams();
            lp.width = 0;
            lp.weight = 1;
            if (i > 0) lp.leftMargin = dp(10);
        btn.setOnClickListener(v -> {
            cfg.applyPreset(((Integer) v.getTag()));
            renderTier();
            refreshSwitches();
        });
            tierRow.addView(btn);
        }
        content.addView(tierRow);
        tierNote = new TextView(this);
        tierNote.setTextSize(12);
        tierNote.setTextColor(getColor(R.color.glass_text_secondary));
        tierNote.setBackgroundResource(R.drawable.bg_glass_card);
        tierNote.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(12);
        tierNote.setLayoutParams(nlp);
        content.addView(tierNote);

        // 选项（按分组）
        String curGroup = null;
        for (ProtectConfig.Opt o : ProtectConfig.OPTS) {
            if (!o.group.equals(curGroup)) {
                curGroup = o.group;
                addLabel(curGroup);
            }
            content.addView(switchRow(o.key, o.title));
        }
        // 高级参数
        for (ProtectConfig.Choice c : ProtectConfig.CHOICES) choiceRow(c);
        for (ProtectConfig.Text t : ProtectConfig.TEXTS) textRow(t);

        // 签名
        addLabel("签名方式");
        sigRow = new LinearLayout(this);
        sigRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] sigNames = {"内置证书", "自有 .jks", "key/cert"};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            TextView btn = glassBtn(sigNames[i], false);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) btn.getLayoutParams();
            lp.width = 0;
            lp.weight = 1;
            if (i > 0) lp.leftMargin = dp(10);
            btn.setTag(mode);
            btn.setOnClickListener(v -> {
                cfg.sign.mode = mode;
                renderSig();
            });
            sigRow.addView(btn);
        }
        content.addView(sigRow);

        // 签名材料输入
        ksPath = textRowInline("密钥文件", REQ_KS, "*.jks / *.keystore");
        alias = textRowInline("别名", -1, "mykey");
        storePass = textRowInline("库口令", -1, "store password");
        keyPass = textRowInline("密钥口令", -1, "留空=同上");
        pk8Path = textRowInline("key.pk8", REQ_PK8, "未选择");
        certPath = textRowInline("cert", REQ_CERT, "未选择 .pem/.der");
        sigFields = new View[]{ksPath, alias, storePass, keyPass, pk8Path, certPath};

        TextView start = glassBtn("开始加固", true);
        start.setOnClickListener(v -> start());
        content.addView(start);

        log = new TextView(this);
        log.setTextSize(12);
        log.setTextColor(getColor(R.color.glass_text_primary));
        log.setBackgroundResource(R.drawable.bg_glass_card);
        log.setPadding(dp(12), dp(12), dp(12), dp(12));
        log.setText("就绪\n");
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(12);
        log.setLayoutParams(llp);
        content.addView(log);
    }

    private EditText ksPath, alias, storePass, keyPass, pk8Path, certPath;
    private View[] sigFields;

    private EditText textRowInline(String title, int req, String hint) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(0, dp(12), 0, 0);
        TextView l = new TextView(this);
        l.setText(title);
        l.setTextSize(12);
        l.setTextColor(getColor(R.color.glass_text_secondary));
        l.setLayoutParams(new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(l);
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(13);
        et.setSingleLine(true);
        et.setHintTextColor(getColor(R.color.glass_text_tertiary));
        et.setTextColor(getColor(R.color.glass_text_primary));
        et.setBackgroundResource(R.drawable.bg_input_glass);
        et.setPadding(dp(14), 0, dp(14), 0);
        et.setLayoutParams(new LinearLayout.LayoutParams(0, dp(46), 1));
        wrap.addView(et);
        if (req >= 0) {
            et.setFocusable(false);
            et.setClickable(true);
            et.setOnClickListener(v -> pickFile("*/*", req));
        }
        content.addView(wrap);
        return et;
    }

    private void renderTier() {
        // 档位按钮高亮
        if (tierRow != null) {
            for (int i = 0; i < tierRow.getChildCount(); i++) {
                TextView btn = (TextView) tierRow.getChildAt(i);
                boolean on = ((Integer) btn.getTag()) == cfg.tier;
                btn.setTextColor(getColor(on ? R.color.glass_accent_orange : R.color.glass_text_primary));
                btn.getBackground().setTint(on ? 0x14EA580C : Color.TRANSPARENT);
            }
        }
        tierNote.setText(note(cfg.tier));
        // 选项开关状态 + 锁定
        for (Map.Entry<String, View> e : switchViews.entrySet()) {
            View row = e.getValue();
            boolean locked = cfg.tier < minTier(e.getKey());
            row.setAlpha(locked ? 0.4f : 1f);
            updateSwitch(row, cfg.on(e.getKey()));
        }
        for (ProtectConfig.Choice c : ProtectConfig.CHOICES)
            selectChip(choiceViews.get(c.key), cfg.choices.get(c.key));
        renderSig();
    }

    private void renderSig() {
        if (sigRow != null) {
            for (int i = 0; i < sigRow.getChildCount(); i++) {
                TextView btn = (TextView) sigRow.getChildAt(i);
                boolean on = ((Integer) btn.getTag()) == cfg.sign.mode;
                btn.setTextColor(getColor(on ? R.color.glass_accent_orange : R.color.glass_text_primary));
                btn.getBackground().setTint(on ? 0x14EA580C : Color.TRANSPARENT);
            }
        }
        boolean ks = cfg.sign.mode == SignConfig.MODE_KEYSTORE;
        boolean kc = cfg.sign.mode == SignConfig.MODE_KEYCERT;
        setVisible(ksPath, ks);
        setVisible(alias, ks);
        setVisible(storePass, ks);
        setVisible(keyPass, ks);
        setVisible(pk8Path, kc);
        setVisible(certPath, kc);
    }

    private void setVisible(View field, boolean on) {
        // EditText 的父容器（wrapper LinearLayout）整体显隐
        View p = (View) field.getParent();
        p.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private int minTier(String key) {
        for (ProtectConfig.Opt o : ProtectConfig.OPTS) if (o.key.equals(key)) return o.minTier;
        return 0;
    }

    private String note(int tier) {
        switch (tier) {
            case ProtectConfig.TIER_ADV:
                return "进阶档：叠加 名称混淆 + 移除调试代码 + DPT 抽取 + AES 加密 + SO 加密/混淆/防篡改 + 反调试 + RASP + SSL Pinning + 签名校验。";
            case ProtectConfig.TIER_STRONG:
                return "高强度档：叠加 VMP + Dex2C + SO 虚拟化 + 反Hook + 内存/完整性自校验 + 数据/白盒加密 + 防截屏 + 多开/加速器/无障碍检测。";
            default:
                return "基础档：字符串 + 控制流 + 资源混淆。纯 Java，兼容性最好。";
        }
    }

    // ---------------- 文件选择 ----------------
    private void pickFile(String type, int req) {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(type);
            startActivityForResult(i, req);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType(type);
                startActivityForResult(i, req);
            } catch (Throwable t2) {
                appendLog("无法打开文件选择器");
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (req == REQ_APK) {
                apk = copyToCache(uri, "protect_src.apk");
                long mb = apk.length() / 1024 / 1024;
                fileInfo.setText(apk.getName() + " · " + mb + " MB");
            } else if (req == REQ_KS) {
                cfg.sign.keystorePath = copyToCache(uri, "sign.jks").getAbsolutePath();
                ksPath.setText(new File(cfg.sign.keystorePath).getName());
            } else if (req == REQ_PK8) {
                cfg.sign.keyPk8Path = copyToCache(uri, "sign.pk8").getAbsolutePath();
                pk8Path.setText(new File(cfg.sign.keyPk8Path).getName());
            } else if (req == REQ_CERT) {
                cfg.sign.certPath = copyToCache(uri, "sign.cert").getAbsolutePath();
                certPath.setText(new File(cfg.sign.certPath).getName());
            }
        } catch (Throwable t) {
            appendLog("读取文件失败: " + t);
        }
    }

    private File copyToCache(Uri uri, String name) throws Exception {
        File f = new File(getCacheDir(), name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
        return f;
    }

    // ---------------- 执行 ----------------
    private void syncSignTexts() {
        cfg.sign.alias = alias.getText().toString().trim();
        cfg.sign.storePass = storePass.getText().toString();
        cfg.sign.keyPass = keyPass.getText().toString();
        for (Map.Entry<String, EditText> e : editViews.entrySet()) {
            cfg.texts.put(e.getKey(), e.getValue().getText().toString().trim());
        }
    }

    private void start() {
        if (running) return;
        ensureStoragePermission();
        if (apk == null) {
            appendLog("请先选择 APK");
            return;
        }
        syncSignTexts();
        String err = cfg.sign.validate();
        if (err != null) {
            appendLog(err);
            return;
        }
        running = true;
        appendLog("===== 开始加固（" + tierName() + "）=====");
        new Thread(() -> {
            try {
                File out = ProtectEngine.process(ProtectActivity.this, apk, cfg, new IProgress() {
                    @Override
                    public void log(String s) {
                        appendLog(s);
                    }
                });
                UI.post(() -> appendLog("✅ 输出: " + out.getAbsolutePath()));
            } catch (Throwable t) {
                UI.post(() -> appendLog("❌ 失败: " + t));
            } finally {
                running = false;
            }
        }, "protect").start();
    }

    private String tierName() {
        return cfg.tier == ProtectConfig.TIER_ADV ? "进阶"
                : cfg.tier == ProtectConfig.TIER_STRONG ? "高强度" : "基础";
    }

    private void appendLog(String s) {
        UI.post(() -> {
            log.append(s + "\n");
            ScrollView sv = findViewById(R.id.scroll);
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        });
    }
}
