package cn.mhook.overlay;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

/**
 * 屏幕叠加辅助（实验）：用无障碍截图拿帧（无需录屏提示），交给 AssistCore 做识别，
 * 再由悬浮窗把延长线画出来。
 *
 * 只做"个人/自己的设备上辅助"，不改第三方 App。
 */
public class AssistService extends AccessibilityService {

    private static final String TAG = "AssistService";
    private static AssistService sInstance;
    private static final Object LOCK = new Object();
    private volatile boolean sBusy;
    private volatile long sBusyAt;
    private volatile long sLastFailLog;

    public static boolean isConnected() {
        return sInstance != null;
    }

    public static AssistService get() {
        return sInstance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "connected, canTakeScreenshot=" + (Build.VERSION.SDK_INT >= 30));
        AssistCore.onServiceReady(this);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        AssistCore.onServiceGone();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只用来拿截图能力，不做节点分析
    }

    @Override
    public void onInterrupt() {
    }

    /** 抓一帧（异步）；结果回调到 AssistCore。同一时刻只允许一帧在跑。 */
    public void requestFrame() {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        if (sBusy) {
            // 超时兜底：极端情况下回调不来会一直卡住，导致后续再也没有帧
            if (System.currentTimeMillis() - sBusyAt < 3000) {
                return;
            }
            sBusy = false;
        }
        sBusy = true;
        sBusyAt = System.currentTimeMillis();
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    Bitmap bmp = null;
                    try {
                        HardwareBuffer hb = result.getHardwareBuffer();
                        ColorSpace cs = result.getColorSpace();
                        Bitmap wrapped = Bitmap.wrapHardwareBuffer(hb, cs);
                        if (wrapped != null) {
                            bmp = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                            wrapped.recycle();
                            // 直接缩到一半交给识别（省内存 + 更快）
                            if (bmp != null) {
                                try {
                                    int w = bmp.getWidth(), h = bmp.getHeight();
                                    Bitmap small = Bitmap.createScaledBitmap(bmp,
                                            Math.max(1, w / 2), Math.max(1, h / 2), true);
                                    if (small != null && small != bmp) {
                                        bmp.recycle();
                                        bmp = small;
                                    }
                                } catch (Throwable t) {
                                    Log.w(TAG, "downscale failed, use full", t);
                                }
                            }
                        }
                        if (hb != null) {
                            hb.close();
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "frame decode failed", t);
                    } finally {
                        sBusy = false;
                    }
                    if (bmp != null) {
                        AssistCore.onFrame(bmp);
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    sBusy = false;
                    long now = System.currentTimeMillis();
                    if (now - sLastFailLog > 5000) {
                        sLastFailLog = now;
                        Log.w(TAG, "takeScreenshot failed code=" + errorCode);
                    }
                }
            });
        } catch (Throwable t) {
            sBusy = false;
            Log.w(TAG, "takeScreenshot failed", t);
        }
    }
}
