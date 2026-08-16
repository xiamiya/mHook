package cn.mhook.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.RxSPTool;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import cn.mhook.fragment.HomePageBuilder;
import cn.mhook.mData;
import cn.mhook.mhook.EventMessage;
import cn.mhook.mhook.R;

public class MainActivity extends Activity {

    private final int[] tabIds = {R.id.tab_0, R.id.tab_1, R.id.tab_2, R.id.tab_3};
    private final int[] tabIconIds = {R.id.tab_icon_0, R.id.tab_icon_1, R.id.tab_icon_2, R.id.tab_icon_3};
    private final int[] tabLabelIds = {R.id.tab_label_0, R.id.tab_label_1, R.id.tab_label_2, R.id.tab_label_3};
    private final List<View> pages = new ArrayList<>();
    public static Activity activity;
    private int currentPage = 0;
    private ViewPager2 viewPager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activity = this;
        getWindow().setBackgroundDrawableResource(R.drawable.bg_home_gradient);
        getWindow().setNavigationBarColor(getResources().getColor(R.color.glass_bg_bottom));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);
        EventBus.getDefault().register(this);

        viewPager = findViewById(R.id.page_host);
        for (int i = 0; i < 4; i++) {
            View page = HomePageBuilder.build(this, viewPager, i);
            pages.add(page);
        }
        viewPager.setAdapter(new PageAdapter());
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentPage = position;
                updateTabUi(position);
            }
        });
        FrameLayout tabPill = findViewById(R.id.tab_pill);
        if (tabPill != null) {
            cn.mhook.widget.GlassShadows.apply(tabPill, 28f, 8f);
        }
        initTabs();
        viewPager.setCurrentItem(0, false);
        startAppInfo();
        // 启动时自动检查更新（有新版才弹窗，已忽略的版本不提示）
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!MainActivity.this.isFinishing() && !MainActivity.this.isDestroyed()) {
                        cn.mhook.update.UpdateManager.checkAndShow(MainActivity.this, false);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, 2500);
    }

    private void initTabs() {
        for (int i = 0; i < tabIds.length; i++) {
            final LinearLayout tab = findViewById(tabIds[i]);
            final int index = i;
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectPage(index);
                }
            });
        }
    }

    private void selectPage(int index) {
        if (index < 0 || index >= pages.size()) {
            return;
        }
        viewPager.setCurrentItem(index, true);
    }

    private void updateTabUi(int index) {
        for (int i = 0; i < tabIds.length; i++) {
            boolean selected = i == index;
            LinearLayout tab = findViewById(tabIds[i]);
            tab.setBackgroundResource(selected ? R.drawable.bg_tab_active : 0);
            ImageView icon = findViewById(tabIconIds[i]);
            TextView label = findViewById(tabLabelIds[i]);
            int color = selected ? R.color.glass_text_primary : R.color.glass_text_secondary;
            if (icon != null) {
                icon.setColorFilter(getResources().getColor(color));
            }
            if (label != null) {
                label.setTextColor(getResources().getColor(color));
            }
        }
    }

    private void startAppInfo() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                mData.appInfos = RxAppTool.getAllAppsInfo(MainActivity.this);
            }
        }).start();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onReceiveMsg(final EventMessage message) {
        if (message.getType().equals("goHome")) {
            selectPage(0);
        }
    }

    /** 主页 4 页适配器：每页即 HomePageBuilder 构建的 View。 */
    private class PageAdapter extends RecyclerView.Adapter<PageHolder> {
        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout container = new FrameLayout(MainActivity.this);
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new PageHolder(container);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            View page = pages.get(position);
            if (page.getParent() != null) {
                ((ViewGroup) page.getParent()).removeView(page);
            }
            holder.container.removeAllViews();
            holder.container.addView(page);
        }

        @Override
        public int getItemCount() {
            return pages.size();
        }
    }

    private static class PageHolder extends RecyclerView.ViewHolder {
        final FrameLayout container;

        PageHolder(FrameLayout container) {
            super(container);
            this.container = container;
        }
    }
}
