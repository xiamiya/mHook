package cn.mhook.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/**
 * 全屏透明悬浮层：
 *  - 球袋标记（6 个，可拖动校准 / 记忆）
 *  - 目标球标记（点击画面标记，或自动识别）
 *  - 从目标球向每个袋口画细线；标记被挡的袋、最近的空袋
 *  - 对最近空袋画「假想球（母球瞄准点）」和母球瞄准线
 *  - ROI 矩形拖动调整
 */
public class OverlayView extends View {

    private static final int NONE = -1, TL = 0, TR = 1, BR = 2, BL = 3, MOVE = 4;

    private final Paint pocketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pocketHiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineBlockedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lineBestPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cueBallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint aimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint roiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private volatile LineDetector.Spec spec;
    private volatile String info = "";

    /** 球袋（归一化 0..1）：6 个，x,y 交替。 */
    private float[] pockets = {
            0.205f, 0.213f, 0.805f, 0.213f,   // 上左 / 上右
            0.196f, 0.475f, 0.812f, 0.475f,   // 中左 / 中右
            0.205f, 0.737f, 0.805f, 0.737f,   // 下左 / 下右
    };
    /** 手动标记的目标球（视图像素），-1 = 未标记。 */
    private volatile float targetX = -1, targetY = -1;
    private volatile float targetR = 20f;
    private volatile boolean targetManual;

    private boolean pocketEditing;
    private int dragPocket = -1;
    private boolean targetPicking;
    private float downX, downY;
    private float[] pocketStart;

    private RectF roi = new RectF(0.10f, 0.18f, 0.90f, 0.86f);
    private boolean roiEditing;
    private int dragCorner = NONE;
    private RectF dragStart;

    private Runnable onPocketsChanged;
    private Runnable onRequestExit;
    private Runnable onTargetPicked;

    // 局部放大镜：拖动时显示手指附近的放大画面
    private volatile Bitmap bg;
    private volatile int bgW, bgH;
    private boolean showLoupe;
    private float lastTX, lastTY;
    private final Paint loupeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint loupeFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint loupeCrossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint loupeBmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    public OverlayView(Context ctx) {
        super(ctx);

        pocketPaint.setColor(0xFF00E676);
        pocketPaint.setStrokeWidth(5f);
        pocketPaint.setStyle(Paint.Style.STROKE);
        pocketHiPaint.setColor(0xFF00E676);
        pocketHiPaint.setStyle(Paint.Style.FILL);

        linePaint.setColor(0xCCFFFFFF);
        linePaint.setStrokeWidth(4f);
        lineBlockedPaint.setColor(0x99FF5555);
        lineBlockedPaint.setStrokeWidth(4f);
        lineBestPaint.setColor(0xFFFF2D2D);
        lineBestPaint.setStrokeWidth(7f);

        targetPaint.setColor(0xFFFFA000);
        targetPaint.setStrokeWidth(7f);
        targetPaint.setStyle(Paint.Style.STROKE);

        ghostPaint.setColor(0xFF2979FF);
        ghostPaint.setStrokeWidth(5f);
        ghostPaint.setStyle(Paint.Style.STROKE);
        ghostPaint.setPathEffect(new DashPathEffect(new float[]{12f, 10f}, 0));

        cueBallPaint.setColor(0xFFFF00E5);
        cueBallPaint.setStrokeWidth(5f);
        cueBallPaint.setStyle(Paint.Style.STROKE);

        aimPaint.setColor(0xFFFFEB3B);
        aimPaint.setStrokeWidth(5f);
        aimPaint.setStyle(Paint.Style.STROKE);
        aimPaint.setPathEffect(new DashPathEffect(new float[]{26f, 16f}, 0));

        textPaint.setColor(0xF2FFFFFF);
        textPaint.setTextSize(36f);

        roiPaint.setColor(0xFF00B0FF);
        roiPaint.setStrokeWidth(5f);
        roiPaint.setStyle(Paint.Style.STROKE);
        roiPaint.setPathEffect(new DashPathEffect(new float[]{18f, 12f}, 0));

        dimPaint.setColor(0x66000000);
        handlePaint.setColor(0xFFFFD600);
        handlePaint.setStyle(Paint.Style.FILL);

        loupeBgPaint.setColor(0xF0000000);
        loupeFramePaint.setColor(0xFF00E676);
        loupeFramePaint.setStrokeWidth(4f);
        loupeFramePaint.setStyle(Paint.Style.STROKE);
        loupeCrossPaint.setColor(0xFFFFEB3B);
        loupeCrossPaint.setStrokeWidth(3f);

        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setSpec(LineDetector.Spec s) {
        this.spec = s;
        postInvalidate();
    }

    /** 最近一帧画面（缩小图），用于拖动时局部放大。 */
    public void setFrame(Bitmap b) {
        if (b != null && b.isRecycled()) {
            b = null;
        }
        this.bg = b;
        this.bgW = b == null ? 0 : b.getWidth();
        this.bgH = b == null ? 0 : b.getHeight();
    }

    public void clear() {
        this.spec = null;
        targetX = -1;
        targetY = -1;
        targetManual = false;
        postInvalidate();
    }

    public void setInfo(String s) {
        this.info = s;
        postInvalidate();
    }

    public void setOnPocketsChanged(Runnable r) {
        this.onPocketsChanged = r;
    }

    public void setOnRequestExit(Runnable r) {
        this.onRequestExit = r;
    }

    public void setOnTargetPicked(Runnable r) {
        this.onTargetPicked = r;
    }

    // ---------- 球袋 ----------
    public float[] pockets() {
        return pockets.clone();
    }

    public void setPockets(float[] p) {
        if (p != null && p.length == 12) {
            pockets = p.clone();
            postInvalidate();
        }
    }

    public void setPocketEditing(boolean on) {
        pocketEditing = on;
        dragPocket = -1;
        postInvalidate();
    }

    public boolean isPocketEditing() {
        return pocketEditing;
    }

    // ---------- 目标球 ----------
    public void setManualTarget(float x, float y) {
        targetX = x;
        targetY = y;
        targetManual = x >= 0;
        postInvalidate();
    }

    public void setTargetPicking(boolean on) {
        targetPicking = on;
        postInvalidate();
    }

    public boolean isTargetPicking() {
        return targetPicking;
    }

    public boolean hasManualTarget() {
        return targetManual && targetX >= 0;
    }

    /** 点击目标球时自动吸附到最近识别到的球心（手点偏差大，吸附才准）。 */
    private void applySnap(float x, float y) {
        float nx = x, ny = y, nr = targetR;
        LineDetector.Spec s = spec;
        if (s != null && s.balls != null) {
            LineDetector.Ball best = null;
            double bd = 1e9;
            for (LineDetector.Ball b : s.balls) {
                double d = Math.hypot(b.x - x, b.y - y);
                if (d < bd) {
                    bd = d;
                    best = b;
                }
            }
            if (best != null && bd < Math.max(80, best.r * 4.0)) {
                nx = best.x;
                ny = best.y;
                nr = best.r;
            }
        }
        targetX = nx;
        targetY = ny;
        targetR = nr;
        targetManual = true;
        postInvalidate();
    }

    // ---------- ROI ----------
    public RectF roi() {
        return new RectF(roi);
    }

    public void setRoi(RectF r) {
        if (r != null) {
            roi = new RectF(r);
            postInvalidate();
        }
    }

    public void setRoiEditing(boolean on) {
        roiEditing = on;
        dragCorner = NONE;
        postInvalidate();
    }

    public boolean isRoiEditing() {
        return roiEditing;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        float x = e.getX(), y = e.getY();

        if (targetPicking) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTX = x;
                    lastTY = y;
                    showLoupe = true;
                    applySnap(x, y);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lastTX = x;
                    lastTY = y;
                    applySnap(x, y);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    applySnap(x, y);
                    targetPicking = false;
                    showLoupe = false;
                    postInvalidate();
                    if (onTargetPicked != null) {
                        onTargetPicked.run();
                    }
                    return true;
                default:
                    return true;
            }
        }

        if (pocketEditing) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = x;
                    downY = y;
                    pocketStart = pockets.clone();
                    dragPocket = NONE;
                    float bestD = Math.max(60f, w * 0.07f);
                    for (int i = 0; i < 6; i++) {
                        float dd = (float) Math.hypot(x - pockets[i * 2] * w, y - pockets[i * 2 + 1] * h);
                        if (dd < bestD) {
                            bestD = dd;
                            dragPocket = i;
                        }
                    }
                    lastTX = x;
                    lastTY = y;
                    showLoupe = dragPocket >= 0;
                    postInvalidate();
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    lastTX = x;
                    lastTY = y;
                    showLoupe = dragPocket >= 0;
                    if (dragPocket >= 0) {
                        pockets[dragPocket * 2] = Math.max(0f, Math.min(1f, x / w));
                        pockets[dragPocket * 2 + 1] = Math.max(0f, Math.min(1f, y / h));
                        if (onPocketsChanged != null) {
                            onPocketsChanged.run();
                        }
                    }
                    postInvalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragPocket = -1;
                    showLoupe = false;
                    postInvalidate();
                    return true;
                default:
                    return false;
            }
        }

        if (!roiEditing) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                dragStart = new RectF(roi);
                float tol = Math.max(40f, w * 0.05f);
                float[] cs = {roi.left, roi.top, roi.right, roi.top, roi.right, roi.bottom, roi.left, roi.bottom};
                dragCorner = NONE;
                float bestD = tol;
                for (int i = 0; i < 4; i++) {
                    float dd = (float) Math.hypot(x - cs[i * 2] * w, y - cs[i * 2 + 1] * h);
                    if (dd < bestD) {
                        bestD = dd;
                        dragCorner = i;
                    }
                }
                if (dragCorner == NONE && roi.contains(x / w, y / h)) {
                    dragCorner = MOVE;
                }
                if (dragCorner == NONE) {
                    if (onRequestExit != null) {
                        onRequestExit.run();
                    }
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragCorner == NONE || dragStart == null) {
                    return false;
                }
                lastTX = x;
                lastTY = y;
                showLoupe = true;
                float dx = x / w - downX / w, dy = y / h - downY / h;
                RectF r = new RectF(dragStart);
                switch (dragCorner) {
                    case TL: r.left = dragStart.left + dx; r.top = dragStart.top + dy; break;
                    case TR: r.right = dragStart.right + dx; r.top = dragStart.top + dy; break;
                    case BR: r.right = dragStart.right + dx; r.bottom = dragStart.bottom + dy; break;
                    case BL: r.left = dragStart.left + dx; r.bottom = dragStart.bottom + dy; break;
                    case MOVE: r.offset(dx, dy); break;
                    default: break;
                }
                if (r.left < 0f) r.left = 0f;
                if (r.top < 0f) r.top = 0f;
                if (r.right > 1f) r.right = 1f;
                if (r.bottom > 1f) r.bottom = 1f;
                if (r.right - r.left < 0.06f) r.right = r.left + 0.06f;
                if (r.bottom - r.top < 0.06f) r.bottom = r.top + 0.06f;
                roi = r;
                if (onPocketsChanged != null) {
                    onPocketsChanged.run();
                }
                postInvalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragCorner = NONE;
                showLoupe = false;
                postInvalidate();
                return true;
            default:
                return false;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        if (roiEditing) {
            float l = roi.left * w, t = roi.top * h, r = roi.right * w, b = roi.bottom * h;
            canvas.drawRect(0, 0, w, t, dimPaint);
            canvas.drawRect(0, b, w, h, dimPaint);
            canvas.drawRect(0, t, l, b, dimPaint);
            canvas.drawRect(r, t, w, b, dimPaint);
            canvas.drawRect(l, t, r, b, roiPaint);
            float[] cs = {l, t, r, t, r, b, l, b};
            for (int i = 0; i < 4; i++) {
                canvas.drawCircle(cs[i * 2], cs[i * 2 + 1], 22f, handlePaint);
            }
        }

        // 目标球
        LineDetector.Spec s = spec;
        float tx = -1, ty = -1, tr = 20f;
        if (targetManual && targetX >= 0) {
            tx = targetX;
            ty = targetY;
            tr = targetR;
        } else if (s != null && s.target != null) {
            tx = s.target.x;
            ty = s.target.y;
            tr = s.target.r;
        }
        if (s != null) {
            if (s.ghostX >= 0) {
                canvas.drawCircle(s.ghostX, s.ghostY, s.ghostR, ghostPaint);
            }
            if (s.cue != null) {
                canvas.drawCircle(s.cue.x, s.cue.y, s.cue.r, cueBallPaint);
            }
        }

        // 球袋 + 连线
        int bestPocket = -1;
        float bestLen = 1e9f;
        boolean[] blocked = new boolean[6];
        float[] px = new float[6], py = new float[6];
        for (int i = 0; i < 6; i++) {
            px[i] = pockets[i * 2] * w;
            py[i] = pockets[i * 2 + 1] * h;
            canvas.drawCircle(px[i], py[i], 26f, pocketPaint);
            canvas.drawCircle(px[i], py[i], 10f, pocketHiPaint);
        }
        if (tx >= 0 && ty >= 0) {
            for (int i = 0; i < 6; i++) {
                blocked[i] = blockedBy(tx, ty, px[i], py[i], tr, tx, ty)
                        || outsideRoi(tx, ty, px[i], py[i], w, h);
                float len = (float) Math.hypot(px[i] - tx, py[i] - ty);
                if (!blocked[i] && len < bestLen) {
                    bestLen = len;
                    bestPocket = i;
                }
            }
            for (int i = 0; i < 6; i++) {
                canvas.drawLine(tx, ty, px[i], py[i],
                        i == bestPocket ? lineBestPaint : (blocked[i] ? lineBlockedPaint : linePaint));
            }
            // 假想球 + 母球瞄准线（对最近的空袋）
            if (bestPocket >= 0) {
                float ux = px[bestPocket] - tx, uy = py[bestPocket] - ty;
                float L = (float) Math.hypot(ux, uy);
                if (L > 1f) {
                    float gx = tx - ux / L * (tr * 2f);
                    float gy = ty - uy / L * (tr * 2f);
                    canvas.drawCircle(gx, gy, tr, ghostPaint);
                    if (s != null && s.cue != null) {
                        canvas.drawLine(s.cue.x, s.cue.y, gx, gy, aimPaint);
                    }
                }
            }
            canvas.drawCircle(tx, ty, tr + 4, targetPaint);
        }

        String txt = (info == null ? "" : info);
        if (pocketEditing) {
            txt = "拖动 6 个黄/缘圈到球袋位置（调好点「球袋完成」）";
        } else if (targetPicking) {
            txt = "点一下画面里的目标球";
        }
        if (txt != null && !txt.isEmpty()) {
            canvas.drawText(txt, 60f, h * 0.12f + 60f, textPaint);
        }

        if (showLoupe && bg != null && !bg.isRecycled() && bgW > 0) {
            drawLoupe(canvas, w, h);
        }
    }

    /** 手指附近的局部放大镜（跟着点击位置走，放在手指上方避开遮挡）。 */
    private void drawLoupe(Canvas canvas, int w, int h) {
        float S = Math.min(380f, w * 0.36f);
        float gap = 70f;
        float margin = h * 0.03f;
        // 默认放手指上方；上方不够就放下方
        float top = lastTY - S - gap;
        if (top < margin) {
            top = lastTY + gap;
        }
        top = Math.max(margin, Math.min(h - S - margin, top));
        float left = Math.max(40f, Math.min(w - S - 40f, lastTX - S / 2f));
        float zoom = 3f;
        // bg 是缩小图，需要把视图坐标换算回 bg 坐标
        float sc = (float) bgW / w;
        float bx = lastTX * sc, by = lastTY * sc;
        float half = (S / (2f * zoom)) * sc;
        float bgw = bgW, bgh = bgH;
        float sx = Math.max(0f, Math.min(bgw - 2 * half, bx - half));
        float sy = Math.max(0f, Math.min(bgh - 2 * half, by - half));
        Rect src = new Rect((int) sx, (int) sy, (int) (sx + 2 * half), (int) (sy + 2 * half));
        RectF dst = new RectF(left, top, left + S, top + S);
        canvas.drawRoundRect(new RectF(left - 8, top - 8, left + S + 8, top + S + 8), 18f, 18f, loupeBgPaint);
        try {
            canvas.drawBitmap(bg, src, dst, loupeBmpPaint);
        } catch (Throwable ignored) {
            return;   // 底图刚好被回收，跳过这一帧
        }
        canvas.drawRect(dst, loupeFramePaint);
        float cx = left + S / 2f, cy = top + S / 2f;
        canvas.drawLine(cx - 40, cy, cx + 40, cy, loupeCrossPaint);
        canvas.drawLine(cx, cy - 40, cx, cy + 40, loupeCrossPaint);
    }

    /** 目标球→袋口的连线是否走出了搜索框（搜索框也当遮挡物）。 */
    private boolean outsideRoi(float x1, float y1, float x2, float y2, int w, int h) {
        float m = 0.03f;
        float l = (roi.left - m) * w, t = (roi.top - m) * h;
        float r = (roi.right + m) * w, b = (roi.bottom + m) * h;
        for (int i = 1; i < 10; i++) {
            float tt = i / 10f;
            float x = x1 + (x2 - x1) * tt, y = y1 + (y2 - y1) * tt;
            if (x < l || y < t || x > r || y > b) {
                return true;
            }
        }
        return false;
    }

    /** 目标球 → 袋口 的路径上是否有别的球挡住。 */
    private boolean blockedBy(float x1, float y1, float x2, float y2, float r, float sx, float sy) {
        LineDetector.Spec s = spec;
        if (s == null || s.balls == null) {
            return false;
        }
        List<LineDetector.Ball> balls = s.balls;
        double dx = x2 - x1, dy = y2 - y1;
        double len2 = dx * dx + dy * dy;
        if (len2 < 1) {
            return false;
        }
        for (LineDetector.Ball b : balls) {
            if (Math.hypot(b.x - sx, b.y - sy) < 1f) {
                continue;   // 目标球自己
            }
            double t = ((b.x - x1) * dx + (b.y - y1) * dy) / len2;
            if (t < 0.05 || t > 0.95) {
                continue;
            }
            double dd = Math.hypot(b.x - (x1 + t * dx), b.y - (y1 + t * dy));
            if (dd < r + b.r - 4) {
                return true;
            }
        }
        return false;
    }
}
