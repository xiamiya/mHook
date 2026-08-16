package cn.mhook.widget;

import android.graphics.Outline;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;

/**
 * 柔和微阴影工具：圆角轮廓 + 暖色投影，避免 CardView 灰圈。
 */
public final class GlassShadows {

    // 暖棕微阴影（对应 HTML rgba(60,40,20,0.08)）
    public static final int WARM_SHADOW = 0x143C2814;

    private GlassShadows() {
    }

    public static void apply(View view, float radiusDp, float elevationDp) {
        apply(view, radiusDp, elevationDp, WARM_SHADOW);
    }

    public static void apply(View view, float radiusDp, float elevationDp, int shadowColor) {
        float radius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, radiusDp, view.getResources().getDisplayMetrics());
        view.setElevation(elevationDp);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        view.setClipToOutline(false);
        if (Build.VERSION.SDK_INT >= 28) {
            view.setOutlineAmbientShadowColor(shadowColor);
            view.setOutlineSpotShadowColor(shadowColor);
        }
    }
}
