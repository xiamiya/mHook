package cn.mhook.activity.intro;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.annotation.FloatRange;
import androidx.annotation.Nullable;
import com.tamsiree.rxkit.RxSPTool;
import cn.mhook.activity.MainActivity;
import cn.mhook.mhook.R;
import io.github.dreierf.materialintroscreen.MaterialIntroActivity;
import io.github.dreierf.materialintroscreen.MessageButtonBehaviour;
import io.github.dreierf.materialintroscreen.SlideFragmentBuilder;
import io.github.dreierf.materialintroscreen.animations.IViewTranslation;

public class IntroActivity extends MaterialIntroActivity {
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enableLastSlideAlphaExitTransition(true);
        getBackButtonTranslationWrapper()
                .setEnterTranslation(new IViewTranslation() {
                    @Override
                    public void translate(View view, @FloatRange(from = 0, to = 1.0) float percentage) {
                        view.setAlpha(percentage);
                    }
                });

        addSlide(new SlideFragmentBuilder()
                        .backgroundColor(R.color.first_slide_background)
                        .buttonsColor(R.color.first_slide_buttons)
                        .image(R.mipmap.img_office)
                        .title("mHook可以做什么")
                        .description("安卓应用行为检测分析，告别应用恶意行为，防止隐私泄露，第一时间发现应用程序异常行为及可疑操作。")
                        .build());

        addSlide(new CustomSlide());

        addSlide(new Statement());


        addSlide(new SlideFragmentBuilder()
                        .backgroundColor(R.color.third_slide_background)
                        .buttonsColor(R.color.third_slide_buttons)
                        .neededPermissions(new String[]{Manifest.permission.INTERNET})
                        .image(R.mipmap.img_equipment)
                        .title("程序正常使用需要一些权限,点击下方按钮申请如下权限: ")
                        .description("存储权限,电话权限")
                        .build(),
                new MessageButtonBehaviour(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMessage("继续操作吧!");
                    }
                }, "已取得权限"));
    }

    @Override
    public void onFinish() {
        RxSPTool.putBoolean(this,"noIntro",true);
        Intent intent = new Intent(this, MainActivity.class);
        this.startActivity(intent);
        super.onFinish();
    }
}

