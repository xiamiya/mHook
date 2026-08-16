package cn.mhook.activity.mkfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.arlib.floatingsearchview.FloatingSearchView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.listener.OnItemChildClickListener;
import com.chad.library.adapter.base.listener.OnItemChildLongClickListener;
import com.tamsiree.rxkit.RxActivityTool;
import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.RxClipboardTool;
import com.tamsiree.rxkit.RxFileTool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import cn.mhook.activity.selectapp.SelectActivity;
import cn.mhook.activity.selectapp.SelectAppItem;
import cn.mhook.activity.selectapp.SetectAppAdapter;
import cn.mhook.mhook.R;
import cn.mhook.widget.GlassToast;

import static cn.mhook.mData.mDir;
import static cn.mhook.msu.su.set777;

public class MKFixActivity extends Activity {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout refreshLayout;
    private Handler handler;
    private List<SelectAppItem> datas = new ArrayList<>();
    private SetectAppAdapter adapter;
    private FloatingSearchView floatingSearchView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_xw);
        ((TextView) findViewById(R.id.page_title)).setText("改包修复");
        handler = new Handler();
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Bundle bundle = new Bundle();
                bundle.putString("appType", "all");
                RxActivityTool.skipActivityForResult(MKFixActivity.this, SelectActivity.class, bundle, 9008);
            }
        });
        initListView();
    }

    private void initListView() {
        recyclerView = findViewById(R.id.config_recycler_view);
        refreshLayout = findViewById(R.id.refresh_layout);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setOrientation(RecyclerView.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);
        refreshLayout.setRefreshing(true);
        refreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                initList("");
            }
        });
        initList("");
        adapter = new SetectAppAdapter(R.layout.activity_xw_item, datas);
        adapter.setEmptyView(LayoutInflater.from(this).inflate(R.layout.view_empty, null));
        adapter.addChildClickViewIds(R.id.appInfoItem);
        adapter.addChildLongClickViewIds(R.id.appInfoItem);
        adapter.setOnItemChildClickListener(new OnItemChildClickListener() {
            @Override
            public void onItemChildClick(@NonNull BaseQuickAdapter adapter, @NonNull View view, int position) {
                final String[] items = new String[]{"模式一", "模式二", "模式三", "模式四"};
                final String[] descs = new String[]{
                        "dex直接加载热修复",
                        "dex预优化加载热修复",
                        "dex合并热修复(推荐普通应用)",
                        "AutoJS脚本覆盖热修复(js/snapshot/project.json，适合AutoJS打包应用改脚本)"
                };
                final String pkg = datas.get(position).getPkg();
                new AlertDialog.Builder(MKFixActivity.this)
                        .setTitle("选择修复模式")
                        .setSingleChoiceItems(items, MK.getCheck(pkg), new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                MK.setCheck(pkg, which);
                                GlassToast.success(MKFixActivity.this, "已选择: " + items[which] + "\n" + descs[which]);
                                dialog.dismiss();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .create().show();
            }
        });
        adapter.setOnItemChildLongClickListener(new OnItemChildLongClickListener() {
            @Override
            public boolean onItemChildLongClick(@NonNull BaseQuickAdapter adapter, @NonNull View view, int position) {
                new AlertDialog.Builder(MKFixActivity.this)
                        .setTitle("提示")
                        .setMessage("确定要移除该应用吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                RxFileTool.deleteDir(mDir + datas.get(position).getPkg() + "/fix/");
                                initList("");
                                dialog.dismiss();
                            }
                        })
                        .create().show();
                return true;
            }
        });
        recyclerView.setAdapter(adapter);
        floatingSearchView = findViewById(R.id.floating_search_view);
        floatingSearchView.setOnQueryChangeListener(new FloatingSearchView.OnQueryChangeListener() {
            @Override
            public void onSearchTextChanged(String oldQuery, String newQuery) {
                initList(newQuery);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 9008 && resultCode == RESULT_OK) {
            String pkg = data.getStringExtra("pkg");
            String path = mDir + pkg + "/fix/mk.apk";
            Boolean s = RxFileTool.copyFile(RxAppTool.getAppPath(this, pkg), path);
            if (s) {
                GlassToast.success(this, "已自动复制补丁包路径到剪切板，前往该目录定制补丁包");
                RxClipboardTool.copyText(MKFixActivity.this, path);
                try {
                    set777();
                } catch (Throwable ignored) {
                }
            } else {
                GlassToast.error(this, "添加失败：改包修复需要 root 权限写入 /data/mHook，当前设备似乎未授予 root");
            }
            initList("");
        }
    }

    private void initList(final String query) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (datas.size() > 0) {
                    datas.clear();
                }
                java.util.List<File> fileList = RxFileTool.listFilesInDir(mDir, false);
                if (fileList != null) {
                    for (File file : fileList) {
                        String filePath = file.getPath();
                        if (RxFileTool.fileExists(filePath + "/fix/mk.apk")) {
                            String pkg = file.getName();
                            if (pkg.contains(query) || RxAppTool.getAppName(MKFixActivity.this, pkg).contains(query)) {
                                datas.add(new SelectAppItem(pkg, RxAppTool.getAppVersionName(MKFixActivity.this, pkg), RxAppTool.getAppName(MKFixActivity.this, pkg), false));
                            }
                        }
                    }
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        adapter.notifyDataSetChanged();
                        refreshLayout.setRefreshing(false);
                    }
                }, 0);
            }
        }).start();
    }
}
