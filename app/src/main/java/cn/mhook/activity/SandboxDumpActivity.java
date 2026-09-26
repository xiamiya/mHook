package cn.mhook.activity;

import android.app.Activity;
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
import android.widget.ScrollView;
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


import cn.mhook.mhook.R;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * 免root动态分析：选择 APK → 自动装入沙箱运行 → 虚拟进程内自动 dump dex → 自动返回界面并导出 zip 到 Download。
 */
public class SandboxDumpActivity extends Activity {

    private static final int REQ_APK = 1001;
    private static final int REQ_SELECT = 1002;

    private static volatile String sDoneStatus;
    private static volatile String sDoneLog;

    private TextView btnChoose;
    private TextView logView;
    private TextView cleanBtn;
    private StringBuilder logBuffer = new StringBuilder();
    private String sDumpLog = "";
    private static boolean sDoneEbpf;
    private boolean ebpfMode;
    private File ebpfDirOverride;
    private long lastEbpfToast;
    private boolean ebpfTimeUpToast;
    private Handler handler = new Handler(Looper.getMainLooper());

    private volatile String currentPkg;
    private File apkFile;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sandbox_dump);
        ebpfMode = getIntent() != null && getIntent().getBooleanExtra("ebpf", false);
        if (ebpfMode) {
            TextView t = findViewById(R.id.title);
            if (t != null) t.setText("eBPF深度调试（需root）");
            TextView c = findViewById(R.id.sb_btn_choose);
            if (c != null) c.setText("选择已安装应用开始 eBPF 脱壳");
            View clean = findViewById(R.id.sb_btn_clean);
            if (clean != null) clean.setVisibility(View.GONE);
            TextView hint = findViewById(R.id.sb_hint);
            if (hint != null) hint.setText("说明：把 eBPF uprobe 挂到 libart，在内核态被动抓取应用“执行过”的 dex 与方法体，退出时回填被抽取的方法。不注入目标进程、对壳基本隐形，适合抽取壳。需 root；抓取期间请保持目标应用在前台并多切换页面。");
        }
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnChoose = findViewById(R.id.sb_btn_choose);
        cleanBtn = findViewById(R.id.sb_btn_clean);
        logView = findViewById(R.id.sb_log);
        findViewById(R.id.sb_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logBuffer.setLength(0);
                logView.setText("");
            }
        });
        // 自动返回重新创建的实例：直接展示上次分析结果（仅同一种模式，避免串台）
        if (sDoneStatus != null && sDoneEbpf == ebpfMode) {
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
                    // 一加/ColorOS 等系统无 OPEN_DOCUMENT 处理器，回退到 GET_CONTENT
                    try {
                        Intent i2 = new Intent(Intent.ACTION_GET_CONTENT);
                        i2.addCategory(Intent.CATEGORY_OPENABLE);
                        i2.setType("application/vnd.android.package-archive");
                        startActivityForResult(i2, REQ_APK);
                    } catch (Throwable t2) {
                        setStatus("无法打开文件选择器");
                        logLine("错误：无法打开文件选择器");
                    }
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
        if (ebpfMode) {
            // eBPF 是“抓运行中的应用”，所以选已安装应用而不是 APK 文件
            btnChoose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (busy) return;
                    chooseInstalledApp();
                }
            });
        }
    }

    /** eBPF 模式：打开应用选择器（与内存分析同一套 UI，支持搜索）。 */
    private void chooseInstalledApp() {
        Bundle bundle = new Bundle();
        bundle.putString("appType", "all");
        com.tamsiree.rxkit.RxActivityTool.skipActivityForResult(this,
                cn.mhook.activity.selectapp.SelectActivity.class, bundle, REQ_SELECT);
    }

    /** eBPF 脱壳主流程（目标为设备上已安装、可运行的应用）。 */
    private void runEbpfDump(final String pkg) {
        busy = true;
        if (btnChoose != null) btnChoose.setEnabled(false);
        logBuffer.setLength(0);
        logView.setText("");
        setStatus("步骤 1/3：准备 eBPF 脱壳...");
        logLine("目标应用：" + pkg);
        lastEbpfToast = System.currentTimeMillis();
        ebpfTimeUpToast = false;
        android.widget.Toast.makeText(this, "eBPF 抓取开始（90 秒）\n请让目标应用保持在前台并多点页面",
                android.widget.Toast.LENGTH_LONG).show();
        currentPkg = pkg;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File outDir = new File(getFilesDir(), "ebpf_dump" + File.separator + pkg);
                    if (outDir.exists()) deleteDumpDir(outDir);
                    outDir.mkdirs();
                    ebpfDirOverride = outDir;
                    final int n = cn.mhook.npatch.EbpfDumper.dump(SandboxDumpActivity.this, pkg, outDir, 90,
                            new cn.mhook.npatch.EbpfDumper.Log() {
                                @Override
                                public void log(final String s) {
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            logLine("[eBPF] " + s);
                                        }
                                    });
                                }
                            },
                            new cn.mhook.npatch.EbpfDumper.Tick() {
                                @Override
                                public void onTick(int remainSec) {
                                    final int r = remainSec;
                                    handler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            setStatus("步骤 2/3：eBPF 抓取中 —— 请保持目标应用在前台并多切换页面");
                                            // 用户此时在目标应用里，看不到本页日志/状态，用系统 Toast 浮在上面提示；
                                            // 每 10 秒提示一次（太频繁会刷屏，太久用户会以为卡住）。
                                            long now = System.currentTimeMillis();
                                            if (r == 0) {
                                                // 注意：倒计时到 0 后工具还要做“退出时修复”，su 调用会等很久，
                                                // ticker 仍每秒回调 -> 这里必须只弹一次，否则刷屏
                                                if (!ebpfTimeUpToast) {
                                                    ebpfTimeUpToast = true;
                                                    lastEbpfToast = now;
                                                    android.widget.Toast.makeText(SandboxDumpActivity.this,
                                                            "eBPF 抓取时间到，正在回填修复 dex（需要几分钟，请耐心等待）...",
                                                            android.widget.Toast.LENGTH_LONG).show();
                                                }
                                            } else if (r > 0 && now - lastEbpfToast >= 10000) {
                                                lastEbpfToast = now;
                                                android.widget.Toast.makeText(SandboxDumpActivity.this,
                                                        "eBPF 抓取中：还剩 " + r + " 秒\n请保持目标应用在前台并多点页面",
                                                        android.widget.Toast.LENGTH_SHORT).show();
                                            }
                                        }
                                    });
                                }
                            });
                    if (n > 0) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                setStatus("步骤 3/3：正在检测/去重/合并/修复 dex...");
                                logLine("eBPF 共抓取 " + n + " 个 dex，开始检测去重 + 合并修复...");
                            }
                        });
                        // 注：不再调用 DexConsolidator（它会对每个 dex 全量扫描类并互相比对，
                        // 上百个/几百 MB 的 eBPF 产物上极慢，且与 DexMerger 的按类去重重复）。
                        // 直接交给 DexMerger：按类去重 + DexPool 重建（附带修复 header/checksum）。
                        final int merged = cn.mhook.npatch.DexMerger.merge(outDir,
                                new cn.mhook.npatch.DexMerger.Log() {
                                    @Override
                                    public void log(final String s) {
                                        handler.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                logLine("[合并] " + s);
                                            }
                                        });
                                    }
                                });
                        // eBPF：合并完成后直接导出，不再走沙箱那套“等待稳定/轮询”流程
                        final String zipInfo = merged > 0 ? exportZip(pkg, outDir) : null;
                        if (zipInfo != null) {
                            deleteDumpDir(outDir);
                        }
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                logLine("合并修复完成：最终 " + merged + " 个 dex（classes.dex…）");
                                logLine(zipInfo != null ? "导出完成：" + zipInfo : "导出失败");
                                android.widget.Toast.makeText(SandboxDumpActivity.this,
                                        "eBPF 完成：合并修复为 " + merged + " 个 dex",
                                        android.widget.Toast.LENGTH_LONG).show();
                                sDoneEbpf = true;
                                sDoneStatus = "eBPF 深度调试完成，合并修复后 " + merged + " 个 dex"
                                        + (zipInfo != null ? "\n导出完成：" + zipInfo : "");
                                sDoneLog = logBuffer.toString();
                                setStatus(sDoneStatus);
                                logView.setText(sDoneLog);
                                reset();
                            }
                        });
                    } else {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                logLine("eBPF 未脱到 dex（应用可能未启动成功或被反调试）");
                                reset();
                            }
                        });
                    }
                } catch (final Throwable t) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("eBPF 失败：" + t);
                            logLine("错误：" + t);
                            reset();
                        }
                    });
                }
            }
        }).start();
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
        } else if (requestCode == REQ_SELECT && resultCode == RESULT_OK && data != null) {
            String pkg = data.getStringExtra("pkg");
            if (pkg != null && !pkg.isEmpty()) {
                runEbpfDump(pkg);
            }
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
                apkFile = null;
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
                    // 只拉起虚拟进程（Application 会加载 dex），不显示 Activity，静默动态分析
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
                            logLine("应用进程已启动，等待 dex 加载并开始分析...");
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

    /** 过滤 dump_log 中逐 dex 的明细（产出 dex / 主动加载补码），保留入口、轮次汇总等有用行。 */
    private static String filterDumpLog(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            if (line.contains("产出dex") || line.contains("主动加载")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private void pollDump(final String pkg) {
        final File outDir = ebpfDirOverride != null ? ebpfDirOverride : new File(getFilesDir(), "sandbox_dump" + File.separator + pkg);
        final long start = System.currentTimeMillis();
        final long timeout = 90 * 1000L;
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
                                    setStatus("步骤 4/4：正在动态分析... 已发现 " + c + " 个 dex，持续扫描中");
                                    logLine("动态分析进度：已发现 " + c + " 个 dex（等待稳定后自动完成）");
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
                                            setStatus("步骤 4/4：正在动态分析... 还剩 " + remain + "s，尚未发现 dex（应用可能仍在加载或已崩溃）");
                                            logLine("还剩 " + remain + "s，仍未发现 dex（应用可能仍在加载或已崩溃）");
                                        } else {
                                            setStatus("步骤 4/4：正在动态分析... 还剩 " + remain + "s，已发现 " + cur + " 个 dex（等待稳定）");
                                            logLine("还剩 " + remain + "s，已发现 " + cur + " 个 dex（等待稳定后自动完成）");
                                        }
                                    }
                                });
                            }
                        }
                        long now = System.currentTimeMillis();
                        // 等待更长：至少 25s（覆盖 15s/25s 延迟加载轮次）+ 15s 无新 dex 才判定完成
                        if (lastCount > 0 && now - start > 25 * 1000L && now - stableSince > 15 * 1000L) {
                            final int c = lastCount;
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    setStatus("正在打包导出 zip...");
                                    logLine("动态分析完成：共 " + c + " 个 dex");
                                    logLine("正在打包导出到 Download...");
                                }
                            });
                    final String zipInfo = exportZip(pkg, outDir);
                    if (zipInfo != null) {
                        deleteDumpDir(outDir);
                    }
                    if (!ebpfMode) {
                        try {
                            BlackBoxCore.get().stopPackage(pkg, 0);
                        } catch (Throwable ignored) {
                        }
                        // 动态分析结束（无论成败）卸载沙箱内应用，释放占用的存储空间
                        uninstallSandboxApp(pkg);
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            logLine(zipInfo != null ? "导出完成：" + zipInfo : "导出失败");
                            if (zipInfo != null) logLine("已清理临时文件");
                            if (!ebpfMode) logLine("已卸载沙箱内应用，释放空间");
                            if (!ebpfMode && sDumpLog != null && !sDumpLog.trim().isEmpty()) {
                                logLine("\n──── 沙箱分析诊断日志 ────");
                                logLine(filterDumpLog(sDumpLog.trim()));
                                logLine("──── 诊断日志(已随 zip 打包 file: dump_log.txt) ────");
                            }
                                    sDoneEbpf = ebpfMode;
                                    sDoneStatus = "动态分析完成，共 " + c + " 个 dex"
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
                                    setStatus("动态分析超时，正在处理结果...");
                                    logLine("动态分析超时（90秒），已发现 " + c + " 个 dex");
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
                            // 动态分析结束（无论成败）卸载沙箱内应用，释放占用的存储空间
                            uninstallSandboxApp(pkg);
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    logLine(zipInfo != null ? "导出完成：" + zipInfo : "导出失败");
                                    if (zipInfo != null) logLine("已清理应用目录中的临时动态分析文件");
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

    /** 删除应用目录中的临时动态分析文件。 */
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
    private static boolean isHostDex(File f) {
        try {
            byte[] d = readAll(f);
            if (d == null) return false;
            return containsBytes(d, "top/niunaijun/blackbox".getBytes("UTF-8"))
                    || containsBytes(d, "cn/mhook/mhook/xposed".getBytes("UTF-8"));
        } catch (Throwable t) {
            return false;
        }
    }

    private String computeRealApp(java.util.List<File> dexes) {
        try {
            if (dexes == null || dexes.isEmpty()) return null;
            String shellApp = null, appPkg = null;
            File apk = apkFile;
            if (apk != null && apk.exists()) {
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apk)) {
                    java.util.zip.ZipEntry e = zf.getEntry("AndroidManifest.xml");
                    if (e != null) {
                        java.io.InputStream in = zf.getInputStream(e);
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) != -1) bos.write(b, 0, n);
                        in.close();
                        byte[] m = bos.toByteArray();
                        shellApp = cn.mhook.npatch.AxmlPatch.findApplicationName(m);
                        appPkg = cn.mhook.npatch.AxmlPatch.findPackageName(m);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (shellApp != null && !cn.mhook.npatch.DexScanner.isShellProxyName(shellApp)) {
                return shellApp;
            }
            java.util.List<String[]> apps = cn.mhook.npatch.DexScanner.findApplicationHierarchy(dexes);
            String realApp = cn.mhook.npatch.DexScanner.pickRealApplication(apps, shellApp, appPkg);
            return realApp != null ? realApp : shellApp;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean containsBytes(byte[] hay, byte[] needle) {
        if (hay == null || needle == null) return false;
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
    private String exportZip(final String pkg, final File outDir) {
        // 读取动态分析诊断日志（若有），供完成回调追加到 UI
        sDumpLog = "";
        try {
            java.io.File lf = outDir != null ? new java.io.File(outDir, "dump_log.txt") : null;
            java.io.File af = outDir != null ? new java.io.File(outDir, "active_load.txt") : null;
            StringBuilder sb = new StringBuilder();
            if (af != null && af.exists()) sb.append(summaryOfActive(new String(readAll(af), "UTF-8")));
            if (lf != null && lf.exists()) sb.append(new String(readAll(lf), "UTF-8"));
            sDumpLog = sb.toString();
        } catch (Throwable ignored) {
        }
        try {
            File[] files = outDir != null ? outDir.listFiles() : null;
            if (files == null || files.length == 0) return null;
            List<File> dexes = new ArrayList<>();
            for (File f : files) {
                if (!f.getName().endsWith(".dex")) continue;
                if (isHostDex(f)) continue;
                dexes.add(f);
            }
            // 真实 Application：写入 real_app.txt 一并导出
            try {
                String realApp = computeRealApp(dexes);
                if (realApp != null) {
                    File raf = new File(outDir, "real_app.txt");
                    FileOutputStream rfos = new FileOutputStream(raf);
                    try { rfos.write(realApp.getBytes("UTF-8")); } finally { rfos.close(); }
                }
            } catch (Throwable ignored) {
            }
            // 顺带打包分析日志
            File logFile = outDir != null ? new File(outDir, "dump_log.txt") : null;
            if (logFile != null && !logFile.exists()) logFile = null;
            File activeFile = outDir != null ? new File(outDir, "active_load.txt") : null;
            if (activeFile != null && !activeFile.exists()) activeFile = null;
            if (dexes.isEmpty() && logFile == null && activeFile == null) return null;
            String name = "sandbox_dump_" + pkg + "_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".zip";
            Uri savedUri = null;
            OutputStream os = null;
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/mhook_dump");
                    savedUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (savedUri != null) os = getContentResolver().openOutputStream(savedUri);
                } catch (Throwable t) {
                    savedUri = null;
                    os = null;
                }
            }
            if (os == null) {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "mhook_dump");
                if (!dir.exists()) dir.mkdirs();
                os = new FileOutputStream(new File(dir, name));
            }
            ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os));
            try {
                byte[] buf = new byte[8192];
                java.util.List<java.io.File> toZip = new java.util.ArrayList<>(dexes);
                if (logFile != null) toZip.add(logFile);
                if (activeFile != null) toZip.add(activeFile);
                File rafFile = new File(outDir, "real_app.txt");
                if (rafFile.exists()) toZip.add(rafFile);
                for (File f : toZip) {
                    ZipEntry entry = new ZipEntry(f.getName());
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
                return "Download/mhook_dump/" + name;
            }
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "mhook_dump").getAbsolutePath() + "/" + name;
        } catch (Throwable t) {
            android.util.Log.e("SandboxDump", "exportZip err", t);
            return null;
        }
    }

    /** 动态分析完成后把本界面（所在的 mHook 任务）拉到前台，清掉虚拟应用页面，展示结果。 */
    private void bringToFront() {
        try {
            Intent i = new Intent(SandboxDumpActivity.this, SandboxDumpActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 从 active_load 全文提取摘要：application/factory/类总数/加载成功，省略完整类列表。 */
    private static String summaryOfActive(String full) {
        if (full == null || full.isEmpty()) return "";
        // 不依赖分隔线星号数量：直接按关键文字定位
        int hIdx = full.indexOf("主动加载类");
        if (hIdx < 0) return full.endsWith("\n") ? full : full + "\n";
        int nl = full.indexOf('\n', hIdx);
        String head = (nl >= 0 ? full.substring(0, nl)
                : full.substring(0, Math.min(hIdx + 24, full.length()))).trim();
        StringBuilder sb = new StringBuilder(head).append("\n");
        int sIdx = full.indexOf("加载成功", hIdx);
        if (sIdx >= 0) {
            int snl = full.indexOf('\n', sIdx);
            sb.append((snl >= 0 ? full.substring(sIdx, snl)
                    : full.substring(sIdx)).trim()).append("\n");
        }
        sb.append("（完整类列表见 zip 内 active_load.txt）\n");
        return sb.toString();
    }

    private static byte[] readAll(java.io.File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) bos.write(b, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    private void setStatus(final String s) {
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
        final ScrollView sv = (ScrollView) findViewById(R.id.sb_log_scroll);
        if (sv != null) {
            sv.post(new Runnable() {
                @Override
                public void run() {
                    sv.fullScroll(ScrollView.FOCUS_DOWN);
                }
            });
        }
    }

    private void reset() {
        busy = false;
        btnChoose.setEnabled(true);
    }
}
