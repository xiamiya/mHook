package cn.mhook.activity;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import cn.mhook.mhook.R;
import cn.mhook.npatch.NpatchEngine;
import cn.mhook.widget.GlassToast;

/**
 * 免root 重打包脱壳：选 APK → 用内置 NPatch 嵌入脱壳模块（DumpModule）并重签名（过签Lv3）→ 导出。
 */
public class RootlessDumpActivity extends Activity {

    private static final int REQ_APK = 9100;

    private TextView fileInfo, outLog, btnStart;
    private Uri apkUri;
    private String apkName = "";
    private boolean busy;
    private final StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rootless_dump);
        fileInfo = findViewById(R.id.file_info);
        outLog = findViewById(R.id.out_log);
        btnStart = findViewById(R.id.btn_start);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_choose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) return;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/vnd.android.package-archive");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.android.package-archive", "application/octet-stream"});
                try {
                    startActivityForResult(intent, REQ_APK);
                } catch (Throwable t) {
                    GlassToast.warning(RootlessDumpActivity.this, "无法打开文件选择器");
                }
            }
        });
        findViewById(R.id.out_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logBuf.setLength(0);
                outLog.setText("");
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPatch();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_APK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            apkUri = data.getData();
            apkName = queryName();
            fileInfo.setText("已选择：" + apkName);
            log("已选择：" + apkName);
        }
    }

    private String queryName() {
        try {
            String n = null;
            android.database.Cursor c = getContentResolver().query(apkUri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) n = c.getString(idx);
                } finally {
                    c.close();
                }
            }
            if (TextUtils.isEmpty(n)) n = apkUri.getLastPathSegment();
            return n;
        } catch (Throwable t) {
            return "APK";
        }
    }

    private void startPatch() {
        if (busy) return;
        if (apkUri == null) {
            GlassToast.warning(this, "请先选择 APK");
            return;
        }
        busy = true;
        btnStart.setEnabled(false);
        log("\n==== 开始重打包脱壳 ====");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File tmp = new File(getCacheDir(), "target_" + System.currentTimeMillis() + ".apk");
                    try (java.io.InputStream is = getContentResolver().openInputStream(apkUri);
                         FileOutputStream fos = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                    log("已复制 APK 到工作目录：" + tmp.length() + " B");

                    File module = new File(getFilesDir(), "npatch/DumpModule.apk");
                    if (!module.exists()) {
                        File parent = module.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        android.content.res.AssetManager am = getAssets();
                        try (java.io.InputStream is = am.open("npatch/DumpModule.apk");
                             FileOutputStream fos = new FileOutputStream(module)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                        }
                    }
                    log("脱壳模块就绪");

                    File outDir = new File(getCacheDir(), "npatch_out");
                    final java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
                    final java.io.PrintStream oldErr = System.err;
                    final java.io.PrintStream oldOut = System.out;
                    File result;
                    try {
                        System.setErr(new java.io.PrintStream(errBuf, true, "UTF-8"));
                        System.setOut(new java.io.PrintStream(errBuf, true, "UTF-8"));
                        result = NpatchEngine.patch(RootlessDumpActivity.this, tmp, module, outDir);
                    } finally {
                        System.setErr(oldErr);
                        System.setOut(oldOut);
                    }
                    if (errBuf.size() > 0) {
                        log(new String(errBuf.toByteArray(), "UTF-8"));
                    }
                    log("patch 完成：" + result.getName());

                    final String exported = exportToDownload(result);
                    log(exported != null ? ("已导出：" + exported) : "导出失败");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (exported != null) {
                                GlassToast.success(RootlessDumpActivity.this, "已导出：" + exported);
                            } else {
                                GlassToast.warning(RootlessDumpActivity.this, "导出失败，请查看日志");
                            }
                        }
                    });
                } catch (final Throwable t) {
                    log("失败：" + t.getMessage());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            GlassToast.error(RootlessDumpActivity.this, "重打包失败：" + t.getMessage());
                        }
                    });
                } finally {
                    busy = false;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnStart.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    private String exportToDownload(File apk) {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String name = "rootless_dump_" + stamp + ".apk";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/mhook_dump");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (FileOutputStream fos = (FileOutputStream) getContentResolver().openOutputStream(uri);
                         FileInputStream fis = new FileInputStream(apk)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                    return "Download/mhook_dump/" + name;
                }
            } else {
                File out = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "mhook_dump/" + name);
                out.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(out);
                     FileInputStream fis = new FileInputStream(apk)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
                }
                return out.getAbsolutePath();
            }
        } catch (Throwable t) {
            log("导出异常：" + t.getMessage());
        }
        return null;
    }

    private void log(final String line) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                if (logBuf.length() > 0) logBuf.append("\n");
                logBuf.append("[").append(ts).append("] ").append(line);
                outLog.setText(logBuf.toString());
                final android.widget.ScrollView scroll = findViewById(R.id.out_scroll);
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }
}
