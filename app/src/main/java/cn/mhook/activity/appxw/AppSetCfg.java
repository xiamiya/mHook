package cn.mhook.activity.appxw;

import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.lzf.easyfloat.permission.PermissionUtils;
import com.qmuiteam.qmui.widget.grouplist.QMUICommonListItemView;
import com.qmuiteam.qmui.widget.grouplist.QMUIGroupListView;
import com.rengwuxian.materialedittext.MaterialEditText;
import com.tamsiree.rxkit.RxAppTool;
import com.tamsiree.rxkit.view.RxToast;
import cn.mhook.BaseActivity;
import cn.mhook.floatprint.FloatActivity;
import cn.mhook.mhook.R;
import static cn.mhook.mhook.contentprovider.appCfg.setAppCfg;

public class AppSetCfg extends BaseActivity {

    private Handler handler;
    QMUIGroupListView mGroupListView;
    String pkg;
    private QMUICommonListItemView probeItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_xw_set);
        handler = new Handler();
        mGroupListView = findViewById(R.id.groupListView);
        pkg = getIntent().getStringExtra("pkg");
        new Thread(new Runnable(){
            @Override
            public void run(){
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initView();
                    }
                }, 0);
            }
        }).start();
    }

    private void initView(){
        QMUICommonListItemView appName = mGroupListView.createItemView("应用 (点击启动)");
        appName.setDetailText(RxAppTool.getAppName(this,pkg));
        appName.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_NONE);
        QMUIGroupListView.newSection(this)
                .setTitle("基本")
                .addItemView(appName, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (RxAppTool.isInstallApp(AppSetCfg.this,pkg)){
                            new FloatActivity(AppSetCfg.this,AppSetCfg.this);
                            if (PermissionUtils.checkPermission(AppSetCfg.this)){
                                RxAppTool.launchApp(AppSetCfg.this,pkg);
                            }
                        }else {
                            RxToast.error("未安装该应用");
                        }
                    }
                })
                .addItemView(getItem("总开关","appCfgEnable"),getOnClick())
                .addTo(mGroupListView);
        QMUIGroupListView.newSection(this)
                .setTitle("UI")
                .addItemView(getItem("对话框","dialog"),getOnClick())
                .addItemView(getItem("Toast","toast"),getOnClick())
                .addItemView(getItem("弹窗","show_view"),getOnClick())
                .addItemView(getItem("界面跳转","activity_goto"),getOnClick())
                .addItemView(getItem("界面关闭","activity_finish"),getOnClick())
                .addItemView(getItem("点击事件","button"),getOnClick())
                .addTo(mGroupListView);
        QMUIGroupListView.newSection(this)
                .setTitle("数据")
                .addItemView(getItem("访问存储操作","file"),getOnClick())
                .addItemView(getItem("JSON添加","putJson"),getOnClick())/*
                .addItemView(getItem("SharedPreference操作","sp"),getOnClick())
                .addItemView(getItem("SQLite读取操作","sql_read"),getOnClick())
                .addItemView(getItem("SQLite写入操作","sql_write"),getOnClick())
                .addItemView(getItem("SQLite删除操作","sql_del"),getOnClick())
                .addItemView(getItem("SQLite更新操作","sql_update"),getOnClick())*/
                .addTo(mGroupListView);
                /*
        QMUIGroupListView.newSection(this)
                .setTitle("敏感")
                .addItemView(getItem("读取剪切板","read_clip"),getOnClick())
                .addItemView(getItem("写入剪切板","write_clip"),getOnClick())
                .addItemView(getItem("获取手机信息","read_phone_info"),getOnClick())
                .addItemView(getItem("获取位置信息","read_pos"),getOnClick())
                .addItemView(getItem("读取短信","read_sms"),getOnClick())
                .addItemView(getItem("发送短信","send_sms"),getOnClick())
                .addTo(mGroupListView);*/
        QMUIGroupListView.newSection(this)
                .setTitle("网络")
                .addItemView(getItem("代理检测及屏蔽","cProperty"),getOnClick())
               /* .addItemView(getItem("创建VPN","new_vpn"),getOnClick())
                 .addItemView(getItem("Okhttp3","okhttp3"),getOnClick())
                 .addItemView(getItem("UDP发送","send_udp"),getOnClick())
                 .addItemView(getItem("UDP监听","bind_udp"),getOnClick())
                 .addItemView(getItem("TCP发送","send_tcp"),getOnClick())
                 .addItemView(getItem("TCP监听","bind_tcp"),getOnClick())
                 .addItemView(getItem("WebView访问","webview"),getOnClick())*/
                .addTo(mGroupListView);
        QMUIGroupListView.newSection(this)
                .setTitle("自定义")
                .addItemView(getProbeItem(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMethodReturnDialog();
                    }
                })
                .addTo(mGroupListView);/*
        QMUIGroupListView.newSection(this)
                .setTitle("加解密")
                .addItemView(getItem("常用算法","crypto"),getOnClick())
                .addTo(mGroupListView);
        QMUIGroupListView.newSection(this)
                .setTitle("Xposed相关")
                .addItemView(getItem("被hook检测", "beHook"),getHookClick())
                .addTo(mGroupListView);*/
    }

    /** 方法返回值探测条目：显示已配置的方法数量。 */
    private QMUICommonListItemView getProbeItem(){
        QMUICommonListItemView item = mGroupListView.createItemView("方法返回值探测");
        item.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_NONE);
        probeItem = item;
        refreshProbeDetail();
        return item;
    }

    private void refreshProbeDetail(){
        if (probeItem == null) return;
        JSONArray arr = getMethodReturnConfig();
        probeItem.setDetailText(arr == null || arr.isEmpty() ? "未配置" : ("已配置 " + arr.size() + " 个方法"));
        probeItem.getDetailTextView().setTextColor(getResources().getColor(
                arr == null || arr.isEmpty() ? R.color.text : R.color.green));
    }

    private JSONArray getMethodReturnConfig(){
        try {
            JSONObject cfg = cn.mhook.mhook.contentprovider.appCfg.getAppCfg(pkg);
            if (cfg != null && cfg.getJSONArray("methodReturn") != null) {
                return cfg.getJSONArray("methodReturn");
            }
        } catch (Throwable ignored) {
        }
        return new JSONArray();
    }

    /** 配置类名/方法名：hook 后打印方法返回值（毫秒/秒时间戳、布尔真假等）。 */
    private void showMethodReturnDialog(){
        final JSONArray data = getMethodReturnConfig();
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        container.setPadding(pad, pad / 2, pad, 0);

        final MaterialEditText cls = new MaterialEditText(this);
        cls.setHint("全限定类名");
        cls.setHelperText("如 com.example.Foo");
        container.addView(cls);

        final MaterialEditText mth = new MaterialEditText(this);
        mth.setHint("方法名");
        mth.setHelperText("如 getTime / isVip");
        container.addView(mth);

        LinearLayout ops = new LinearLayout(this);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        TextView addBtn = new TextView(this);
        addBtn.setText("+ 添加");
        addBtn.setTextColor(getResources().getColor(R.color.blue));
        addBtn.setTextSize(15);
        addBtn.setPadding(0, pad / 2, pad * 2, pad / 2);
        TextView clearBtn = new TextView(this);
        clearBtn.setText("清空");
        clearBtn.setTextColor(getResources().getColor(R.color.red));
        clearBtn.setTextSize(15);
        clearBtn.setPadding(0, pad / 2, 0, pad / 2);
        ops.addView(addBtn);
        ops.addView(clearBtn);
        container.addView(ops);

        final TextView list = new TextView(this);
        list.setTextSize(13);
        list.setTextColor(getResources().getColor(R.color.text));
        list.setText(formatProbeList(data));
        container.addView(list);

        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String c = cls.getText().toString().trim();
                String m = mth.getText().toString().trim();
                if (c.isEmpty() || m.isEmpty()){
                    RxToast.warning("请填写类名和方法名");
                    return;
                }
                JSONObject h = new JSONObject(true);
                h.put("className", c);
                h.put("methodName", m);
                data.add(h);
                cls.setText("");
                mth.setText("");
                list.setText(formatProbeList(data));
            }
        });
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                data.clear();
                list.setText(formatProbeList(data));
            }
        });

        new android.app.AlertDialog.Builder(this)
                .setTitle("方法返回值探测")
                .setMessage("hook 后每次调用都会把返回值记录到行为日志（含时间戳毫秒/秒判断）")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        setAppCfg(pkg, "methodReturn", data);
                        refreshProbeDetail();
                        RxToast.success("已保存，重启目标应用后生效");
                    }
                })
                .show();
    }

    private String formatProbeList(JSONArray data){
        if (data == null || data.isEmpty()) return "（暂无）";
        StringBuilder sb = new StringBuilder();
        for (Object o : data){
            JSONObject h = (JSONObject) o;
            sb.append(h.getString("className")).append("#").append(h.getString("methodName")).append('\n');
        }
        return sb.toString();
    }

    private void setHookEnable(String key,Boolean enable){

    }

    private View.OnClickListener getHookClick(){
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QMUICommonListItemView q =  (QMUICommonListItemView)v;
                setAppCfg(pkg,q.getTag().toString(),!getEnable(q.getTag().toString()));
                q.setDetailText(getEnable(q.getTag().toString())?"已开启":"未开启");
                q.getDetailTextView().setTextColor(getResources().getColor(getEnable(q.getTag().toString())?R.color.green:R.color.qmui_config_color_75_pure_black));
                if (getEnable(q.getTag().toString())) {
                    setHookEnable(q.getTag().toString(), true);
                } else {
                    setHookEnable(q.getTag().toString(), false);
                }
            }
        };
    }

    private View.OnClickListener getOnClick(){
        return new View.OnClickListener(){

            /**
             * Called when a view has been clicked.
             *
             * @param v The view that was clicked.
             */
            @Override
            public void onClick(View v) {
                QMUICommonListItemView q =  (QMUICommonListItemView)v;
                setAppCfg(pkg,q.getTag().toString(),!getEnable(q.getTag().toString()));
                q.setDetailText(getEnable(q.getTag().toString())?"已开启":"未开启");
                q.getDetailTextView().setTextColor(getResources().getColor(getEnable(q.getTag().toString())?R.color.green:R.color.qmui_config_color_75_pure_black));
            }
        };
    }

    private QMUICommonListItemView getItem(String name,String tag){
        QMUICommonListItemView statusCheck = mGroupListView.createItemView(name);
        statusCheck.setTag(tag);
        statusCheck.setDetailText(getEnable(tag)?"已开启":"未开启");
        statusCheck.getDetailTextView().setTextColor(getResources().getColor(getEnable(tag)?R.color.green:R.color.qmui_config_color_75_pure_black));
        statusCheck.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_NONE);
        return statusCheck;
    }

    public  JSONObject getAppCfg(){
        return cn.mhook.mhook.contentprovider.appCfg.getAppCfg(pkg);
    }

    public  Boolean getEnable(String key){
        if (getAppCfg()!=null&&getAppCfg().containsKey(key)&&getAppCfg().getBoolean(key)){
            return true;
        }
        return false;
    }
}
