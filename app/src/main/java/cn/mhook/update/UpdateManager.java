package cn.mhook.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.tamsiree.rxkit.view.RxToast;

/**
 * 更新检查统一入口：检查 GitHub Releases → 有新版弹窗。
 * 弹窗按钮：暂不更新 / 不再提示（忽略该版本）/ 下载更新（跳浏览器）。
 */
public class UpdateManager {

    private static final String PREFS = "update_prefs";
    private static final String KEY_IGNORED = "ignored_version";

    public static String ignoredVersion(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_IGNORED, null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setIgnoredVersion(Context c, String v) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_IGNORED, v).apply();
        } catch (Throwable ignored) {
        }
    }

    public static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 检查更新并弹窗。manual=true 来自设置页（无更新/失败都给反馈）；false 为启动自动检测（安静）。 */
    public static void checkAndShow(final Activity activity, final boolean manual) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateChecker.ReleaseInfo info;
                try {
                    info = UpdateChecker.fetchLatest();
                } catch (final Throwable t) {
                    if (manual) {
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showError(activity, t);
                            }
                        });
                    }
                    return;
                }
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleResult(activity, info, manual);
                    }
                });
            }
        }).start();
    }

    private static void handleResult(Activity activity, UpdateChecker.ReleaseInfo info, boolean manual) {
        String cur = currentVersion(activity);
        int cmp = UpdateChecker.compareVersion(info.tagName, "v" + cur);
        if (cmp <= 0) {
            if (manual) showUpToDate(activity, cur);
            return;
        }
        // 仅启动自动检测支持"不再提示"；手动检测始终弹窗
        if (!manual && info.tagName != null && info.tagName.equals(ignoredVersion(activity))) {
            return;
        }
        showUpdate(activity, info, manual);
    }

    private static void showUpToDate(Activity activity, String cur) {
        new AlertDialog.Builder(activity)
                .setTitle("已是最新版本")
                .setMessage("当前已是最新版本 v" + cur)
                .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create().show();
    }

    private static void showError(Activity activity, Throwable t) {
        new AlertDialog.Builder(activity)
                .setTitle("检查更新失败")
                .setMessage("无法获取最新版本信息：" + t.getMessage() + "\n请检查网络后重试。")
                .setPositiveButton("重试", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        checkAndShow(activity, true);
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create().show();
    }

    private static void showUpdate(final Activity activity, final UpdateChecker.ReleaseInfo info, final boolean manual) {
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本：v").append(currentVersion(activity)).append('\n');
        msg.append("最新版本：").append(info.tagName);
        if (info.publishedAt != null && info.publishedAt.length() >= 10) {
            msg.append("\n发布日期：").append(info.publishedAt.substring(0, 10));
        }
        if (info.body != null && !info.body.isEmpty()) {
            msg.append("\n\n────────── 更新日志 ──────────\n").append(info.body);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + info.tagName)
                .setMessage(msg.toString())
                .setNegativeButton("暂不更新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
        // 仅启动自动检测提供"不再提示"
        if (!manual) {
            builder.setNeutralButton("不再提示", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    setIgnoredVersion(activity, info.tagName);
                    dialog.dismiss();
                    RxToast.info("已忽略该版本更新");
                }
            });
        }
        builder.setPositiveButton("下载更新", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                if (info.apkUrl == null) {
                    RxToast.warning("该版本发布内容没有 APK 附件");
                    return;
                }
                openBrowser(activity, info.apkUrl);
            }
        })
        .create().show();
    }

    private static void openBrowser(Activity activity, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            RxToast.error("无法打开浏览器：" + t.getMessage());
        }
    }
}
