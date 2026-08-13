package cn.mhook.activity;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.TextView;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import cn.mhook.BaseActivity;
import cn.mhook.mhook.R;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * 免root脱壳：选择 APK → 自动装入沙箱运行 → 虚拟进程内自动 dump dex → 自动返回界面并导出 zip 到 Download。
 */
public class SandboxDumpActivity extends BaseActivity {

    private static final int REQ_APK = 1001;

    private static volatile String sDoneStatus;
    private static volatile String sDoneLog;

    private TextView btnChoose;
    private TextView statusView;
    private TextView logView;
    private TextView cleanBtn;
    private StringBuilder logBuffer = new StringBuilder();
    private Handler handler = new Handler(Looper.getMainLooper());

    private volatile String currentPkg;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sandbox_dump);
        btnChoose = findViewById(R.id.sb_btn_choose);
        cleanBtn = findViewById(R.id.sb_btn_clean);
        statusView = findViewById(R.id.sb_status);
        logView = findViewById(R.id.sb_log);
        // 自动返回重新创建的实例：直接展示上次脱壳结果
        if (sDoneStatus != null) {
            setStatus(sDoneStatus);
            logView.setText(sDoneLog != null ? sDoneLog : "");
        }
        btnChoose.setOnClickListener(new View.OnClickListener() {
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
                    setStatus("无法打开文件选择器");
                    logLine("错误：无法打开文件选择器");
                }
            }
        });
        cleanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) return;
                startClean();
            }
        });
    }

    /** 一键清理沙箱：卸载沙箱内所有已装应用，释放存储空间。 */
    private void startClean() {
        busy = true;
        btnChoose.setEnabled(false);
        cleanBtn.setEnabled(false);
        setStatus("正在清理沙箱...");
        logLine("开始清理沙箱内已装应用");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                try {
                    java.util.List<android.content.pm.ApplicationInfo> apps =
                            top.niunaijun.blackbox.BlackBoxCore.get().getInstalledApplications(0, 0);
                    if (apps != null) {
                        for (android.content.pm.ApplicationInfo ai : apps) {
                            try {
                                top.niunaijun.blackbox.BlackBoxCore.get().uninstallPackageAsUser(ai.packageName, 0);
                                n++;
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                final int count = n;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("清理完成，共卸载 " + count + " 个沙箱应用");
                        logLine("清理完成，共卸载 " + count + " 个沙箱应用");
                        busy = false;
                        btnChoose.setEnabled(true);
                        cleanBtn.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_APK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            startDump(data.getData());
        }
    }

    private void startDump(final Uri uri) {
        busy = true;
        btnChoose.setEnabled(false);
        setStatus("步骤 1/4：正在准备 APK...");
        logLine("开始：正在准备 APK");
        logView.setText("");
        logBuffer.setLength(0);
        new Thread(new Runnable() {
            @Override
            public void run() {
                File apkFile = null;
                try {
                    // 复制到本地临时文件，便于识别加固特征 + 安装
                    apkFile = new File(getCacheDir(), "selected_" + System.currentTimeMillis() + ".apk");
                    java.io.InputStream is = getContentResolver().openInputStream(uri);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(apkFile);
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    } finally {
                        try { is.close(); } catch (Throwable ignored) { }
                        try { fos.close(); } catch (Throwable ignored) { }
                    }
                    final String packer = cn.mhook.mhook.xposed.dump.PackerDetector.detect(SandboxDumpActivity.this, apkFile.getAbsolutePath());
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (packer != null) {
                                setStatus("检测到该应用为「" + packer + "」加固");
                                logLine("检测到加固特征：该应用为「" + packer + "」");
                            } else {
                                logLine("未检测到加固特征（普通应用）");
                            }
                            setStatus("步骤 1/4：正在安装 APK 到沙箱...");
                            logLine("开始：正在安装 APK 到沙箱");
                        }
                    });
                    final InstallResult result = BlackBoxCore.get().installPackageAsUser(apkFile, 0);
                    if (result == null || !result.success || result.packageName == null) {
                        final String msg = result != null ? (result.msg != null ? result.msg : "安装失败") : "安装失败";
                        if (result != null && result.packageName != null) {
                            uninstallSandboxApp(result.packageName);
                        }
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                setStatus("安装失败：" + msg);
                                logLine("错误：安装失败 - " + msg);
                                reset();
                            }
                        });
                        return;
                    }
                    currentPkg = result.packageName;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("步骤 2/4：安装成功，正在静默启动应用...");
                            logLine("安装成功：" + currentPkg);
                            logLine("正在静默启动应用（后台运行，不显示界面）...");
                        }
                    });
                    // 只拉起虚拟进程（Application 会加载 dex），不显示 Activity，静默脱壳
                    AppConfig cfg = BlackBoxCore.getBActivityManager().initProcess(currentPkg, currentPkg, 0);
                    if (cfg == null) {
                        uninstallSandboxApp(currentPkg);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                setStatus("进程启动失败（可能是加固应用无法在沙箱运行）");
                                logLine("错误：应用进程启动失败");
                                logLine("已卸载沙箱内应用，释放空间");
                                reset();
                            }
                        });
                        return;
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("步骤 3/4：应用已启动，正在加载 dex...");
                            logLine("应用进程已启动，等待 dex 加载并开始脱壳...");
                        }
                    });
                    pollDump(currentPkg);
                } catch (final Throwable t) {
                    if (currentPkg != null) {
                        uninstallSandboxApp(currentPkg);
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("操作失败：" + t);
                            logLine("错误：" + t);
                            reset();
                        }
                    });
                } finally {
                    if (apkFile != null) {
                        try {
                            apkFile.delete();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }).start();
    }

    private void pollDump(final String pkg) {
        final File outDir = new File(getFilesDir(), "sandbox_dump" + File.separator + pkg);
        final long start = System.currentTimeMillis();
        final long timeout = 60 * 1000L;
        new Thread(new Runnable() {
            @Override
            public void run() {
                int lastCount = -1;
                long stableSince = start;
                long lastHint = start;
                while (true) {
                    try {
                        int count = countDex(outDir);
                        if (count != lastCount) {
                            lastCount = count;
                            stableSince = System.currentTimeMillis();
                            final int c = count;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    setStatus("步骤 4/4：正在脱壳... 已发现 " + c + " 个 dex，持续扫描中");
                                    logLine("脱壳进度：已发现 " + c + " 个 dex（等待稳定后自动完成）");
                                }
                            });
                        } else {
                            long now = System.currentTimeMillis();
                            // 每 10 秒提示一次当前状态，让用户知道进行到哪一步
                            if (now - lastHint >= 10 * 1000L) {
                                lastHint = now;
                                final int cur = lastCount;
                                final int remain = (int) ((timeout - (now - start)) / 1000);
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (cur == 0) {
                                            setStatus("步骤 4/4：正在脱壳... 还剩 " + remain + "s，尚未发现 dex（应用可能仍在加载或已崩溃）");
                                            logLine("还剩 " + remain + "s，仍未发现 dex（应用可能仍在加载或已崩溃）");
                                        } else {
                                            setStatus("步骤 4/4：正在脱壳... 还剩 " + remain + "s，已发现 " + cur + " 个 dex（等待稳定）");
                                            logLine("还剩 " + remain + "s，已发现 " + cur + " 个 dex（等待稳定后自动完成）");
                                        }
                                    }
                                });
                            }
                        }
                        long now = System.currentTimeMillis();
                        if (lastCount > 0 && now - stableSince > 12 * 1000L) {
                            final int c = lastCount;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    setStatus("正在打包导出 zip...");
                                    logLine("脱壳完成：共 " + c + " 个 dex");
                                    logLine("正在打包导出到 Download...");
                                }
                            });
                            final String zipInfo = exportZip(pkg, outDir);
                            if (zipInfo != null) {
                                deleteDumpDir(outDir);
                            }
                            try {
                                BlackBoxCore.get().stopPackage(pkg, 0);
                            } catch (Throwable ignored) {
                            }
                            // 脱壳结束（无论成败）卸载沙箱内应用，释放占用的存储空间
                            uninstallSandboxApp(pkg);
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    logLine(zipInfo != null ? "导出完成：" + zipInfo : "导出失败");
                                    if (zipInfo != null) logLine("已清理应用目录中的临时脱壳文件");
                                    logLine("已卸载沙箱内应用，释放空间");
                                    sDoneStatus = "脱壳完成，共 " + c + " 个 dex"
                                            + (zipInfo != null ? "\n导出完成：" + zipInfo : "");
                                    sDoneLog = logBuffer.toString();
                                    setStatus(sDoneStatus);
                                    logView.setText(sDoneLog);
                                    bringToFront();
                                    reset();
                                }
                            });
                            return;
                        }
                        if (now - start > timeout) {
                            final int c = lastCount;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    setStatus("脱壳超时，正在处理结果...");
                                    logLine("脱壳超时（60秒），已发现 " + c + " 个 dex");
                                }
                            });
                            final String zipInfo = c > 0 ? exportZip(pkg, outDir) : null;
                            if (zipInfo != null) {
                                deleteDumpDir(outDir);
                            }
                            try {
                                BlackBoxCore.get().stopPackage(pkg, 0);
                            } catch (Throwable ignored) {
                            }
                            // 脱壳结束（无论成败）卸载沙箱内应用，释放占用的存储空间
                            uninstallSandboxApp(pkg);
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    logLine(zipInfo != null ? "导出完成：" + zipInfo : "导出失败");
                                    if (zipInfo != null) logLine("已清理应用目录中的临时脱壳文件");
                                    logLine("已卸载沙箱内应用，释放空间");
                                    sDoneStatus = "超时结束，共发现 " + c + " 个 dex（加固应用可能无法在沙箱运行）"
                                            + (zipInfo != null ? "\n导出完成：" + zipInfo : "");
                                    sDoneLog = logBuffer.toString();
                                    setStatus(sDoneStatus);
                                    logView.setText(sDoneLog);
                                    bringToFront();
                                    reset();
                                }
                            });
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }).start();
    }

    private int countDex(File dir) {
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return 0;
        int n = 0;
        for (File f : files) {
            if (f.getName().endsWith(".dex")) n++;
        }
        return n;
    }

    /** 删除应用目录中的临时脱壳文件。 */
    /** 卸载沙箱内应用，释放其占用的存储空间。 */
    private void uninstallSandboxApp(String pkg) {
        if (pkg == null) return;
        try {
            BlackBoxCore.get().uninstallPackageAsUser(pkg, 0);
        } catch (Throwable ignored) {
        }
    }

    private void deleteDumpDir(File dir) {
        try {
            File[] files = dir != null ? dir.listFiles() : null;
            if (files != null) {
                for (File f : files) {
                    if (!f.delete()) {
                        f.deleteOnExit();
                    }
                }
            }
            if (dir != null && !dir.delete()) {
                dir.deleteOnExit();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把已 dump 的 dex 打包成 zip 并导出到系统 Download 目录（API 29+ 用 MediaStore）。返回展示用路径，失败返回 null。 */
    private String exportZip(final String pkg, final File outDir) {
        try {
            File[] files = outDir != null ? outDir.listFiles() : null;
            if (files == null || files.length == 0) return null;
            List<File> dexes = new ArrayList<>();
            for (File f : files) {
                if (f.getName().endsWith(".dex")) dexes.add(f);
            }
            if (dexes.isEmpty()) return null;
            String name = "sandbox_dump_" + pkg + "_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".zip";
            Uri savedUri = null;
            OutputStream os = null;
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    savedUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (savedUri != null) os = getContentResolver().openOutputStream(savedUri);
                } catch (Throwable t) {
                    savedUri = null;
                    os = null;
                }
            }
            if (os == null) {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                os = new FileOutputStream(new File(dir, name));
            }
            ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os));
            try {
                byte[] buf = new byte[8192];
                for (File f : dexes) {
                    ZipEntry entry = new ZipEntry(f.getName());
                    entry.setSize(f.length());
                    zos.putNextEntry(entry);
                    FileInputStream in = new FileInputStream(f);
                    try {
                        int n;
                        while ((n = in.read(buf)) != -1) zos.write(buf, 0, n);
                    } finally {
                        in.close();
                    }
                    zos.closeEntry();
                }
            } finally {
                try {
                    zos.close();
                } catch (Throwable ignored) {
                }
            }
            if (savedUri != null) {
                return "Download/" + name;
            }
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath() + "/" + name;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 脱壳完成后把本界面（所在的 mHook 任务）拉到前台，清掉虚拟应用页面，展示结果。 */
    private void bringToFront() {
        try {
            Intent i = new Intent(SandboxDumpActivity.this, SandboxDumpActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void setStatus(final String s) {
        statusView.setText(s);
    }

    /** 追加一行带时间戳的步骤日志，最多保留 50 行。 */
    private void logLine(final String line) {
        if (logView == null) return;
        if (logBuffer.length() > 0) logBuffer.append("\n");
        logBuffer.append("[").append(new SimpleDateFormat("HH:mm:ss").format(new Date())).append("] ").append(line);
        String[] parts = logBuffer.toString().split("\n");
        if (parts.length > 50) {
            StringBuilder sb = new StringBuilder();
            for (int i = parts.length - 50; i < parts.length; i++) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(parts[i]);
            }
            logBuffer.setLength(0);
            logBuffer.append(sb);
        }
        logView.setText(logBuffer.toString());
    }

    private void reset() {
        busy = false;
        btnChoose.setEnabled(true);
    }
}
