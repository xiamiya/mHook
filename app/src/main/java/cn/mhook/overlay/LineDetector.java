package cn.mhook.overlay;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌球辅助识别（全自动，Java 版）。
 *
 * 依据游戏自身显示的元素（已在真机截图上验证误差 1~2px）：
 *   1) 虚球空心圆：亮环 + 内部是台面            → 锚点
 *   2) 实心球：圆盘匹配 + 梯度边界点均值精修球心
 *   3) 母球   = 最白（低饱和）那颗球
 *   4) 目标球 = 与虚球圆相切的球（圆心距 ≈ 半径和）
 *   5) 目标球出射线 = 目标球心 沿 (虚球心→目标球心) 延长
 *   6) 母球出射线   = 从虚球心发出；优先识别游戏画的那条，识别不到则按
 *                     「垂直于连线方向」算出（游戏有时不画）
 */
public final class LineDetector {

    private static final String TAG = "LineDetector";

    /** 线条相对台面的亮度差阈值（面板可调）。 */
    public static volatile int threshold = 30;
    public static volatile int maxLen = 1400;
    /** 桌面搜索区域（屏幕比例）。 */
    public static volatile float roiL = 0.10f, roiT = 0.18f, roiR = 0.90f, roiB = 0.86f;

    private LineDetector() {
    }

    public static void adjustRoi(float d) {
        float v = Math.max(0f, Math.min(0.42f, roiL + d));
        roiL = v;
        roiR = 1f - v;
    }

    public static RectF roiNormalized() {
        return new RectF(roiL, roiT, roiR, roiB);
    }

    public static void setRoiNormalized(RectF r) {
        if (r == null) {
            return;
        }
        roiL = c01(r.left);
        roiT = c01(r.top);
        roiR = c01(r.right);
        roiB = c01(r.bottom);
        if (roiR < roiL + 0.02f) {
            roiR = roiL + 0.02f;
        }
        if (roiB < roiT + 0.02f) {
            roiB = roiT + 0.02f;
        }
    }

    private static float c01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** 一颗球。 */
    public static class Ball {
        public float x, y, r;
        public int white;   // 白色程度 0..100
    }

    /** 一帧的识别结果（坐标 = 位图像素）。 */
    public static class Spec {
        public Ball cue;
        public Ball target;
        public float ghostX = -1, ghostY = -1, ghostR;
        public float[] targetLine;      // x1,y1,x2,y2（从目标球心延长）
        public float[] cueLine;         // x1,y1,x2,y2（从虚球心延长）
        public boolean cueLineDetected; // true=识别到游戏画的；false=几何推算
        public List<Ball> balls;        // 所有识别到的球（用于判断遮挡）

        public boolean ok() {
            return cue != null && target != null && targetLine != null;
        }
    }

    // ---------- 帧内状态（复用，避免频繁 GC） ----------
    private static int W, H, X0, Y0, X1, Y1;
    private static int[] sPx;
    private static float[] sBr;
    private static boolean[] sMask;      // 比台面亮（线/亮环）
    private static boolean[] sNotCloth;  // 非台面
    private static float sClothBr;
    private static int sRBall = 20;

    /** 释放帧缓冲（停止辅助时调用）。 */
    public static void release() {
        sPx = null;
        sBr = null;
        sMask = null;
        sNotCloth = null;
    }
    /** 最近一帧的调试信息。 */
    public static volatile String lastDebug = "";

    public static Spec detect(Bitmap bmp) {
        if (bmp == null) {
            return null;
        }
        Spec sp = new Spec();
        try {
            W = bmp.getWidth();
            H = bmp.getHeight();
            int n = W * H;
            if (sPx == null || sPx.length != n) {
                sPx = new int[n];
                sBr = new float[n];
                sMask = new boolean[n];
                sNotCloth = new boolean[n];
            }
            bmp.getPixels(sPx, 0, W, 0, 0, W, H);

            X0 = (int) (W * roiL);
            X1 = (int) (W * roiR);
            Y0 = (int) (H * roiT);
            Y1 = (int) (H * roiB);

            buildMasks();

            sRBall = Math.round(W * 0.018f);
            if (sRBall < 12) sRBall = 12;
            if (sRBall > 34) sRBall = 34;

            List<Ball> balls = findBalls();
            for (Ball b : balls) {
                refineCenter(b);
            }
            int maskOn = 0;
            for (int i = 0; i < n; i++) {
                if (sMask[i]) {
                    maskOn++;
                }
            }
            lastDebug = "bmp=" + W + "x" + H + " roi=" + X0 + "," + Y0 + "-" + X1 + "," + Y1
                    + " rBall=" + sRBall + " clothBr=" + Math.round(sClothBr)
                    + " maskPx=" + maskOn
                    + " balls=" + balls.size();

            // 母球 = 最白
            for (Ball b : balls) {
                if (sp.cue == null || b.white > sp.cue.white) {
                    sp.cue = b;
                }
            }

            List<float[]> ghosts = findGhosts();   // {x,y,r,score}
            lastDebug += " ghosts=" + ghosts.size();
            StringBuilder dbg = new StringBuilder();
            for (int i = 0; i < Math.min(3, ghosts.size()); i++) {
                float[] g = ghosts.get(i);
                dbg.append(" G").append(i).append("=(").append((int) g[0]).append(",").append((int) g[1])
                        .append(")R").append((int) g[2]).append("/").append(Math.round(g[3]));
            }
            for (Ball b : balls) {
                dbg.append(" b(").append((int) b.x).append(",").append((int) b.y).append(")w").append(b.white);
            }
            lastDebug += dbg.toString();
            sp.balls = balls;
            float best = 1e9f;
            for (float[] gh : ghosts) {
                Ball bt = null;
                double bg = 1e9;
                for (Ball b : balls) {
                    if (b == sp.cue) {
                        continue;
                    }
                    double d = Math.hypot(b.x - gh[0], b.y - gh[1]);
                    if (d < 1.3 * gh[2] || d > 3.1 * gh[2]) {
                        continue;
                    }
                    double gap = Math.abs(d - (b.r + gh[2]));
                    if (gap < bg) {
                        bg = gap;
                        bt = b;
                    }
                }
                if (bt == null || bg > 12) {
                    continue;
                }
                float sc = (float) bg - gh[3] * 0.02f;
                if (sc < best) {
                    best = sc;
                    sp.target = bt;
                    sp.ghostX = gh[0];
                    sp.ghostY = gh[1];
                    sp.ghostR = gh[2];
                }
            }

            if (!sp.ok()) {
                lastDebug += " pairFail";
                return sp;
            }

            // 目标球出射线
            float ux = sp.target.x - sp.ghostX, uy = sp.target.y - sp.ghostY;
            float L = (float) Math.hypot(ux, uy);
            if (L < 1f) {
                sp.target = null;
                return sp;
            }
            ux /= L;
            uy /= L;
            float reach = reachToRoi(sp.target.x, sp.target.y, ux, uy);
            sp.targetLine = new float[]{sp.target.x, sp.target.y, sp.target.x + ux * reach, sp.target.y + uy * reach};

            // 母球出射线（从虚球心发出）
            buildCueLine(sp, ux, uy);
            return sp;
        } catch (Throwable t) {
            Log.w(TAG, "detect failed", t);
            return null;
        }
    }

    // ---------- 掩膜 ----------
    private static void buildMasks() {
        // 台面颜色：ROI 内出现最多的颜色（4bit/通道）
        int[] hist = new int[4096];
        for (int y = Y0; y < Y1; y += 5) {
            for (int x = X0; x < X1; x += 5) {
                int c = sPx[y * W + x];
                int q = (((c >> 16) & 0xF0) << 4) | ((c >> 8) & 0xF0) | ((c & 0xF0) >> 4);
                hist[q]++;
            }
        }
        int bq = 0;
        for (int i = 0; i < 4096; i++) {
            if (hist[i] > hist[bq]) {
                bq = i;
            }
        }
        int cr = ((bq >> 8) & 0xF) << 4 | 8;
        int cg = ((bq >> 4) & 0xF) << 4 | 8;
        int cb = (bq & 0xF) << 4 | 8;
        sClothBr = luma(cr, cg, cb);
        float lineDelta = Math.max(22f, threshold);

        for (int y = 0; y < H; y++) {
            int row = y * W;
            for (int x = 0; x < W; x++) {
                int i = row + x;
                int c = sPx[i];
                float br = luma(c);
                sBr[i] = br;
                int dr = ((c >> 16) & 0xFF) - cr;
                int dg = ((c >> 8) & 0xFF) - cg;
                int db = (c & 0xFF) - cb;
                boolean notCloth = (dr * dr + dg * dg + db * db) > 9025;   // 95^2
                boolean inRoi = x >= X0 && x <= X1 && y >= Y0 && y <= Y1;
                sNotCloth[i] = inRoi && notCloth;
                sMask[i] = inRoi && (br - sClothBr) >= lineDelta;
            }
        }
    }

    private static boolean isWhitish(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return (mx - mn) < 45 && mx > 150;
    }

    // ---------- 实心球 ----------
    private static List<Ball> findBalls() {
        List<Ball> out = new ArrayList<>();
        int r = sRBall;
        int r2 = r * r;
        for (int cy = Y0 + r; cy <= Y1 - r; cy += 3) {
            for (int cx = X0 + r; cx <= X1 - r; cx += 3) {
                if (!sNotCloth[cy * W + cx]) {
                    continue;
                }
                int hit = 0, tot = 0, white = 0;
                for (int dy = -r; dy <= r; dy += 2) {
                    for (int dx = -r; dx <= r; dx += 2) {
                        if (dx * dx + dy * dy > r2) {
                            continue;
                        }
                        int i = (cy + dy) * W + (cx + dx);
                        tot++;
                        if (sNotCloth[i] && sBr[i] > 50) {
                            hit++;
                            if (isWhitish(sPx[i])) {
                                white++;
                            }
                        }
                    }
                }
                if (tot > 0 && hit * 100 / tot >= 72) {
                    Ball b = new Ball();
                    b.x = cx;
                    b.y = cy;
                    b.r = r;
                    b.white = white * 100 / tot;
                    out.add(b);
                }
            }
        }
        // 去重（保留白度高者）
        List<Ball> uniq = new ArrayList<>();
        for (Ball b : out) {
            Ball dup = null;
            for (Ball k : uniq) {
                if (Math.hypot(b.x - k.x, b.y - k.y) < r * 1.1) {
                    dup = k;
                    break;
                }
            }
            if (dup == null) {
                uniq.add(b);
            } else if (b.white > dup.white) {
                dup.x = b.x;
                dup.y = b.y;
                dup.white = b.white;
            }
        }
        return uniq;
    }

    /** 球心精修：梯度边界点均值（对线/高光稳健）。 */
    private static void refineCenter(Ball b) {
        float lo = b.r - 5, hi = b.r + 9;
        for (int it = 0; it < 5; it++) {
            double sx = 0, sy = 0;
            int cnt = 0;
            for (int i = 0; i < 72; i++) {
                double th = i * Math.PI / 36, cs = Math.cos(th), sn = Math.sin(th);
                double bgd = -1, brd = -1;
                for (double rad = lo; rad <= hi; rad += 0.75) {
                    int x = (int) Math.round(b.x + cs * rad), y = (int) Math.round(b.y + sn * rad);
                    if (x < 1 || y < 1 || x >= W - 1 || y >= H - 1) {
                        continue;
                    }
                    double gd = Math.abs(sBr[y * W + x + 1] - sBr[y * W + x - 1])
                            + Math.abs(sBr[(y + 1) * W + x] - sBr[(y - 1) * W + x]);
                    if (gd > bgd) {
                        bgd = gd;
                        brd = rad;
                    }
                }
                if (brd > 0 && bgd > 20) {
                    sx += b.x + cs * brd;
                    sy += b.y + sn * brd;
                    cnt++;
                }
            }
            if (cnt < 30) {
                break;
            }
            int nx = (int) Math.round(sx / cnt), ny = (int) Math.round(sy / cnt);
            if (nx == b.x && ny == b.y) {
                break;
            }
            b.x = nx;
            b.y = ny;
        }
    }

    // ---------- 虚球空心圆 ----------
    private static List<float[]> findGhosts() {
        List<float[]> peaks = new ArrayList<>();
        int r = sRBall;
        // 粗扫：stride 6，两个半径
        for (int cy = Y0 + r; cy <= Y1 - r; cy += 6) {
            for (int cx = X0 + r; cx <= X1 - r; cx += 6) {
                for (int k = -1; k <= 1; k += 2) {
                    int R = r + k * 2;
                    if (R < 10) {
                        continue;
                    }
                    float[] m = ringScore(cx, cy, R);
                    if (m[0] >= 0.58f && m[1] <= 0.42f) {
                        peaks.add(new float[]{cx, cy, R, m[0] * 100 - m[1] * 40});
                    }
                }
            }
        }
        // 局部细化 + 去重
        peaks.sort((a, b) -> Float.compare(b[3], a[3]));
        List<float[]> uniq = new ArrayList<>();
        for (float[] p : peaks) {
            boolean dup = false;
            for (float[] k : uniq) {
                if (Math.hypot(p[0] - k[0], p[1] - k[1]) < r * 1.6) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                uniq.add(refineGhost(p));
            }
            if (uniq.size() >= 10) {
                break;
            }
        }
        uniq.removeIf(g -> g[3] < 30f);
        return uniq;
    }

    /** 在候选附近小幅搜索最佳半径与圆心。 */
    private static float[] refineGhost(float[] p) {
        float bx = p[0], by = p[1], bR = p[2], bs = p[3];
        for (int dy = -4; dy <= 4; dy += 2) {
            for (int dx = -4; dx <= 4; dx += 2) {
                for (int dR = -3; dR <= 4; dR++) {
                    int R = (int) p[2] + dR;
                    if (R < 10) {
                        continue;
                    }
                    float[] m = ringScore((int) p[0] + dx, (int) p[1] + dy, R);
                    float sc = m[0] * 100 - m[1] * 40;
                    if (m[0] >= 0.55f && m[1] <= 0.45f && sc > bs) {
                        bs = sc;
                        bx = p[0] + dx;
                        by = p[1] + dy;
                        bR = R;
                    }
                }
            }
        }
        return new float[]{bx, by, bR, bs};
    }

    /** 返回 {环命中率, 内部命中率}：环上"比台面亮"的像素比例 / 内部的比例。 */
    private static float[] ringScore(int cx, int cy, int R) {
        int on = 0, tot = 0;
        for (int i = 0; i < 72; i++) {
            double th = i * Math.PI / 36;
            int x = (int) Math.round(cx + R * Math.cos(th));
            int y = (int) Math.round(cy + R * Math.sin(th));
            if (x < 0 || y < 0 || x >= W || y >= H) {
                continue;
            }
            tot++;
            if (sMask[y * W + x]) {
                on++;
            }
        }
        if (tot == 0) {
            return new float[]{0f, 1f};
        }
        float ringF = (float) on / tot;
        int inOn = 0, inTot = 0;
        int ir = Math.max(3, (int) (R * 0.5));
        for (int i = 0; i < 36; i++) {
            double th = i * Math.PI / 18;
            int x = (int) Math.round(cx + ir * Math.cos(th));
            int y = (int) Math.round(cy + ir * Math.sin(th));
            if (x < 0 || y < 0 || x >= W || y >= H) {
                continue;
            }
            inTot++;
            if (sMask[y * W + x]) {
                inOn++;
            }
        }
        float inF = inTot == 0 ? 1f : (float) inOn / inTot;
        return new float[]{ringF, inF};
    }

    // ---------- 母球出射线 ----------
    private static void buildCueLine(Spec sp, float tx, float ty) {
        float ax = sp.cue.x - sp.ghostX, ay = sp.cue.y - sp.ghostY;
        float al = (float) Math.hypot(ax, ay);
        if (al < 1f) {
            ax = -tx;
            ay = -ty;
            al = 1f;
        }
        ax /= al;
        ay /= al;
        float cos28 = (float) Math.cos(Math.toRadians(28));

        float bestScore = -1e9f, bux = 0, buy = 0;
        for (int a = 0; a < 360; a += 3) {
            double th = Math.toRadians(a);
            float ux = (float) Math.cos(th), uy = (float) Math.sin(th);
            // 排除瞄准线方向（虚球→母球）和连线方向（虚球→目标球）
            if (Math.abs(ux * ax + uy * ay) > cos28) {
                continue;
            }
            if (Math.abs(ux * tx + uy * ty) > cos28) {
                continue;
            }
            // 沿射线检查"细亮线"
            int start = sRBall + 4;
            int on = 0, tot = 0, thin = 0, thinTot = 0;
            float off = Math.max(4f, sRBall * 0.5f);
            float px = -uy, py = ux;
            for (int rr = start; rr < maxLen; rr += 4) {
                int x = (int) (sp.ghostX + ux * rr), y = (int) (sp.ghostY + uy * rr);
                if (x < X0 || y < Y0 || x > X1 || y > Y1) {
                    break;
                }
                tot++;
                if (sMask[y * W + x]) {
                    on++;
                    thinTot++;
                    int xa = (int) (x + px * off), ya = (int) (y + py * off);
                    int xb = (int) (x - px * off), yb = (int) (y - py * off);
                    boolean oa = xa >= 0 && ya >= 0 && xa < W && ya < H && sMask[ya * W + xa];
                    boolean ob = xb >= 0 && yb >= 0 && xb < W && yb < H && sMask[yb * W + xb];
                    if (!oa && !ob) {
                        thin++;
                    }
                }
            }
            if (tot < 12 || thinTot < 8) {
                continue;
            }
            float frac = (float) on / tot;
            float thinF = (float) thin / thinTot;
            if (frac < 0.6f || thinF < 0.7f) {
                continue;
            }
            float score = frac * 100f + Math.min(tot, 240) * 0.2f;
            if (score > bestScore) {
                bestScore = score;
                bux = ux;
                buy = uy;
            }
        }
        if (bestScore > -1e8f) {
            float reach = reachToRoi(sp.ghostX, sp.ghostY, bux, buy);
            sp.cueLine = new float[]{sp.ghostX, sp.ghostY, sp.ghostX + bux * reach, sp.ghostY + buy * reach};
            sp.cueLineDetected = true;
        } else {
            // 几何推算：垂直于连线方向，取"顺着母球来向"的那一侧
            float px = -ty, py = tx;
            float dot = px * (sp.cue.x - sp.ghostX) + py * (sp.cue.y - sp.ghostY);
            if (dot < 0) {
                px = -px;
                py = -py;
            }
            float reach = reachToRoi(sp.ghostX, sp.ghostY, px, py);
            sp.cueLine = new float[]{sp.ghostX, sp.ghostY, sp.ghostX + px * reach, sp.ghostY + py * reach};
            sp.cueLineDetected = false;
        }
    }

    private static float reachToRoi(float x, float y, float ux, float uy) {
        float reach = 0;
        for (int r = 0; r < maxLen + 600; r += 4) {
            float xx = x + ux * r, yy = y + uy * r;
            if (xx < X0 || yy < Y0 || xx > X1 || yy > Y1) {
                break;
            }
            reach = r;
        }
        return Math.max(80f, reach);
    }

    private static float luma(int c) {
        return (((c >> 16) & 0xFF) * 30 + ((c >> 8) & 0xFF) * 59 + (c & 0xFF) * 11) / 100f;
    }

    private static float luma(int r, int g, int b) {
        return (r * 30 + g * 59 + b * 11) / 100f;
    }
}
