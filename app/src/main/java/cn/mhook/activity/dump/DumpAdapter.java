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
        super(R.layout.activity_dump_item, data);
    }

    @Override
    protected void convert(BaseViewHolder helper, SelectAppItem item) {
        String pkg = item.getPkg();
        File dumpDir = new File(mDir + pkg + "/dump");
        boolean on = dumpDir.exists();
        helper.setText(R.id.item_name_tv, item.getAppName())
                .setText(R.id.item_ver, item.getVer());
        TextView pkgView = helper.getView(R.id.item_pkg);
        TextView gross = helper.getView(R.id.item_gross);
        TextView dumpBtn = helper.getView(R.id.item_dump_btn);
        if (on) {
            File[] files = dumpDir.listFiles();
            int count = 0;
            long size = 0;
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".dex")) {
                        count++;
                        size += f.length();
                    }
                }
            }
            pkgView.setText("/data/mHook/" + pkg + "/dump/\n已脱壳 " + count + " 个 dex（" + formatSize(size) + "）");
            gross.setText("已开启");
            gross.setTextColor(helper.itemView.getContext().getResources().getColor(R.color.green));
            dumpBtn.setVisibility(View.VISIBLE);
        } else {
            pkgView.setText(pkg);
            gross.setText("未开启");
            gross.setTextColor(helper.itemView.getContext().getResources().getColor(R.color.text));
            dumpBtn.setVisibility(View.GONE);
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
