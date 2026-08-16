package cn.mhook.fragment;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.viewholder.BaseViewHolder;

import java.util.List;

import cn.mhook.mhook.R;

public class GlassListAdapter extends BaseQuickAdapter<GlassItem, BaseViewHolder> {

    private boolean toggleOn = false;
    private ToggleListener toggleListener;

    public interface ToggleListener {
        /** @return true 表示切换成功，UI 才更新状态 */
        boolean onToggle(boolean desired);
    }

    public GlassListAdapter(int layoutResId, @Nullable List<GlassItem> data) {
        super(layoutResId, data);
    }

    public void setToggleState(boolean on) {
        toggleOn = on;
        notifyDataSetChanged();
    }

    public void setToggleListener(ToggleListener listener) {
        this.toggleListener = listener;
    }

    @Override
    protected void convert(final BaseViewHolder helper, final GlassItem item) {
        helper.setText(R.id.title, item.name)
                .setImageResource(R.id.icon, item.icon);

        TextView subtitle = helper.getView(R.id.subtitle);
        if (subtitle != null) {
            if (item.subtitle != null && !item.subtitle.isEmpty()) {
                subtitle.setText(item.subtitle);
                subtitle.setVisibility(View.VISIBLE);
            } else {
                subtitle.setVisibility(View.GONE);
            }
        }

        View iconBox = helper.getView(R.id.icon_box_sm);
        if (iconBox != null && iconBox.getBackground() != null) {
            iconBox.getBackground().setTint(alpha(item.accentColor, 51));
        }
        ImageView icon = helper.getView(R.id.icon);
        if (icon != null) {
            icon.setColorFilter(item.accentColor);
        }

        View toggle = helper.getView(R.id.toggle);
        ImageView arrow = helper.getView(R.id.arrow);
        if (item.type == GlassItem.TYPE_TOGGLE) {
            if (toggle != null) {
                toggle.setVisibility(View.VISIBLE);
                applyToggle(toggle, helper.getView(R.id.toggle_knob));
            }
            if (arrow != null) {
                arrow.setVisibility(View.GONE);
            }
        } else {
            if (toggle != null) {
                toggle.setVisibility(View.GONE);
            }
            if (arrow != null) {
                arrow.setVisibility(View.VISIBLE);
            }
        }

        View row = helper.getView(R.id.row);
        if (row != null) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (item.type == GlassItem.TYPE_TOGGLE) {
                        boolean desired = !toggleOn;
                        boolean success = toggleListener == null || toggleListener.onToggle(desired);
                        if (success) {
                            toggleOn = desired;
                            applyToggle(helper.getView(R.id.toggle), helper.getView(R.id.toggle_knob));
                        }
                    } else if (item.onClick != null) {
                        item.onClick.onClick(v);
                    }
                }
            });
        }
    }

    private void applyToggle(View toggle, View knob) {
        if (toggle == null) {
            return;
        }
        float travel = 20 * getContext().getResources().getDisplayMetrics().density;
        if (toggleOn) {
            toggle.getBackground().setTint(ContextCompat.getColor(getContext(), R.color.glass_accent_green));
            if (knob != null) {
                knob.animate().translationX(travel).setDuration(200).start();
            }
        } else {
            toggle.getBackground().setTint(0x80B4AA9B);
            if (knob != null) {
                knob.animate().translationX(0f).setDuration(200).start();
            }
        }
    }

    private int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
