package cn.mhook.activity;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import cn.mhook.mhook.R;

/**
 * 感谢开源项目页（玻璃拟态）
 */
public class ThanksActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_page);

        ((TextView) findViewById(R.id.page_title)).setText("感谢开源项目");
        ((TextView) findViewById(R.id.page_content)).setText(buildThanks());

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private String buildThanks() {
        return "本应用基于以下开源项目构建，感谢所有开源作者的贡献：\n\n"
                + "Xposed API（rovo89）\n"
                + "QMUI（Tencent）\n"
                + "RxTool（tamsiree）\n"
                + "BaseRecyclerViewAdapterHelper（CymChad）\n"
                + "FloatingSearchView（arimorty）\n"
                + "fastjson（alibaba）\n"
                + "EasyFloat（princekin-f）\n"
                + "EventBus（greenrobot）\n"
                + "XPopup（li-xiaojun）\n"
                + "Bugly（Tencent）\n"
                + "zip4j（srikanth-lingala）\n"
                + "RxShell（darken）\n"
                + "FreeReflection（tiann）\n"
                + "AndroidX 系列组件（Google）\n"
                + "Kotlin（JetBrains）\n"
                + "dexlib2（JesusFreke）\n"
                + "Guava（Google）\n"
                + "BlackDex（CodingGay）\n"
                + "BlackBox 虚拟沙箱引擎（top.niunaijun / BlackBoxReborn）\n"
                + "VirtualApp（Lody）虚拟容器奠基\n"
                + "Dobby inline Hook（jmpews）\n"
                + "玄星逆核（XuanXing/NieHe）逆向技能文档（AGPL-3.0）\n\n"
                + "以上项目的详细许可见各自开源仓库。";
    }
}
