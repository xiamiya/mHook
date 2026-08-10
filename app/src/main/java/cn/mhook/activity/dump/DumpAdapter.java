package cn.mhook.activity.dump;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.viewholder.BaseViewHolder;

import java.io.File;
import java.util.List;

import cn.mhook.activity.selectapp.SelectAppItem;
import cn.mhook.mhook.R;

import static cn.mhook.mData.mDir;

public class DumpAdapter extends BaseQuickAdapter<SelectAppItem, BaseViewHolder> {

    public DumpAdapter(@Nullable List<SelectAppItem> data) {
        super(R.layout.activity_xw_item, data);
    }

    @Override
    protected void convert(BaseViewHolder helper, SelectAppItem item) {
        helper.setText(R.id.item_name_tv, item.getAppName())
                .setText(R.id.item_ver, item.getVer());
        TextView pkgView = helper.getView(R.id.item_pkg);
        boolean on = new File(mDir + item.getPkg() + "/dump").exists();
        if (on) {
            pkgView.setText("dex 保存到：\n/data/mHook/" + item.getPkg() + "/dump/");
            pkgView.setSingleLine(false);
        } else {
            pkgView.setText(item.getPkg());
            pkgView.setSingleLine(true);
        }
        TextView gross = helper.getView(R.id.item_gross);
        gross.setVisibility(View.VISIBLE);
        gross.setText(on ? "已开启" : "未开启");
        gross.setTextColor(helper.itemView.getContext().getResources().getColor(on ? R.color.green : R.color.text));
    }
}
