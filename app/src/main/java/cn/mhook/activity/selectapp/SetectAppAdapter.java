package cn.mhook.activity.selectapp;

import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.viewholder.BaseViewHolder;

import java.util.List;

import cn.mhook.mhook.R;

public class SetectAppAdapter extends BaseQuickAdapter<SelectAppItem, BaseViewHolder> {

    private static final int TYPE_HEADER = 100;

    public SetectAppAdapter(@Nullable List<SelectAppItem> data) {
        super(R.layout.activity_select_item, data);
    }

    public SetectAppAdapter(@LayoutRes int layoutResId, @Nullable List<SelectAppItem> data) {
        super(layoutResId, data);
    }

    @Override
    protected int getDefItemViewType(int position) {
        return getData().get(position).isHeader() ? TYPE_HEADER : 0;
    }

    @Override
    protected BaseViewHolder onCreateDefViewHolder(ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            return createBaseViewHolder(parent, R.layout.select_app_section);
        }
        return super.onCreateDefViewHolder(parent, viewType);
    }

    @Override
    protected void convert(final BaseViewHolder helper, final SelectAppItem item) {
        if (item.isHeader()) {
            helper.setText(R.id.section_title_tv, item.getHeaderText());
            return;
        }
        helper.setText(R.id.item_name_tv,item.getAppName())
                .setText(R.id.item_ver,item.getVer())
                .setText(R.id.item_pkg,item.getPkg());
        if (SelectActivity.ret.contains(item.getPkg())){
            helper.getView(R.id.appInfoItem).setBackgroundResource(R.drawable.bg_glass_card_selected);
        }else {
            helper.getView(R.id.appInfoItem).setBackgroundResource(R.drawable.bg_glass_card);
        }
    }
}
