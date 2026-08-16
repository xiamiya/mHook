package cn.mhook.floatprint;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.lzf.easyfloat.EasyFloat;
import com.lzf.easyfloat.enums.ShowPattern;
import com.lzf.easyfloat.enums.SidePattern;
import com.lzf.easyfloat.interfaces.OnFloatCallbacks;
import com.lzf.easyfloat.interfaces.OnInvokeView;
import com.lzf.easyfloat.permission.PermissionUtils;
import com.tamsiree.rxkit.RxFileTool;
import com.tamsiree.rxkit.RxTimeTool;
import com.tamsiree.rxkit.RxTool;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import cn.mhook.floatprint.log.FloatPrintLogAdapter;
import cn.mhook.floatprint.log.FloatPrintLogItem;
import cn.mhook.mhook.R;
import cn.mhook.mhook.contentprovider.PrintData;
import cn.mhook.widget.GlassToast;

public class FloatActivity {

    private Context context;
    private Activity activity;
    private android.os.Handler handler = new android.os.Handler();
    private List<FloatPrintLogItem> datas = new ArrayList<>();
    private FloatPrintLogAdapter adapter;
    private RecyclerView recyclerView;
    private int endId = 0;
    private Boolean stop = false;
    private boolean canshow = false;

    public FloatActivity(Activity activity, Context context) {
        this.activity = activity;
        this.context = context;
        initFloat();
    }

    private void initFloat() {
        if (PermissionUtils.checkPermission(context)) {
            showFloat();
        } else {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("提示")
                    .setMessage("使用调试功能需要您授予悬浮窗权限")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            showFloat();
                        }
                    })
                    .show();
        }
    }

    private void showFloat() {
        OnInvokeView onInvokeView = new OnInvokeView() {
            @Override
            public void invoke(final View view) {
                initLogPanel(view);
                initTabs(view);
                initDragAndResize(view);

                view.findViewById(R.id.reduce).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        canshow = false;
                        EasyFloat.hideAppFloat("print");
                        initIcon();
                    }
                });
                view.findViewById(R.id.closeFloat).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        EasyFloat.dismissAppFloat("print");
                        EasyFloat.dismissAppFloat("icon");
                    }
                });
            }
        };
        EasyFloat.with(activity)
                .setLayout(R.layout.float_layout, onInvokeView)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setSidePattern(SidePattern.RESULT_HORIZONTAL)
                .setMatchParent(true, false)
                .setTag("print")
                .setDragEnable(false)
                .registerCallbacks(new OnFloatCallbacks() {
                    @Override
                    public void createdResult(boolean isCreated, @Nullable String msg, @Nullable View view) {
                    }

                    @Override
                    public void show(View view) {
                    }

                    @Override
                    public void hide(View view) {
                    }

                    @Override
                    public void dismiss() {
                        handler.removeCallbacks(task);
                    }

                    @Override
                    public void touchEvent(View view, MotionEvent event) {
                    }

                    @Override
                    public void drag(View view, MotionEvent event) {
                    }

                    @Override
                    public void dragEnd(View view) {
                        EasyFloat.appFloatDragEnable(false, "print");
                    }
                })
                .show();
        EasyFloat.showAppFloat("print");
    }

    /** 调试日志面板：列表 + 清空/筛选/暂停/导出。 */
    private void initLogPanel(View view) {
        recyclerView = view.findViewById(R.id.config_recycler_view);
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(RecyclerView.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);
        adapter = new FloatPrintLogAdapter(R.layout.float_print_item, datas);
        recyclerView.setAdapter(adapter);
        PrintData.delAll(RxTool.getContext());
        handler.post(task);

        view.findViewById(R.id.cleanAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                datas.clear();
                adapter.notifyDataSetChanged();
            }
        });
        view.findViewById(R.id.stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stop = !stop;
                ((TextView) view.findViewById(R.id.stop)).setText(stop ? "继续" : "暂停");
            }
        });
        view.findViewById(R.id.sx).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GlassToast.warning(context, "无需筛选");
            }
        });
        view.findViewById(R.id.dc).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONObject j = PrintData.getData(RxTool.getContext(), 0);
                if (j == null || j.isEmpty()) {
                    return;
                }
                String s = JSONObject.toJSONString(j, true);
                String path = "/sdcard/cn.mhook.mhook/OtherAppLog/" + RxTimeTool.getCurTimeString(new SimpleDateFormat("HH:mm:ss")) + ".json";
                RxFileTool.writeFileFromString(path, s, false);
                GlassToast.success(context, "已导出到 " + path);
            }
        });
    }

    private void initTabs(final View view) {
        final TextView tabDebug = view.findViewById(R.id.tab_debug);
        final TextView tabOther = view.findViewById(R.id.tab_other);
        final View logPanel = view.findViewById(R.id.log_panel);
        final View settingPanel = view.findViewById(R.id.setting_panel);
        tabDebug.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logPanel.setVisibility(View.VISIBLE);
                settingPanel.setVisibility(View.GONE);
                tabDebug.setTextColor(0xFFEA580C);
                tabOther.setTextColor(0x80FFFFFF);
            }
        });
        tabOther.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logPanel.setVisibility(View.GONE);
                settingPanel.setVisibility(View.VISIBLE);
                tabDebug.setTextColor(0x80FFFFFF);
                tabOther.setTextColor(0xFFEA580C);
            }
        });
    }

    /** 顶部拖动条拖动 + 右下角缩放手柄调整高度。 */
    private void initDragAndResize(final View view) {
        view.findViewById(R.id.bar).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    EasyFloat.appFloatDragEnable(true, "print");
                }
                return false;
            }
        });

        final View handle = view.findViewById(R.id.resizeHandle);
        final int minH = dp(220);
        final int maxH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.7f);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float startY, baseH;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        baseH = getFloatHeight();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = event.getRawY() - startY;
                        int newH = Math.max(minH, Math.min(maxH, (int) (baseH + dy)));
                        updateFloatHeight(newH);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return true;
            }
        });
    }

    private float getFloatHeight() {
        View fv = EasyFloat.getAppFloatView("print");
        if (fv != null && fv.getHeight() > 0) {
            return fv.getHeight();
        }
        return dp(340);
    }

    private void updateFloatHeight(int newH) {
        View fv = EasyFloat.getAppFloatView("print");
        if (fv == null) return;
        if (fv instanceof ViewGroup) {
            View content = ((ViewGroup) fv).getChildAt(0);
                if (content != null) {
                    ViewGroup.LayoutParams lp = content.getLayoutParams();
                    lp.height = newH;
                    content.setLayoutParams(lp);
                    content.requestLayout();
                }
        }
    }

    private void initIcon() {
        if (EasyFloat.getAppFloatView("icon") != null) {
            EasyFloat.showAppFloat("icon");
            return;
        }
        OnInvokeView onInvokeView = new OnInvokeView() {
            @Override
            public void invoke(final View view) {
                view.findViewById(R.id.fd).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        canshow = true;
                        EasyFloat.showAppFloat("print");
                        EasyFloat.hideAppFloat("icon");
                    }
                });
            }
        };
        EasyFloat.with(activity)
                .setLayout(R.layout.float_print_icon, onInvokeView)
                .setShowPattern(ShowPattern.ALL_TIME)
                .setSidePattern(SidePattern.RESULT_HORIZONTAL)
                .setTag("icon")
                .setAppFloatAnimator(null)
                .setLocation(0, 300)
                .show();
    }

    private Runnable task = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, 200);
            try {
                JSONObject jsonObject = PrintData.getData(RxTool.getContext(), endId);
                int eid = jsonObject.getIntValue("endId");
                if (eid <= 0) {
                    return;
                }
                endId = eid;
                JSONArray msg = jsonObject.getJSONArray("msg");
                for (Object o : msg) {
                    JSONObject j = JSON.parseObject(o.toString());
                    datas.add(new FloatPrintLogItem(JSONObject.toJSONString(j, true)));
                }
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!stop) {
                            adapter.notifyDataSetChanged();
                            recyclerView.scrollToPosition(datas.size() - 1);
                        }
                    }
                }, 0);
            } catch (Throwable ignored) {
            }
        }
    };

    private int dp(int v) {
        return (int) (context.getResources().getDisplayMetrics().density * v);
    }
}
