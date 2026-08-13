package cn.mhook.activity.appxw;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.arlib.floatingsearchview.FloatingSearchView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.listener.OnItemChildClickListener;
import com.chad.library.adapter.base.listener.OnItemChildLongClickListener;
import com.chad.library.adapter.base.listener.OnItemLongClickListener;
import com.nightonke.boommenu.BoomButtons.HamButton;
import com.nightonke.boommenu.BoomButtons.OnBMClickListener;
import com.nightonke.boommenu.BoomMenuButton;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.tamsiree.rxkit.RxActivityTool;
import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.RxFileTool;
import com.tamsiree.rxkit.view.RxToast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import cn.mhook.BaseActivity;
import cn.mhook.activity.selectapp.SelectActivity;
import cn.mhook.activity.selectapp.SelectAppItem;
import cn.mhook.activity.selectapp.SetectAppAdapter;
import cn.mhook.mhook.R;

import static cn.mhook.mData.mDir;

public class AppXWActivity extends BaseActivity {

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
        handler = new Handler();
        initListView();
        initBoomMenu();
    }

    private void initListView(){
        recyclerView = (RecyclerView) findViewById(R.id.config_recycler_view);
        refreshLayout=(SwipeRefreshLayout)findViewById(R.id.refresh_layout);
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
                Bundle bundle = new Bundle();
                bundle.putString("pkg",datas.get(position).getPkg());
                RxActivityTool.skipActivity(AppXWActivity.this,AppSetCfg.class,bundle);
            }
        });
        adapter.setOnItemChildLongClickListener(new OnItemChildLongClickListener() {
            @Override
            public boolean onItemChildLongClick(@NonNull BaseQuickAdapter adapter, @NonNull View view, int position) {
                new QMUIDialog.MessageDialogBuilder(AppXWActivity.this)
                        .setTitle("提示")
                        .setMessage("确定要移除该应用吗？")
                        .setSkinManager(QMUISkinManager.defaultInstance(AppXWActivity.this))
                        .addAction("取消", new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                dialog.dismiss();
                            }
                        })
                        .addAction(0, "确定", QMUIDialogAction.ACTION_PROP_NEGATIVE, new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                String appSettingDir = mDir+datas.get(position).getPkg()+"/Setting.json";
                                RxFileTool.deleteFile(appSettingDir);
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


    private void initBoomMenu(){
        BoomMenuButton bmb = findViewById(R.id.bmb);
        bmb.addBuilder(new HamButton.Builder()
                .normalImageRes(R.drawable.eagle)
                .normalText("添加分析应用")
                .listener(new OnBMClickListener() {
                    @Override
                    public void onBoomButtonClick(int index) {
                        Bundle bundle=new Bundle();
                        bundle.putString("appType","all");
                        RxActivityTool.skipActivityForResult(AppXWActivity.this, SelectActivity.class,bundle,9008);
                    }
                })
                .subNormalText("添加需要分析的应用程序"));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==9008&&resultCode==RESULT_OK){
            String pkg = data.getStringExtra("pkg");
            String appSettingDir = mDir+pkg+"/Setting.json";
            if(RxFileTool.fileExists(appSettingDir)){
                RxToast.warning("已添加该应用");
            }else {
                RxFileTool.writeFileFromString(appSettingDir,"{}",false);
                initList("");
            }
        }
    }


    private  void initList(final String query){
        new Thread(new Runnable(){
            @Override
            public void run(){
                if (datas.size()>0){
                    datas.clear();
                }
                java.util.List<File> fileList = RxFileTool.listFilesInDir(mDir,false);
                if (fileList != null) {
                    for (File file : fileList) {
                        String filePath = file.getPath();
                        if (RxFileTool.fileExists(filePath+"/Setting.json")){
                            String pkg = file.getName();
                            if (pkg.contains(query)||RxAppTool.getAppName(AppXWActivity.this,pkg).contains(query)){
                                datas.add(new SelectAppItem(pkg,RxAppTool.getAppVersionName(AppXWActivity.this,pkg),RxAppTool.getAppName(AppXWActivity.this,pkg),false));
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
