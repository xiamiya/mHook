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
                + "【核心框架】\n"
                + "BlackBox 虚拟沙箱引擎（top.niunaijun / BlackBoxReborn）\n"
                + "VirtualApp（Lody）虚拟容器奠基\n"
                + "Xposed API（rovo89）\n"
                + "hiddenApiBypass（LSPosed）\n"
                + "FreeReflection（tiann）\n\n"
                + "【过签 / 加固处理】\n"
                + "SRPatch-X 签名强度检测引擎\n"
                + "NPatch（7723mod，基于 LSPatch）\n"
                + "ZenPatch 原生 IO 重定向思路\n"
                + "Solab DPatch（pandora AppFactory）\n"
                + "ApkDataMultiplexing（L-JINBIN）过签包数据复用\n"
                + "ApkCheckPack 加固特征库\n\n"
                + "【脱壳 / 动态分析参考】\n"
                + "BlackDex（CodingGay）\n"
                + "Youpk（ROM 级脱壳 + dexfixer）\n"
                + "FART（hanbinglengyue）\n"
                + "frida（oleavr）\n"
                + "unicorn / unidbg（模拟执行）\n\n"
                + "【Native / Hook】\n"
                + "Dobby inline Hook（jmpews）\n"
                + "shadow3aaa/dobby-api\n\n"
                + "【Dex / 文件处理】\n"
                + "dexlib2 / smali / baksmali（JesusFreke）\n"
                + "apk-parser（hsiafan）\n"
                + "zip4j（srikanth-lingala）\n"
                + "Guava（Google）\n"
                + "Apache Commons（Codec / Collections / IO）\n"
                + "antlr-runtime、slf4j、XZ Utils\n\n"
                + "【UI / 基础库】\n"
                + "QMUI（Tencent）\n"
                + "RxTool（tamsiree）\n"
                + "BaseRecyclerViewAdapterHelper（CymChad）\n"
                + "FloatingSearchView（arimorty）\n"
                + "BoomMenu（nightonke）\n"
                + "MaterialEditText（rengwuxian）\n"
                + "Material Intro Screen（dreierf）\n"
                + "XPopup（li-xiaojun）\n"
                + "EasyFloat（princekin-f）\n"
                + "AndroidDonate（didikee）\n"
                + "EventBus（greenrobot）\n"
                + "fastjson（alibaba）\n"
                + "RxShell（darken）\n"
                + "Bugly（Tencent）\n"
                + "AndroidX 系列组件（Google）\n"
                + "Kotlin（JetBrains）\n\n"
                + "【知识与文档】\n"
                + "玄星逆核（XuanXing/NieHe）逆向技能文档（AGPL-3.0）\n\n"
                + "以上项目的详细许可见各自开源仓库。";
    }
}
