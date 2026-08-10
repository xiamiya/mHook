package cn.mhook.activity;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.qmuiteam.qmui.util.QMUIDisplayHelper;
import com.qmuiteam.qmui.widget.QMUIProgressBar;
import com.qmuiteam.qmui.widget.tab.QMUITabBuilder;
import com.qmuiteam.qmui.widget.tab.QMUITabIndicator;
import com.qmuiteam.qmui.widget.tab.QMUITabSegment2;
import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.RxFileTool;
import com.tamsiree.rxkit.RxSPTool;
import com.tamsiree.rxkit.RxTimeTool;
import com.tamsiree.rxkit.view.RxToast;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import cn.mhook.BaseActivity;
import cn.mhook.activity.intro.IntroActivity;
import cn.mhook.fragment.MainFragment;
import cn.mhook.mData;
import cn.mhook.mhook.EventMessage;
import cn.mhook.mhook.R;

import static cn.mhook.mData.mDir;

public class MainActivity extends BaseActivity {

    private QMUITabSegment2 qmuiTabSegment;
    private List<Fragment> mFragments;
    private ViewPager2 viewPager;
    private Context context;
    private QMUIProgressBar qmuiProgressBar;
    private ImageView xpStatus;
    private Handler handler;
    BatteryManager batteryManager ;
    private TextView time;
    public static Activity activity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!RxSPTool.getBoolean(this,"noIntro")){
            Intent intent = new Intent(this, IntroActivity.class);
            this.startActivity(intent);
            finish();
        }
       /* getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE| WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);*/
        activity = this;
        setContentView(R.layout.activity_main);
        EventBus.getDefault().register(this);
        context = this;
        xpStatus = findViewById(R.id.xp_status);
        batteryManager =  (BatteryManager)getSystemService(BATTERY_SERVICE);
        if (xp()){
            xpStatus.setColorFilter(getResources().getColor(R.color.green));
        }
        handler = new Handler();
        qmuiProgressBar = findViewById(R.id.batter);
        qmuiProgressBar.setQMUIProgressBarTextGenerator(new QMUIProgressBar.QMUIProgressBarTextGenerator() {
            @Override
            public String generateText(QMUIProgressBar progressBar, int value, int maxValue) {
                return 100 * value / maxValue +"";
            }
        });
        qmuiProgressBar.setProgress(80);
        time = findViewById(R.id.time);
        handler.post(task);
        initViewPager();
        startAppInfo();
        // 启动时自动检查更新（有新版才弹窗，已忽略的版本不提示）
        handler.postDelayed(new Runnable() {
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
      //initTab();
    }



    private Runnable task =new Runnable() {
        public void run() {

            // TODOAuto-generated method stub
            handler.postDelayed(this,1*1000);//设置延迟时间，此处是5秒
            //需要执行的代码
            time.setText(RxTimeTool.getCurTimeString(new SimpleDateFormat("HH:mm:ss")));
            qmuiProgressBar.setProgress(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
        }
    };

    private void startAppInfo(){
        new Thread(new Runnable(){
            @Override
            public void run(){
                //处理事务
                mData.appInfos = RxAppTool.getAllAppsInfo(MainActivity.this);
            }
        }).start();
    }

    private Boolean xp(){
        return false;
    }

    private void initViewPager(){
        viewPager = findViewById(R.id.contentViewPager);
        mFragments = new ArrayList<>();
        mFragments.add(new MainFragment());



     //   mFragments.add(new MeFragment());
        viewPager.setAdapter(new  MyFragmentPagerAdapter(this,mFragments));
        viewPager.setOffscreenPageLimit(2);
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            /**
             * This method will be invoked when a new page becomes selected. Animation is not
             * necessarily complete.
             *
             * @param position Position index of the new selected page.
             */
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                switch (position){
                    case 0:
                        viewPager.setUserInputEnabled(true);
                        break;
                    case 1:
                        viewPager.setUserInputEnabled(false);
                        break;
                }
            }
        });
    }

    private void initTab(){
        qmuiTabSegment = findViewById(R.id.tabSegment);
        QMUITabBuilder tabBuilder = qmuiTabSegment.tabBuilder()
                .setGravity(Gravity.CENTER);
        tabBuilder.setDynamicChangeIconColor(true)
                .setTextSize(
                        QMUIDisplayHelper.sp2px(context, 13),
                        QMUIDisplayHelper.sp2px(context, 16))
                .setSelectedIconScale(1.1f);
        qmuiTabSegment.setIndicator(new QMUITabIndicator(
                QMUIDisplayHelper.dp2px(this, 2), false, false));
        qmuiTabSegment.addTab(tabBuilder
                .setNormalDrawable(ContextCompat.getDrawable(context, R.drawable.home))
                .build(this));
        qmuiTabSegment.addTab(tabBuilder
                .setNormalDrawable(ContextCompat.getDrawable(context, R.drawable.sq))
                .build(this));
      /*  qmuiTabSegment.addTab(tabBuilder
                .setNormalDrawable(ContextCompat.getDrawable(context, R.drawable.me))
                .build(this));*/
        qmuiTabSegment.setupWithViewPager(viewPager);
        qmuiTabSegment.notifyDataChanged();
        qmuiTabSegment.selectTab(0);
        qmuiTabSegment.setVisibility(View.GONE);
    }

    class MyFragmentPagerAdapter extends FragmentStateAdapter {

        private List<Fragment> mFragments;

        public MyFragmentPagerAdapter(@NonNull FragmentActivity fragmentActivity, List<Fragment> fragments) {
            super(fragmentActivity);
            this.mFragments = fragments;
        }
        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return mFragments.get(position);
        }
        @Override
        public int getItemCount() {
            return mFragments.size();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onReceiveMsg(final EventMessage message) {
        if (message.getType().equals("goHome")){
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    viewPager.setCurrentItem(0);
                }
            }, 0);
        }
    }


}
