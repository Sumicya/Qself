/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

/**
 * The resting glass pill, rewritten as an explicit three-stage pipeline:
 *
 * <ol>
 *   <li><b>Capture</b> — the backdrop pages behind the pill are drawn into a
 *       render node (GPU path) or a bitmap (CPU path), positioned so the pill
 *       window samples exactly what passes underneath.</li>
 *   <li><b>Blur</b> — the capture runs through a saturation lift and a blur,
 *       then a rim-only refraction lens; on hosts where the AGSL lens is
 *       rejected the capture is blurred on the CPU instead, so the pill still
 *       shows frosted content rather than a flat plate.</li>
 *   <li><b>Surface</b> — a faint material wash and a press highlight finish
 *       the plate; the rim outline clips everything.</li>
 * </ol>
 *
 * <p>Every stage reports through the feature journal; {@link GlassBlurProbe}
 * samples real pixels to prove the blur actually happened on device.
 */
final class GlassSurface extends View {

    /** Render pipeline flavour, journaled as glass.path=… */
    static final int PATH_GPU = 0;
    static final int PATH_CPU = 1;
    static final int PATH_FLAT = 2;

    private static final String[] PATH_NAME = {"gpu", "cpu", "flat"};

    /** Rim band, in dp, inside which the lens bends samples. */
    private static final float RIM_DP = 24f;
    /** Blur radius in dp. */
    private static final float BLUR_DP = 8f;
    /** Saturation multiplier applied to the blurred backdrop. */
    private static final float VIBRANCY = 1.5f;
    /** Container surface at 40% alpha, light and dark. */
    private static final int WASH_LIGHT = 0x66FAFAFA;
    private static final int WASH_DARK = 0x66121212;
    /** Press highlight: 8% flat wash plus a 15% radial at the droplet. */
    private static final float PRESS_WASH = 0.08f;
    private static final float PRESS_RIPPLE = 0.15f;
    /** CPU path refresh cadence and capture scale. */
    private static final int CPU_REFRESH_EVERY = 4;
    private static final float CPU_SAMPLE_SCALE = 0.5f;

    private final WeakReference<ViewGroup> backdropRef;
    private final float density;
    private final int samplePad;

    /* stage 1+2 GPU state */
    private final RenderNode captureNode = new RenderNode("qselfGlassCapture");
    private final RenderEffect vibrancy;
    private RuntimeShader lens;
    private RenderEffect chain;
    private int chainW = -1;
    private int chainH = -1;

    /* stage 1+2 CPU state */
    private Bitmap cpuBuffer;
    private final Paint cpuPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int frameCount;

    /* stage 3 state */
    private final Paint materialWash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeWash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient pressRipple;
    private final Path outline = new Path();
    private final Rect visibleRect = new Rect();
    private final int[] selfPos = new int[2];
    private final int[] childPos = new int[2];

    private int path = PATH_GPU;
    private boolean night;
    private int pageColor;
    private float press;
    private float pressX;

    GlassSurface(Context context, ViewGroup backdrop, float density,
                 boolean night) {
        super(context);
        this.backdropRef = new WeakReference<>(backdrop);
        this.density = density;
        this.samplePad = Math.round(RIM_DP * density);
        ColorMatrix lift = new ColorMatrix();
        lift.setSaturation(VIBRANCY);
        this.vibrancy = RenderEffect.createColorFilterEffect(
                new ColorMatrixColorFilter(lift));
        try {
            lens = new RuntimeShader(GlassShader.LENS_PROGRAM);
            path = PATH_GPU;
        } catch (Throwable t) {
            path = PATH_CPU;
            LiquidGlassModule.logErr("lens program rejected, cpu blur path", t);
        }
        setTheme(night);
        setWillNotDraw(false);
    }

    /** Which pipeline is painting the pill; journaled in install.ok. */
    String renderPathName() {
        return PATH_NAME[path];
    }

    void setTheme(boolean detectedNight) {
        night = GlassConfig.resolveNight(detectedNight);
        pageColor = GlassConfig.backgroundColor(night);
        invalidate();
    }

    /** Press progress and focal x (droplet centre), in local coordinates. */
    void setInteraction(float progress, float centreX) {
        if (press != progress || pressX != centreX) {
            press = progress;
            pressX = centreX;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // Zero contribution to WRAP_CONTENT; the host sizes this view once
        // the real EXACTLY spec arrives.
        setMeasuredDimension(
                MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY
                        ? MeasureSpec.getSize(widthSpec) : 0,
                MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                        ? MeasureSpec.getSize(heightSpec) : 0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        outline.reset();
        outline.addRoundRect(0, 0, w, h, h * 0.5f, h * 0.5f, Path.Direction.CW);
        chainW = -1;
    }

    /* -------------------------------------------------------------- draw */

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int alpha = GlassConfig.materialAlpha();
        if (alpha == 0) {
            return;
        }
        int layer = alpha == 255 ? canvas.save()
                : canvas.saveLayerAlpha(0, 0, w, h, alpha);
        try {
            boolean flat = GlassConfig.background != 0;
            if (!flat && canvas.isHardwareAccelerated()) {
                if (path == PATH_GPU) {
                    drawGpuBackdrop(canvas, w, h);
                } else if (path == PATH_CPU) {
                    drawCpuBackdrop(canvas, w, h);
                }
            }
            materialWash.setColor(flat
                    ? GlassConfig.backgroundColor(night)
                    : (night ? WASH_DARK : WASH_LIGHT));
            canvas.drawRoundRect(0, 0, w, h, h * 0.5f, h * 0.5f, materialWash);
            drawPressHighlight(canvas, w, h);
        } finally {
            canvas.restoreToCount(layer);
        }
    }

    /** Stage 1+2, GPU: capture into the render node, blur+lens the node. */
    private void drawGpuBackdrop(Canvas canvas, int w, int h) {
        int captureW = w + samplePad * 2;
        int captureH = h + samplePad * 2;
        boolean captured;
        captureNode.setPosition(0, 0, captureW, captureH);
        RecordingCanvas rc = captureNode.beginRecording(captureW, captureH);
        try {
            captured = captureInto(rc, captureW, captureH,
                    ViewGeom.cumulativeScale(this));
        } catch (Throwable t) {
            captured = false;
            demote("gpu-capture", t);
        } finally {
            captureNode.endRecording();
        }
        if (!captured) {
            return;
        }
        try {
            if (chain == null || chainW != w || chainH != h) {
                buildChain(w, h, h * 0.5f);
            }
            captureNode.setRenderEffect(chain);
            canvas.save();
            canvas.clipPath(outline);
            canvas.translate(-samplePad, -samplePad);
            canvas.drawRenderNode(captureNode);
            canvas.restore();
        } catch (Throwable t) {
            demote("gpu-draw", t);
        }
    }

    /** Demotes the pipeline one rung; the journal records the first fall. */
    private void demote(String stage, Throwable t) {
        path = path == PATH_GPU ? PATH_CPU : PATH_FLAT;
        LiquidGlassModule.logErr(stage + " failed, path=" + renderPathName(), t);
        sumicya.qself.diagnostics.FeatureJournal.record("GLASS",
                "blur.fail", "reason=" + stage + " "
                        + t.getClass().getSimpleName());
    }

    /** Stage 1+2, CPU: capture into a bitmap, blur it, draw it back. */
    private void drawCpuBackdrop(Canvas canvas, int w, int h) {
        try {
            frameCount++;
            int bw = Math.max(1, Math.round((w + samplePad * 2)
                    * CPU_SAMPLE_SCALE));
            int bh = Math.max(1, Math.round((h + samplePad * 2)
                    * CPU_SAMPLE_SCALE));
            boolean refresh = cpuBuffer == null || cpuBuffer.isRecycled()
                    || cpuBuffer.getWidth() != bw
                    || cpuBuffer.getHeight() != bh
                    || frameCount % CPU_REFRESH_EVERY == 0;
            if (refresh) {
                if (cpuBuffer == null || cpuBuffer.isRecycled()
                        || cpuBuffer.getWidth() != bw
                        || cpuBuffer.getHeight() != bh) {
                    if (cpuBuffer != null && !cpuBuffer.isRecycled()) {
                        cpuBuffer.recycle();
                    }
                    cpuBuffer = Bitmap.createBitmap(bw, bh,
                            Bitmap.Config.ARGB_8888);
                }
                Canvas c = new Canvas(cpuBuffer);
                c.scale(CPU_SAMPLE_SCALE, CPU_SAMPLE_SCALE);
                if (captureInto(c, w + samplePad * 2, h + samplePad * 2)) {
                    int radius = Math.max(1,
                            Math.round(BLUR_DP * density * CPU_SAMPLE_SCALE));
                    GlassBlurProbe.boostSaturation(cpuBuffer);
                    StackBlur.blur(cpuBuffer, radius);
                }
            }
            if (cpuBuffer == null || cpuBuffer.isRecycled()) {
                return;
            }
            canvas.save();
            canvas.clipPath(outline);
            canvas.drawBitmap(cpuBuffer, null,
                    new android.graphics.RectF(-samplePad, -samplePad,
                            w + samplePad, h + samplePad),
                    cpuPaint);
            canvas.restore();
        } catch (Throwable t) {
            path = PATH_FLAT;
            LiquidGlassModule.logErr("cpu glass failed, flat fallback", t);
            sumicya.qself.diagnostics.FeatureJournal.record("GLASS",
                    "blur.fail", "reason=cpu " + t.getClass().getSimpleName());
        }
    }

    /**
     * Stage 1 shared by both paths: draws the backdrop pages that overlap the
     * pill into the given canvas, already shifted so the pill window lands on
     * (samplePad, samplePad). Returns false when nothing was captured.
     */
    private boolean captureInto(RecordingCanvas rc, int captureW, int captureH,
                                float drawScale) {
        ViewGroup pager = backdropRef.get();
        if (pager == null || pager.getWidth() <= 0) {
            return false;
        }
        if (Math.abs(drawScale - 1f) > 0.001f) {
            rc.scale(1f / drawScale, 1f / drawScale,
                    captureW * 0.5f, captureH * 0.5f);
        }
        // Gaps the pages do not cover must not blur into transparent black.
        rc.drawColor(pageColor);
        if (!ViewGeom.unscaledScreenPos(this, selfPos)) {
            getLocationOnScreen(selfPos);
        }
        boolean captured = false;
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            if (page.getVisibility() != VISIBLE
                    || !page.getGlobalVisibleRect(visibleRect)
                    || visibleRect.isEmpty()) {
                continue;
            }
            page.getLocationOnScreen(childPos);
            float dx = samplePad - (selfPos[0] - childPos[0]);
            float dy = samplePad - (selfPos[1] - childPos[1]);
            int save = rc.save();
            rc.translate(dx, dy);
            rc.clipRect(-dx, -dy, -dx + captureW, -dy + captureH);
            page.draw(rc);
            rc.restoreToCount(save);
            captured = true;
        }
        if (!captured) {
            pager.getLocationOnScreen(childPos);
            rc.translate(samplePad - (selfPos[0] - childPos[0]),
                    samplePad - (selfPos[1] - childPos[1]));
            pager.draw(rc);
            captured = true;
        }
        return captured;
    }

    /** CPU-path capture into a plain canvas shares the same geometry. */
    private boolean captureInto(Canvas c, int captureW, int captureH) {
        ViewGroup pager = backdropRef.get();
        if (pager == null || pager.getWidth() <= 0) {
            return false;
        }
        if (!ViewGeom.unscaledScreenPos(this, selfPos)) {
            getLocationOnScreen(selfPos);
        }
        c.drawColor(pageColor);
        boolean captured = false;
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            if (page.getVisibility() != VISIBLE
                    || !page.getGlobalVisibleRect(visibleRect)
                    || visibleRect.isEmpty()) {
                continue;
            }
            page.getLocationOnScreen(childPos);
            float dx = samplePad - (selfPos[0] - childPos[0]);
            float dy = samplePad - (selfPos[1] - childPos[1]);
            int save = c.save();
            c.translate(dx, dy);
            c.clipRect(-dx, -dy, -dx + captureW, -dy + captureH);
            page.draw(c);
            c.restoreToCount(save);
            captured = true;
        }
        return captured;
    }

    /** Blur + lens + vibrancy chain, rebuilt when the pill resizes. */
    private void buildChain(int w, int h, float radius) {
        lens.setFloatUniform("size", (float) w, (float) h);
        lens.setFloatUniform("offset", (float) -samplePad, (float) -samplePad);
        lens.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        lens.setFloatUniform("refractionHeight", RIM_DP * density);
        lens.setFloatUniform("refractionAmount", -RIM_DP * density);
        lens.setFloatUniform("depthEffect", 0f);
        float blurRadius = BLUR_DP * density;
        chain = RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(lens, "content"),
                RenderEffect.createBlurEffect(blurRadius, blurRadius,
                        vibrancy, Shader.TileMode.CLAMP));
        chainW = w;
        chainH = h;
    }

    /** Stage 3: press highlight, verbatim InteractiveHighlight recipe. */
    private void drawPressHighlight(Canvas canvas, int w, int h) {
        if (press <= 0.01f) {
            return;
        }
        int save = canvas.save();
        canvas.clipPath(outline);
        edgeWash.setColor(0xFFFFFFFF);
        edgeWash.setAlpha(Math.round(0xFF * PRESS_WASH * press));
        edgeWash.setBlendMode(BlendMode.PLUS);
        canvas.drawRect(0, 0, w, h, edgeWash);
        float cx = pressX > 0f ? Math.min(Math.max(pressX, 0f), w)
                : w * 0.5f;
        float radius = Math.min(w, h) * 1.5f;
        int core = Math.round(0xFF * PRESS_RIPPLE * press);
        pressRipple = new RadialGradient(cx, h * 0.5f, radius,
                new int[]{(core << 24) | 0x00FFFFFF,
                        (core << 24) | 0x00FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        ripplePaint.setShader(pressRipple);
        ripplePaint.setBlendMode(BlendMode.PLUS);
        canvas.drawRect(0, 0, w, h, ripplePaint);
        canvas.restoreToCount(save);
    }

    /* ------------------------------------------------------------- probe */

    /** Screen position of the pill window, for the blur probe. */
    boolean locateOnScreen(int[] out) {
        if (!isAttachedToWindow() || getWidth() <= 0) {
            return false;
        }
        getLocationOnScreen(out);
        return true;
    }

    int samplePadPx() {
        return samplePad;
    }

    /**
     * Draws the raw (unblurred) backdrop region into the given canvas at the
     * capture geometry, so the probe can measure what there is to blur.
     */
    boolean captureRaw(Canvas c, int captureW, int captureH) {
        return captureInto(c, captureW, captureH);
    }
}
