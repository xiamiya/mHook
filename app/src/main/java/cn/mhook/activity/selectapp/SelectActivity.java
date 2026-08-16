package cn.mhook.activity.selectapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.alibaba.fastjson.JSONArray;
import com.arlib.floatingsearchview.FloatingSearchView;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.listener.OnItemChildClickListener;
import com.tamsiree.rxkit.RxAppTool;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import cn.mhook.mhook.R;

public class SelectActivity extends Activity {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout refreshLayout;
    private Handler handler;
    private List<SelectAppItem> datas = new ArrayList<>();
    private SetectAppAdapter adapter;
    private FloatingSearchView floatingSearchView;
    public static JSONArray ret = new JSONArray();
    private static final Comparator<SelectAppItem> NAME_COMPARATOR = new Comparator<SelectAppItem>() {
        private final Collator collator = Collator.getInstance(Locale.CHINA);

        @Override
        public int compare(SelectAppItem a, SelectAppItem b) {
            String na = a.getAppName() == null ? "" : a.getAppName();
            String nb = b.getAppName() == null ? "" : b.getAppName();
            return collator.compare(na, nb);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_select_app);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        handler = new Handler();
        initListView();
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
        adapter = new SetectAppAdapter(datas);
        adapter.addChildClickViewIds(R.id.appInfoItem);
        adapter.setOnItemChildClickListener(new OnItemChildClickListener() {
            @Override
            public void onItemChildClick(@NonNull BaseQuickAdapter adapter, @NonNull View view, int position) {
                Intent intent=new Intent();
                intent.putExtra("pkg",datas.get(position).getPkg());
                setResult(RESULT_OK,intent);
                finish();
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

    private  void initList(final String query){
        new Thread(new Runnable(){
            @Override
            public void run(){
                if (datas.size()>0){
                    datas.clear();
                }
                List<SelectAppItem> sysApps = new ArrayList<>();
                List<SelectAppItem> userApps = new ArrayList<>();
                PackageManager packageManager =SelectActivity.this.getPackageManager();
                HashSet<String> systemPkgs = new HashSet<>();
                try {
                    for (ApplicationInfo ai : packageManager.getInstalledApplications(0)) {
                        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                            systemPkgs.add(ai.packageName);
                        }
                    }
                }catch (Throwable ignored) {
                }
                List<RxAppTool.AppInfo> list;
                list = RxAppTool.getAllAppsInfo(SelectActivity.this);
                for (RxAppTool.AppInfo info:list) {
                    if (info.getPackageName().equals(getPackageName())) continue;
                    if (!info.getPackageName().contains(query)&&!info.getName().contains(query)) continue;
                    SelectAppItem item = new SelectAppItem(info.getPackageName(),info.getVersionName(),info.getName(),ret.contains(info.getPackageName()));
                    if (systemPkgs.contains(info.getPackageName())){
                        sysApps.add(item);
                    }else {
                        userApps.add(item);
                    }
                }
                Collections.sort(sysApps, NAME_COMPARATOR);
                Collections.sort(userApps, NAME_COMPARATOR);
                if (query.isEmpty()){
                    datas.add(new SelectAppItem("用户应用 (" + userApps.size() + ")"));
                    datas.addAll(userApps);
                    datas.add(new SelectAppItem("系统应用 (" + sysApps.size() + ")"));
                    datas.addAll(sysApps);
                }else {
                    datas.addAll(sysApps);
                    datas.addAll(userApps);
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
