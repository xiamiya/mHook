package cn.mhook.activity;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import com.alibaba.fastjson.JSONObject;
import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;
import com.qmuiteam.qmui.widget.grouplist.QMUICommonListItemView;
import com.qmuiteam.qmui.widget.grouplist.QMUIGroupListView;
import com.tamsiree.rxkit.view.RxToast;

import java.io.File;
import java.io.InputStream;

import cn.mhook.App;
import cn.mhook.BaseActivity;
import cn.mhook.activity.intro.IntroActivity;
import cn.mhook.mhook.R;
import cn.mhook.update.UpdateManager;
import eu.darken.rxshell.cmd.Cmd;
import cn.mhook.msu.su;

import static cn.mhook.mData.mDir;


public class SettingActivity extends BaseActivity {

    QMUIGroupListView mGroupListView;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);
        initGroupList();
    }

    private void initGroupList(){
        mGroupListView = findViewById(R.id.groupListView);
        QMUIGroupListView.newSection(this)
                .setTitle("设置")
                .addItemView(getListItem("调试模式","调试日志存放在mhook包名路径"), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        App.setEnable("debug",!App.enable("debug"));
                        RxToast.info(App.enable("debug")?"已启用调试":"已禁用调试");
                    }
                })
                .addTo(mGroupListView);
        QMUIGroupListView.newSection(this)
                .setTitle("关于")
                .addItemView(getListItem("用户协议","重新阅读用户协议与使用需知"), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(SettingActivity.this, IntroActivity.class);
                        SettingActivity.this.startActivity(intent);
                        finish();
                    }
                })
                .addItemView(getListItem("感谢开源项目", "本应用基于以下开源项目构建"), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new QMUIDialog.MessageDialogBuilder(SettingActivity.this)
                                .setTitle("感谢开源项目")
                                .setMessage("本应用基于以下开源项目构建，感谢所有开源作者的贡献：\n\n"
                                        + "Xposed API（rovo89）\n"
                                        + "QMUI（Tencent）\n"
                                        + "RxTool（tamsiree）\n"
                                        + "BoomMenu（Nightonke）\n"
                                        + "BaseRecyclerViewAdapterHelper（CymChad）\n"
                                        + "FloatingSearchView（arimorty）\n"
                                        + "MaterialEditText（rengwuxian）\n"
                                        + "fastjson（alibaba）\n"
                                        + "EasyFloat（princekin-f）\n"
                                        + "EventBus（greenrobot）\n"
                                        + "XPopup（li-xiaojun）\n"
                                        + "Bugly（Tencent）\n"
                                        + "material-intro-screen（DreierF）\n"
                                        + "zip4j（srikanth-lingala）\n"
                                        + "AndroidDonate（didikee）\n"
                                        + "RxShell（darken）\n"
                                        + "FreeReflection（tiann）\n"
                                        + "AndroidX 系列组件（Google）\n"
                                        + "dexlib2（JesusFreke）\n"
                                        + "Guava（Google）\n"
                                        + "BlackDex（CodingGay）\n"
                                        + "BlackBox 虚拟沙箱引擎（top.niunaijun / BlackBoxReborn）\n"
                                        + "VirtualApp（Lody）虚拟容器奠基\n"
                                        + "Dobby inline Hook（jmpews）\n"
                                        + "FreeReflection 隐藏API绕过（tiann）\n"
                                        + "玄星逆核（XuanXing/NieHe）逆向技能文档（AGPL-3.0）\n\n"
                                        + "以上项目的详细许可见各自开源仓库。")
                                .setSkinManager(QMUISkinManager.defaultInstance(SettingActivity.this))
                                .addAction("知道了", new QMUIDialogAction.ActionListener() {
                                    @Override
                                    public void onClick(QMUIDialog dialog, int index) {
                                        dialog.dismiss();
                                    }
                                })
                                .create().show();
                    }
                })
                .addItemView(getListItem("打赏支持", "请夏糜吃鸡腿"), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(SettingActivity.this, DonateActivity.class));
                    }
                })
                .addItemView(getListItem("检查更新", "当前版本 v" + currentVersion()), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        checkUpdate();
                    }
                })
                .addTo(mGroupListView);

    }

    private void checkUpdate() {
        UpdateManager.checkAndShow(this, true);
    }

    private String currentVersion() {
        return UpdateManager.currentVersion(this);
    }

    private Handler handler = new Handler(Looper.getMainLooper());

    private QMUICommonListItemView getListItem(String title,String detail){
        QMUICommonListItemView statusCheck = mGroupListView.createItemView(title);
        statusCheck.setOrientation(QMUICommonListItemView.VERTICAL);
        statusCheck.setDetailText(detail);
        statusCheck.setAccessoryType(QMUICommonListItemView.ACCESSORY_TYPE_NONE);
        return statusCheck;
    }
}
