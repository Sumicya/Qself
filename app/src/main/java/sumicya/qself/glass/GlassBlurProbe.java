/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.Window;

import java.util.concurrent.atomic.AtomicBoolean;

import sumicya.qself.diagnostics.FeatureJournal;

/**
 * On-device proof that the glass actually blurs. Install milestones only show
 * that the pill was laid out; this probe measures pixels:
 *
 * <ol>
 *   <li>captures the raw backdrop region the pill refracts,</li>
 *   <li>blurs the same capture on the CPU and measures how much detail the
 *       blur removes,</li>
 *   <li>reads the composited screen pixels under the pill with
 *       {@link PixelCopy} and measures what the user really sees.</li>
 * </ol>
 *
 * <p>The journal then carries real numbers: {@code GLASS blur.probe
 * raw=… blurred=… screen=… softening=… screenSoft=…}. A missing or empty
 * capture, a blur that removes nothing, or a failed pixel read each record a
 * {@code blur.fail} with the reason — blur can no longer ship unverified.
 */
final class GlassBlurProbe {

    /** Longest edge of the probe bitmaps; enough for an energy measure. */
    private static final int PROBE_MAX_EDGE = 160;
    /** A capture flatter than this has nothing to blur. */
    private static final float MIN_RAW_ENERGY = 0.5f;
    /** Blur must remove at least this much detail to count. */
    private static final float MIN_SOFTENING = 0.2f;
    private static final long PROBE_DELAY_MS = 900L;

    private static final AtomicBoolean fired = new AtomicBoolean(false);
    private static HandlerThread probeThread;
    private static Handler probeHandler;

    private GlassBlurProbe() {
    }

    /** Fires once, shortly after install, when frames have settled. */
    static void verify(GlassSurface surface) {
        if (!fired.compareAndSet(false, true)) {
            return;
        }
        surface.postDelayed(() -> run(surface), PROBE_DELAY_MS);
    }

    private static void run(GlassSurface surface) {
        try {
            int[] pos = new int[2];
            int w = surface.getWidth();
            int h = surface.getHeight();
            if (!surface.locateOnScreen(pos) || w <= 0 || h <= 0) {
                FeatureJournal.record("GLASS", "blur.fail",
                        "reason=not-attached w=" + w + " h=" + h);
                return;
            }
            int pad = surface.samplePadPx();
            int captureW = w + pad * 2;
            int captureH = h + pad * 2;
            float scale = Math.min(1f,
                    (float) PROBE_MAX_EDGE / Math.max(captureW, captureH));
            int bw = Math.max(2, Math.round(captureW * scale));
            int bh = Math.max(2, Math.round(captureH * scale));

            // 1) raw capture: what is there to blur?
            Bitmap raw = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(raw);
            c.scale(scale, scale);
            boolean captured;
            try {
                captured = surface.captureRaw(c, captureW, captureH);
            } catch (Throwable t) {
                captured = false;
            }
            float rawEnergy = captured ? gradientEnergy(raw) : 0f;
            if (!captured || rawEnergy < MIN_RAW_ENERGY) {
                raw.recycle();
                FeatureJournal.record("GLASS", "blur.fail",
                        "reason=empty-capture path="
                                + surface.renderPathName()
                                + " raw=" + round(rawEnergy));
                return;
            }

            // 2) CPU blur of the same capture: does the blur math work?
            Bitmap blurred = raw.copy(Bitmap.Config.ARGB_8888, true);
            boostSaturation(blurred);
            long t0 = SystemClock.uptimeMillis();
            StackBlur.blur(blurred, Math.max(2, Math.round(
                    8f * surface.getResources().getDisplayMetrics().density
                            * scale)));
            long blurMs = SystemClock.uptimeMillis() - t0;
            float blurredEnergy = gradientEnergy(blurred);
            float softening = 1f - blurredEnergy / rawEnergy;
            blurred.recycle();
            if (softening < MIN_SOFTENING) {
                raw.recycle();
                FeatureJournal.record("GLASS", "blur.fail",
                        "reason=no-effect path=" + surface.renderPathName()
                                + " raw=" + round(rawEnergy)
                                + " blurred=" + round(blurredEnergy));
                return;
            }
            raw.recycle();

            // 3) what the user actually sees: composited pixels of the pill.
            copyScreenRegion(surface, pos, w, h, bw, bh, rawEnergy, blurMs,
                    softening);
        } catch (Throwable t) {
            FeatureJournal.record("GLASS", "blur.fail",
                    "reason=probe " + t.getClass().getSimpleName());
        }
    }

    /**
     * PixelCopy of the pill's screen region, then energy comparison. The source
     * rect is translated into window coordinates, because {@link PixelCopy}
     * reads a window, not a view.
     */
    private static void copyScreenRegion(GlassSurface surface, int[] screenPos,
                                         int w, int h, int bw, int bh,
                                         float rawEnergy, long blurMs,
                                         float softening) {
        Window window = windowOf(surface.getContext());
        Bitmap screen = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (window == null) {
            screen.recycle();
            FeatureJournal.record("GLASS", "blur.probe",
                    "path=" + surface.renderPathName()
                            + " raw=" + round(rawEnergy)
                            + " softening=" + round(softening)
                            + " blurMs=" + blurMs + " pixcopy=no-window");
            return;
        }
        int[] windowPos = new int[2];
        window.getDecorView().getLocationOnScreen(windowPos);
        int left = screenPos[0] - windowPos[0];
        int top = screenPos[1] - windowPos[1];
        int dw = Math.max(1, window.getDecorView().getWidth());
        int dh = Math.max(1, window.getDecorView().getHeight());
        left = Math.max(0, Math.min(left, dw - 1));
        top = Math.max(0, Math.min(top, dh - 1));
        int cw = Math.min(w, dw - left);
        int ch = Math.min(h, dh - top);
        if (cw < 2 || ch < 2) {
            screen.recycle();
            FeatureJournal.record("GLASS", "blur.probe",
                    "path=" + surface.renderPathName()
                            + " raw=" + round(rawEnergy)
                            + " pixcopy=clipped");
            return;
        }
        if (cw != w || ch != h) {
            screen.recycle();
            screen = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        }
        Rect source = new Rect(left, top, left + cw, top + ch);
        final Bitmap capture = screen;
        try {
            PixelCopy.request(window, source, capture, result -> {
                try {
                    if (result != PixelCopy.SUCCESS) {
                        FeatureJournal.record("GLASS", "blur.probe",
                                "path=" + surface.renderPathName()
                                        + " raw=" + round(rawEnergy)
                                        + " softening=" + round(softening)
                                        + " blurMs=" + blurMs
                                        + " pixcopy=err" + result);
                        return;
                    }
                    Bitmap scaled = Bitmap.createScaledBitmap(
                            capture, bw, bh, true);
                    float screenEnergy = gradientEnergy(scaled);
                    scaled.recycle();
                    float screenSoft = 1f - screenEnergy / rawEnergy;
                    FeatureJournal.record("GLASS", "blur.probe",
                            "path=" + surface.renderPathName()
                                    + " raw=" + round(rawEnergy)
                                    + " softening=" + round(softening)
                                    + " screen=" + round(screenEnergy)
                                    + " screenSoft=" + round(screenSoft)
                                    + " blurMs=" + blurMs + " pixcopy=ok");
                } catch (Throwable t) {
                    FeatureJournal.record("GLASS", "blur.probe",
                            "path=" + surface.renderPathName()
                                    + " pixcopy=decode-fail");
                } finally {
                    capture.recycle();
                }
            }, handler());
        } catch (Throwable t) {
            screen.recycle();
            FeatureJournal.record("GLASS", "blur.probe",
                    "path=" + surface.renderPathName()
                            + " raw=" + round(rawEnergy)
                            + " softening=" + round(softening)
                            + " blurMs=" + blurMs + " pixcopy=rejected");
        }
    }

    /** The window that hosts the pill; the view's context chain leads to it. */
    private static Window windowOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return ((Activity) current).getWindow();
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static synchronized Handler handler() {
        if (probeThread == null || probeThread.isInterrupted()) {
            probeThread = new HandlerThread("qselfBlurProbe");
            probeThread.start();
            probeHandler = new Handler(probeThread.getLooper());
        }
        return probeHandler;
    }

    /* -------------------------------------------------------- math utils */

    /** Mean absolute luminance gradient: higher means more visible detail. */
    static float gradientEnergy(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        if (w < 2 || h < 2) {
            return 0f;
        }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        long sum = 0;
        long n = 0;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int l = luminance(px[row + x]);
                if (x + 1 < w) {
                    sum += Math.abs(l - luminance(px[row + x + 1]));
                    n++;
                }
                if (y + 1 < h) {
                    sum += Math.abs(l - luminance(px[row + x + w]));
                    n++;
                }
            }
        }
        return n == 0 ? 0f : (float) sum / n;
    }

    private static int luminance(int argb) {
        return ((argb >> 16) & 0xFF) * 299
                + ((argb >> 8) & 0xFF) * 587
                + (argb & 0xFF) * 114;
    }

    /** Gentle saturation lift so the frosted content keeps its colour. */
    static void boostSaturation(Bitmap bitmap) {
        Bitmap tmp = Bitmap.createBitmap(bitmap.getWidth(),
                bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        ColorMatrix lift = new ColorMatrix();
        lift.setSaturation(1.08f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(lift));
        new Canvas(tmp).drawBitmap(bitmap, 0f, 0f, p);
        new Canvas(bitmap).drawBitmap(tmp, 0f, 0f, null);
        tmp.recycle();
    }

    private static String round(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
