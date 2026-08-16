package cn.mhook.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 玻璃拟态提示 Toast：暖米白圆角背景 + 主题色描边，替代 RxToast 蓝色样式。
 */
public final class GlassToast {

    private static final int TEXT_COLOR = 0xFF2D2A26;
    private static final int BG_COLOR = 0xFFF5F1E9;

    private GlassToast() {
    }

    public static void info(Context ctx, String msg) {
        show(ctx, msg, 0xFFFF8A3C);
    }

    public static void success(Context ctx, String msg) {
        show(ctx, msg, 0xFF16A34A);
    }

    public static void warning(Context ctx, String msg) {
        show(ctx, msg, 0xFFEA580C);
    }

    public static void error(Context ctx, String msg) {
        show(ctx, msg, 0xFFDC2626);
    }

    private static void show(Context ctx, String msg, int accent) {
        Context c = ctx == null ? null : ctx.getApplicationContext();
        if (c == null) {
            return;
        }
        int dp = (int) (c.getResources().getDisplayMetrics().density);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_COLOR);
        bg.setCornerRadius(dp * 14f);
        bg.setStroke(dp, accent);

        TextView tv = new TextView(c);
        tv.setText(msg);
        tv.setTextColor(TEXT_COLOR);
        tv.setTextSize(14);
        tv.setPadding(dp * 16, dp * 10, dp * 16, dp * 10);
        tv.setBackground(bg);

        Toast toast = new Toast(c);
        toast.setView(tv);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp * 90);
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.show();
    }
}
