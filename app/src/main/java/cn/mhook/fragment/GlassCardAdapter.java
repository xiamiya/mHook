package cn.mhook.fragment;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.viewholder.BaseViewHolder;

import java.util.List;

import cn.mhook.mhook.R;
import cn.mhook.widget.GlassShadows;

public class GlassCardAdapter extends BaseQuickAdapter<GlassItem, BaseViewHolder> {

    public GlassCardAdapter(int layoutResId, @Nullable List<GlassItem> data) {
        super(layoutResId, data);
    }

    @Override
    protected void convert(final BaseViewHolder helper, final GlassItem item) {
        helper.setText(R.id.title, item.name)
                .setText(R.id.subtitle, item.subtitle)
                .setImageResource(R.id.icon, item.icon);

        View card = helper.getView(R.id.card);
        if (card != null) {
            GlassShadows.apply(card, 24f, 4f);
        }

        View iconBox = helper.getView(R.id.icon_box);
        if (iconBox != null && iconBox.getBackground() != null) {
            iconBox.getBackground().setTint(alpha(item.accentColor, 51));
        }
        ImageView icon = helper.getView(R.id.icon);
        if (icon != null) {
            icon.setColorFilter(item.accentColor);
        }

        TextView badge = helper.getView(R.id.badge);
        if (badge != null) {
            if (item.badge != null && !item.badge.isEmpty()) {
                int accent = item.badgeColor == 0 ? ContextCompat.getColor(getContext(), R.color.glass_accent_blue) : item.badgeColor;
                badge.setText(item.badge);
                badge.setTextColor(accent);
                if (badge.getBackground() != null) {
                    badge.getBackground().setTint(alpha(accent, 26));
                }
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
        }

        if (item.onClick != null) {
            helper.getView(R.id.card).setOnClickListener(item.onClick);
        }
    }

    private int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
