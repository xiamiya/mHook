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
        TextView dirBtn = helper.getView(R.id.item_dir_btn);
        TextView zipBtn = helper.getView(R.id.item_zip_btn);
        if (on) {
            int count = 0, filled = 0;
            long size = 0;
            File[] files = dumpDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".dex")) {
                        count++;
                        size += f.length();
                        if (f.getName().contains("-filled")) filled++;
                    }
                }
            }
            String progress = "已动态分析 " + count + " 个 dex（" + formatSize(size) + "）"
                    + (filled > 0 ? "，补码快照 " + filled : "");
            String last = readLastEvent(new File(dumpDir, "dump_log.txt"));
            pkgView.setText("/data/mHook/" + pkg + "/dump/\n" + progress
                    + (last == null ? "" : "\n最近: " + last));
            gross.setText("已开启");
            gross.setTextColor(helper.itemView.getContext().getResources().getColor(R.color.green));
            dumpBtn.setVisibility(View.VISIBLE);
            dirBtn.setVisibility(View.VISIBLE);
            zipBtn.setVisibility(View.VISIBLE);
        } else {
            pkgView.setText(pkg);
            gross.setText("未开启");
            gross.setTextColor(helper.itemView.getContext().getResources().getColor(R.color.text));
            dumpBtn.setVisibility(View.GONE);
            dirBtn.setVisibility(View.GONE);
            zipBtn.setVisibility(View.GONE);
        }
    }

    /** 读取分析日志最后一行事件（+Ns tag: msg），带长度保护。 */
    private static String readLastEvent(File log) {
        try {
            if (!log.exists()) return null;
            byte[] b = new byte[(int) Math.min(log.length(), 4096)];
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r");
            try {
                raf.seek(Math.max(0, log.length() - b.length));
                raf.readFully(b);
            } finally {
                raf.close();
            }
            String tail = new String(b, "UTF-8");
            int i = tail.length() - 1;
            StringBuilder sb = new StringBuilder();
            int scans = 0;
            while (i >= 0 && scans < 8) {
                int end = tail.lastIndexOf('\n', i);
                if (end < 0) break;
                String line = tail.substring(end + 1, i + 1).trim();
                if (line.contains("]")) {
                    int k = line.indexOf(']');
                    String ev = line.substring(k + 1).trim();
                    if (!ev.isEmpty()) {
                        sb.insert(0, ev);
                        break;
                    }
                }
                i = end - 1;
                scans++;
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
