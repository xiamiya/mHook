package cn.mhook.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cn.mhook.mhook.R;

/**
 * 叠加辅助核心：球袋标记 + 目标球标记 + 连线 + 悬浮控制条 + 帧循环。
 */
public final class AssistCore {

    private static final String TAG = "AssistCore";
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final long FRAME_MS = 800;   // 约 1.2fps，够用且不占资源
    private static final String PREFS = "mhook_assist";
    private static final float[] DEFAULT_POCKETS = {
            0.205f, 0.213f, 0.805f, 0.213f,
            0.196f, 0.475f, 0.812f, 0.475f,
            0.205f, 0.737f, 0.805f, 0.737f,
    };

    private static WindowManager wm;
    private static OverlayView view;
    private static boolean running;
    private static WindowManager.LayoutParams lp;
    private static View panel;
    private static WindowManager.LayoutParams panelLp;
    private static TextView panelRun;
    private static TextView panelPocket;
    private static TextView panelTarget;
    private static TextView panelRoi;
    private static long lastFrameAt;
    private static long lastLogAt;
    private static int frames;
    private static Context appCtx;
    private static final float[] pockets = DEFAULT_POCKETS.clone();

    private AssistCore() {
    }

    public static boolean overlayAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(ctx);
        }
        return true;
    }

    public static boolean isRunning() {
        return running;
    }

    public static OverlayView view() {
        return view;
    }

    static void onServiceReady(Context ctx) {
        if (running) {
            attach(ctx);
        }
    }

    static void onServiceGone() {
        running = false;
        detach();
    }

    public static boolean start(Context ctx) {
        if (!overlayAllowed(ctx)) {
            return false;
        }
        appCtx = ctx.getApplicationContext();
        loadPrefs();
        cleanupFrames();
        running = true;
        attach(ctx);
        return true;
    }

    public static void stop() {
        running = false;
        detach();
    }

    private static void attach(Context ctx) {
        if (view != null) {
            return;
        }
        try {
            appCtx = ctx.getApplicationContext();
            view = new OverlayView(ctx);
            view.setRoi(LineDetector.roiNormalized());
            view.setPockets(pockets);
            view.setOnPocketsChanged(() -> {
                // 拖动中只更新内存（不写盘，避免卡顿）；退出编辑时再保存
                System.arraycopy(view.pockets(), 0, pockets, 0, 12);
                LineDetector.setRoiNormalized(view.roi());
            });
            view.setOnRequestExit(() -> {
                view.setRoiEditing(false);
                updateTouchable();
                refreshPanel();
            });
            view.setOnTargetPicked(() -> {
                updateTouchable();
                refreshPanel();
            });
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            lp = params;
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            wm.addView(view, lp);
            UI.post(frameLoop);
        } catch (Throwable t) {
            Log.w(TAG, "attach failed", t);
            view = null;
        }
    }

    public static void setTouchable(boolean touchable) {
        if (view == null || wm == null || lp == null) {
            return;
        }
        try {
            if (touchable) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            wm.updateViewLayout(view, lp);
        } catch (Throwable t) {
            Log.w(TAG, "updateViewLayout failed", t);
        }
    }

    private static boolean anyEditMode() {
        return view != null && (view.isPocketEditing() || view.isTargetPicking() || view.isRoiEditing());
    }

    private static void updateTouchable() {
        setTouchable(anyEditMode());
    }

    public static void setPocketEditing(boolean on) {
        if (view == null) {
            return;
        }
        view.setTargetPicking(false);
        view.setRoiEditing(false);
        view.setPocketEditing(on);
        afterModeChange();
    }

    public static void setTargetPicking() {
        if (view == null) {
            return;
        }
        boolean on = !view.isTargetPicking();
        view.setPocketEditing(false);
        view.setRoiEditing(false);
        view.setTargetPicking(on);
        if (on) {
            needDetect = true;   // 选目标球要重新识别一次，才能吸附到球心
        }
        afterModeChange();
    }

    public static void setRoiEditing(boolean on) {
        if (view == null) {
            return;
        }
        view.setPocketEditing(false);
        view.setTargetPicking(false);
        view.setRoiEditing(on);
        afterModeChange();
    }

    /** 编辑模式切换后：进入时抓一帧做放大镜底图；全部退出时立刻释放底图。 */
    private static void afterModeChange() {
        if (anyEditMode()) {
            needBg = true;
        } else {
            recycleBg();
            savePrefs();
        }
        updateTouchable();
        refreshPanel();
    }

    public static void clearTarget() {
        if (view != null) {
            view.setManualTarget(-1, -1);
        }
        refreshPanel();
    }

    private static void recycleBg() {
        // 不显式 recycle：避免渲染线程正在用时抛"使用已回收位图"异常；交给 GC
        if (view != null) {
            view.setFrame(null);
        }
        bgBmp = null;
        bgCanvas = null;
    }

    /** 清理存图缓存。 */
    static void cleanupFrames() {
        try {
            if (appCtx == null) {
                return;
            }
            java.io.File dir = appCtx.getExternalFilesDir(null);
            if (dir == null) {
                dir = appCtx.getFilesDir();
            }
            java.io.File[] fs = dir.listFiles();
            if (fs != null) {
                for (java.io.File f : fs) {
                    if (f.getName().startsWith("assist_frame")) {
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void detach() {
        UI.removeCallbacks(frameLoop);
        if (view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (Throwable ignored) {
            }
        }
        view = null;
        wm = null;
        recycleBg();
        LineDetector.release();
    }

    private static final Runnable frameLoop = new Runnable() {
        @Override
        public void run() {
            if (!running || view == null) {
                return;
            }
            if (!anyEditMode() || needBg || needDetect) {
                AssistService svc = AssistService.get();
                if (svc != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastFrameAt >= FRAME_MS) {
                        lastFrameAt = now;
                        svc.requestFrame();
                    }
                }
            }
            UI.postDelayed(this, FRAME_MS);
        }
    };

    private static volatile LineDetector.Spec spec;
    private static volatile int saveLeft;
    // 粘滞：某些帧识别不到目标球/母球时，沿用上一帧，避免线一闪一闪
    private static LineDetector.Ball stickyTarget;
    private static LineDetector.Ball stickyCue;
    private static java.util.List<LineDetector.Ball> stickyBalls;
    private static long stickyTargetAt;
    private static Bitmap bgBmp;
    private static Canvas bgCanvas;
    private static volatile boolean needBg;
    private static volatile boolean needDetect;
    private static final Paint BG_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static volatile boolean detecting;

    public static void requestSaveFrame() {
        cleanupFrames();   // 先删掉上一批（不攒着）
        saveLeft = 8;
    }

    static void onFrame(Bitmap bmp) {
        final OverlayView v = view;
        if (v == null) {
            bmp.recycle();
            return;
        }
        try {
            if (saveLeft > 0) {
                int idx = 8 - saveLeft;
                saveLeft--;
                try {
                    java.io.File dir = v.getContext().getExternalFilesDir(null);
                    if (dir == null) {
                        dir = v.getContext().getFilesDir();
                    }
                    java.io.File f = new java.io.File(dir, "assist_frame_" + idx + ".png");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                } catch (Throwable t) {
                    Log.w(TAG, "save frame failed", t);
                }
            }

            boolean edit = anyEditMode();
            if (edit) {
                updateBg(v, bmp);   // 只更新放大镜底图（识别很重，不能每帧做）
            }
            // 非编辑模式照常识别；「选目标球」时也识别一次（需要球心做吸附）
            if (!edit || needDetect) {
                if (detecting) {
                    bmp.recycle();
                    return;
                }
                detecting = true;
                needDetect = false;
                final Bitmap b = bmp;
                final int bw = bmp.getWidth(), bh = bmp.getHeight();
                WORK.execute(() -> {
                    LineDetector.Spec s;
                    try {
                        s = LineDetector.detect(b);
                    } catch (Throwable t) {
                        Log.w(TAG, "detect failed", t);
                        s = null;
                    }
                    final LineDetector.Spec fs = s;
                    UI.post(() -> applySpec(v, fs, bw, bh));
                    b.recycle();
                    detecting = false;
                });
            } else {
                bmp.recycle();
            }
        } catch (Throwable t) {
            Log.w(TAG, "onFrame failed", t);
            try {
                bmp.recycle();
            } catch (Throwable ignored) {
            }
            detecting = false;
        }
    }

    /** 主线程：应用识别结果（含粘滞，避免线一闪一闪）。 */
    private static void applySpec(OverlayView v, LineDetector.Spec s, int bw, int bh) {
        if (v == null) {
            return;
        }
        frames++;
        if (s != null) {
            long now = System.currentTimeMillis();
            if (s.target != null) {
                stickyTarget = copyBall(s.target);
                stickyTargetAt = now;
            }
            if (s.cue != null) {
                stickyCue = copyBall(s.cue);
            }
            if (s.balls != null && !s.balls.isEmpty()) {
                stickyBalls = copyBalls(s.balls);
            }
            if (s.target == null && stickyTarget != null && now - stickyTargetAt < 4000) {
                s.target = copyBall(stickyTarget);
            }
            if (s.cue == null && stickyCue != null) {
                s.cue = copyBall(stickyCue);
            }
            if ((s.balls == null || s.balls.isEmpty()) && stickyBalls != null) {
                s.balls = copyBalls(stickyBalls);
            }
            if (s.balls != null && !s.balls.isEmpty()) {
                scaleSpec(s, bw, bh, v.getWidth(), v.getHeight());
                spec = s;
                v.setSpec(s);
            }
        }
        StringBuilder sb = new StringBuilder("帧 ").append(frames);
        if (v.hasManualTarget()) {
            sb.append(" · 目标球：手动");
        } else if (spec != null && spec.target != null) {
            sb.append(" · 目标球：自动");
        } else {
            sb.append(" · 未识别到目标球（可点「目标球」手动标）");
        }
        v.setInfo(sb.toString());

        long now = System.currentTimeMillis();
        if (now - lastLogAt > 1000) {
            lastLogAt = now;
            Log.i(TAG, LineDetector.lastDebug);
        }
    }

    /** 把当前帧缩成小图，供拖动时的放大镜使用。 */
    private static void updateBg(OverlayView v, Bitmap bmp) {
        int vw = v.getWidth(), vh = v.getHeight();
        if (vw <= 0 || vh <= 0) {
            return;
        }
        final int bgW = 640;
        int bgH = Math.max(1, Math.round(bgW * (float) vh / vw));
        if (bgBmp == null || bgBmp.getWidth() != bgW || bgBmp.getHeight() != bgH) {
            bgBmp = Bitmap.createBitmap(bgW, bgH, Bitmap.Config.ARGB_8888);
            bgCanvas = new Canvas(bgBmp);
        }
        bgCanvas.drawBitmap(bmp, new Rect(0, 0, bmp.getWidth(), bmp.getHeight()),
                new Rect(0, 0, bgW, bgH), BG_PAINT);
        v.setFrame(bgBmp);
        needBg = false;
    }

    public static LineDetector.Spec lastSpec() {
        return spec;
    }

    private static void scaleSpec(LineDetector.Spec s, int bw, int bh, int vw, int vh) {
        if (s == null || bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) {
            return;
        }
        float sx = (float) vw / bw, sy = (float) vh / bh;
        if (Math.abs(sx - 1f) < 0.001f && Math.abs(sy - 1f) < 0.001f) {
            return;
        }
        if (s.cue != null) {
            s.cue.x *= sx;
            s.cue.y *= sy;
            s.cue.r *= Math.min(sx, sy);
        }
        if (s.target != null) {
            s.target.x *= sx;
            s.target.y *= sy;
            s.target.r *= Math.min(sx, sy);
        }
        if (s.balls != null) {
            for (LineDetector.Ball b : s.balls) {
                b.x *= sx;
                b.y *= sy;
                b.r *= Math.min(sx, sy);
            }
        }
        s.ghostX *= sx;
        s.ghostY *= sy;
        s.ghostR *= Math.min(sx, sy);
        scaleLine(s.targetLine, sx, sy);
        scaleLine(s.cueLine, sx, sy);
    }

    private static LineDetector.Ball copyBall(LineDetector.Ball b) {
        LineDetector.Ball c = new LineDetector.Ball();
        c.x = b.x;
        c.y = b.y;
        c.r = b.r;
        c.white = b.white;
        return c;
    }

    private static java.util.List<LineDetector.Ball> copyBalls(java.util.List<LineDetector.Ball> src) {
        java.util.List<LineDetector.Ball> out = new java.util.ArrayList<>(src.size());
        for (LineDetector.Ball b : src) {
            out.add(copyBall(b));
        }
        return out;
    }

    private static void scaleLine(float[] l, float sx, float sy) {
        if (l == null || l.length != 4) {
            return;
        }
        l[0] *= sx;
        l[1] *= sy;
        l[2] *= sx;
        l[3] *= sy;
    }

    public static boolean showPanel(Context ctx) {
        if (!overlayAllowed(ctx)) {
            return false;
        }
        if (panel != null) {
            return true;
        }
        try {
            attach(ctx);
            if (view == null) {
                return false;
            }
            panel = android.view.LayoutInflater.from(ctx).inflate(R.layout.overlay_panel, null);
            panelLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            panelLp.y = 120;
            wm.addView(panel, panelLp);

            panelRun = panel.findViewById(R.id.panel_run);
            panelPocket = panel.findViewById(R.id.panel_pocket);
            panelTarget = panel.findViewById(R.id.panel_target);
            panelRoi = panel.findViewById(R.id.panel_roi_minus);

            panelRun.setOnClickListener(v -> {
                if (isRunning()) {
                    stop();
                } else {
                    attach(ctx);
                    running = true;
                    if (view != null) {
                        UI.post(frameLoop);
                    }
                }
                refreshPanel();
            });
            panelTarget.setOnClickListener(v -> {
                if (view == null) {
                    return;
                }
                if (view.isTargetPicking()) {
                    view.setTargetPicking(false);
                    updateTouchable();
                } else {
                    setTargetPicking();
                }
                refreshPanel();
            });
            panelPocket.setOnClickListener(v -> {
                if (view == null) {
                    return;
                }
                setPocketEditing(!view.isPocketEditing());
            });
            panel.findViewById(R.id.panel_clear).setOnClickListener(v -> clearTarget());
            panelRoi.setOnClickListener(v -> {
                if (view == null) {
                    return;
                }
                setRoiEditing(!view.isRoiEditing());
            });
            panel.findViewById(R.id.panel_close).setOnClickListener(v -> hidePanel());
            panel.setOnTouchListener(new PanelDrag());
            refreshPanel();
            Log.i(TAG, "panel shown");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "showPanel failed", t);
            panel = null;
            return false;
        }
    }

    public static void hidePanel() {
        try {
            // 先移除控制条（detach 会把 wm 置空）
            if (panel != null && wm != null) {
                try {
                    wm.removeView(panel);
                } catch (Throwable ignored) {
                }
            }
            panel = null;
            panelLp = null;
            panelRun = null;
            panelPocket = null;
            panelTarget = null;
            panelRoi = null;
            stop();
            detach();
            cleanupFrames();
        } catch (Throwable t) {
            Log.e(TAG, "hidePanel failed", t);
        }
    }

    public static boolean isPanelShown() {
        return panel != null;
    }

    private static void refreshPanel() {
        if (panelRun != null) {
            panelRun.setText(isRunning() ? "停止" : "开始");
        }
        if (panelPocket != null) {
            panelPocket.setText(view != null && view.isPocketEditing() ? "球袋完成" : "球袋");
        }
        if (panelTarget != null) {
            panelTarget.setText(view != null && view.isTargetPicking() ? "点球…" : "目标球");
        }
        if (panelRoi != null) {
            panelRoi.setText(view != null && view.isRoiEditing() ? "框完成" : "调框");
        }
    }

    // ---------- 记忆 ----------
    private static void loadPrefs() {
        if (appCtx == null) {
            return;
        }
        try {
            SharedPreferences sp = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            LineDetector.threshold = sp.getInt("thr", LineDetector.threshold);
            LineDetector.setRoiNormalized(new RectF(
                    sp.getFloat("l", LineDetector.roiL),
                    sp.getFloat("t", LineDetector.roiT),
                    sp.getFloat("r", LineDetector.roiR),
                    sp.getFloat("b", LineDetector.roiB)));
            for (int i = 0; i < 6; i++) {
                pockets[i * 2] = sp.getFloat("pk" + i + "x", pockets[i * 2]);
                pockets[i * 2 + 1] = sp.getFloat("pk" + i + "y", pockets[i * 2 + 1]);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void savePrefs() {
        if (appCtx == null) {
            return;
        }
        try {
            SharedPreferences.Editor e = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            e.putInt("thr", LineDetector.threshold);
            e.putFloat("l", LineDetector.roiL);
            e.putFloat("t", LineDetector.roiT);
            e.putFloat("r", LineDetector.roiR);
            e.putFloat("b", LineDetector.roiB);
            for (int i = 0; i < 6; i++) {
                e.putFloat("pk" + i + "x", pockets[i * 2]);
                e.putFloat("pk" + i + "y", pockets[i * 2 + 1]);
            }
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    private static class PanelDrag implements View.OnTouchListener {
        private int downX, downY;
        private float startX, startY;

        @Override
        public boolean onTouch(View v, android.view.MotionEvent e) {
            switch (e.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = (int) e.getRawX();
                    downY = (int) e.getRawY();
                    startX = panelLp.x;
                    startY = panelLp.y;
                    return false;
                case android.view.MotionEvent.ACTION_MOVE:
                    int dx = (int) e.getRawX() - downX;
                    int dy = (int) e.getRawY() - downY;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        panelLp.x = (int) startX + dx;
                        panelLp.y = (int) startY + dy;
                        try {
                            wm.updateViewLayout(panel, panelLp);
                        } catch (Throwable ignored) {
                        }
                    }
                    return false;
                default:
                    return false;
            }
        }
    }
}
