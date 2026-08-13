package cn.mhook.activity.mkfix;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
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
import com.nightonke.boommenu.BoomButtons.HamButton;
import com.nightonke.boommenu.BoomButtons.OnBMClickListener;
import com.nightonke.boommenu.BoomMenuButton;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.tamsiree.rxkit.RxActivityTool;
import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.RxClipboardTool;
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
import cn.mhook.msu.su;

import static cn.mhook.mData.mDir;
import static cn.mhook.msu.su.set777;

public class MKFixActivity extends BaseActivity {

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

                final String[] items = new String[]{"模式一", "模式二", "模式三", "模式四"};
                final String[] descs = new String[]{
                        "dex直接加载热修复",
                        "dex预优化加载热修复",
                        "dex合并热修复(推荐普通应用)",
                        "AutoJS脚本覆盖热修复(js/snapshot/project.json，适合AutoJS打包应用改脚本)"
                };
                String pkg = datas.get(position).getPkg();
                new QMUIDialog.CheckableDialogBuilder(MKFixActivity.this)
                        .setCheckedIndex(MK.getCheck(pkg))
                        .setTitle("选择修复模式")
                        .setSkinManager(QMUISkinManager.defaultInstance(MKFixActivity.this))
                        .addItems(items, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                MK.setCheck(pkg,which);
                                RxToast.success("已选择: "+items[which]+"\n"+descs[which]);
                                dialog.dismiss();
                            }
                        })
                        .create().show();
            }
        });
        adapter.setOnItemChildLongClickListener(new OnItemChildLongClickListener() {
            @Override
            public boolean onItemChildLongClick(@NonNull BaseQuickAdapter adapter, @NonNull View view, int position) {
                new QMUIDialog.MessageDialogBuilder(MKFixActivity.this)
                        .setTitle("提示")
                        .setMessage("确定要移除该应用吗？")
                        .setSkinManager(QMUISkinManager.defaultInstance(MKFixActivity.this))
                        .addAction("取消", new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                                dialog.dismiss();
                            }
                        })
                        .addAction(0, "确定", QMUIDialogAction.ACTION_PROP_NEGATIVE, new QMUIDialogAction.ActionListener() {
                            @Override
                            public void onClick(QMUIDialog dialog, int index) {
                               RxFileTool.deleteDir(mDir+datas.get(position).getPkg()+"/fix/");
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
                .normalText("添加软件")
                .listener(new OnBMClickListener() {
                    @Override
                    public void onBoomButtonClick(int index) {
                        Bundle bundle=new Bundle();
                        bundle.putString("appType","all");
                        RxActivityTool.skipActivityForResult(MKFixActivity.this, SelectActivity.class,bundle,9008);
                    }
                })
                .subNormalText("添加需要热修复的软件"));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==9008&&resultCode==RESULT_OK){
            String pkg = data.getStringExtra("pkg");
            String path = mDir+pkg+"/fix/mk.apk";
            Boolean s = RxFileTool.copyFile(RxAppTool.getAppPath(this,pkg),path);
            if (s){
                RxToast.success("已自动复制补丁包路径到剪切板，前往该目录定制补丁包");
                RxClipboardTool.copyText(MKFixActivity.this,path);
                set777();
            }else {
                RxToast.error("复制补丁包失败，请反馈");
            }
            initList("");
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
                        if (RxFileTool.fileExists(filePath+"/fix/mk.apk")){
                            String pkg = file.getName();
                            if (pkg.contains(query)|| RxAppTool.getAppName(MKFixActivity.this,pkg).contains(query)){
                                datas.add(new SelectAppItem(pkg,RxAppTool.getAppVersionName(MKFixActivity.this,pkg),RxAppTool.getAppName(MKFixActivity.this,pkg),false));
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
