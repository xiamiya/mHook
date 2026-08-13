package cn.mhook.fragment;

import android.view.LayoutInflater;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.qmuiteam.qmui.arch.QMUIFragment;
import com.tamsiree.rxkit.RxActivityTool;

import java.util.ArrayList;
import java.util.List;
import cn.mhook.activity.SettingActivity;
import cn.mhook.activity.ai.AiActivity;
import cn.mhook.activity.appxw.AppXWActivity;
import cn.mhook.activity.SandboxDumpActivity;
import cn.mhook.activity.dump.DumpActivity;
import cn.mhook.activity.hook.HookActivity;
import cn.mhook.activity.mkfix.MKFixActivity;
import cn.mhook.activity.xp.XpModuleAiActivity;
import cn.mhook.mhook.R;

public class MainFragment extends QMUIFragment {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout refreshLayout;
    private View root;
    private List<MainItem> datas = new ArrayList<>();
    private MainAdapter adapter;
    /**
     * onCreateView
     */
    @Override
    protected View onCreateView() {
        root = LayoutInflater.from(getContext()).inflate(R.layout.fragment_main, null);
        initListView();
        return root;
    }

    private void initListView(){
        recyclerView = (RecyclerView) root.findViewById(R.id.config_recycler_view);
        refreshLayout=(SwipeRefreshLayout)root.findViewById(R.id.refresh_layout);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setOrientation(RecyclerView.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);
        refreshLayout.setEnabled(false);
        refreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {

            }
        });
        datas.add(new MainItem("自定义HOOK", "添加和管理自定义HOOK", getResources().getColor(R.color.blue), R.mipmap.hook, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RxActivityTool.skipActivity(getContext(), HookActivity.class);
            }
        }));

        datas.add(new MainItem("应用行为控制", "分析和控制应用的操作行为", getResources().getColor(R.color.app_color_theme_5), R.drawable.app_xw, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RxActivityTool.skipActivity(getContext(), AppXWActivity.class);
            }
        }));
        datas.add(new MainItem("MK热修复", "无感知修复异常", getResources().getColor(R.color.first_slide_background), R.drawable.fx, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RxActivityTool.skipActivity(getContext(), MKFixActivity.class);
            }
        }));
        datas.add(new MainItem("内存脱壳", "纯Java内存脱壳，dump加固后的dex", getResources().getColor(R.color.app_color_theme_6), R.mipmap.app, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RxActivityTool.skipActivity(getContext(), DumpActivity.class);
            }
        }));
        datas.add(new MainItem("沙箱脱壳（免root）", "选APK自动装入沙箱运行并dump，全程免root", getResources().getColor(R.color.app_color_theme_2), R.drawable.app_xw, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RxActivityTool.skipActivity(getContext(), SandboxDumpActivity.class);
            }
        }));
        datas.add(new MainItem("应用分析", "AI生成Hook配置/自动改包", getResources().getColor(R.color.app_color_theme_1), R.drawable.comment, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RxActivityTool.skipActivity(getContext(), AiActivity.class);
            }
        }));
        datas.add(new MainItem("XP模块分析AI版", "分析XP模块APK并导入Hook配置", getResources().getColor(R.color.green), R.drawable.fx, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RxActivityTool.skipActivity(getContext(), XpModuleAiActivity.class);
            }
        }));
        datas.add(new MainItem("设置", "软件设置与关于", getResources().getColor(R.color.fab), R.drawable.setting, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RxActivityTool.skipActivity(getContext(), SettingActivity.class);
            }
        }));

        adapter = new MainAdapter(R.layout.fragment_main_item, datas);
        recyclerView.setAdapter(adapter);
    }
}