/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

/**
 * The resting glass pill. It captures whatever pager pages pass behind it
 * into a display list, runs the capture through a saturation lift, a blur
 * and a rim-only refraction lens, then covers it with a faint wash plus a
 * press highlight. Without hardware AGSL it paints a flat tinted material.
 */
final class LiquidGlassPanel extends View {

    /** Rim band, in dp, inside which the lens bends samples; the centre passes through. */
    private static final float RIM_DP = 24f;
    /** Hardware blur radius in dp. */
    private static final float BLUR_DP = 4f;
    /** Saturation multiplier applied to the blurred backdrop. */
    private static final float VIBRANCY = 1.5f;
    private static final int WASH_LIGHT = 0x66F2F2F7;
    private static final int WASH_DARK = 0x662C2C2E;
    private static final float PRESS_WASH = 0.06f;
    private static final float PRESS_BLOOM = 0.12f;

    /**
     * Signed-distance helpers reused by the droplet's own programs; the
     * function names are therefore the renderer-internal contract.
     */
    static final String SDF_SOURCE = ""
            + "float radiusAt(float2 p, float4 radii) {\n"
            + "    if (p.x < 0.0) return p.y > 0.0 ? radii.w : radii.x;\n"
            + "    return p.y > 0.0 ? radii.z : radii.y;\n"
            + "}\n"
            + "float sdRoundedRect(float2 p, float2 halfExtent, float radius) {\n"
            + "    float2 q = abs(p) - halfExtent + radius;\n"
            + "    float outside = length(max(q, 0.0)) - radius;\n"
            + "    float inside = min(max(q.x, q.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 p, float2 halfExtent, float radius) {\n"
            + "    float2 q = abs(p) - halfExtent + radius;\n"
            + "    float2 corner = max(q, 0.0);\n"
            + "    if (dot(corner, corner) > 0.0) return sign(p) * normalize(corner);\n"
            + "    float2 g = sign(p);\n"
            + "    if (q.x >= q.y) { g.y = 0.0; } else { g.x = 0.0; }\n"
            + "    return g;\n"
            + "}\n";

    private static final String LENS_PROGRAM = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + SDF_SOURCE
            + "half4 main(float2 coord) {\n"
            + "    float2 halfExtent = size * 0.5;\n"
            + "    float2 local = (coord + offset) - halfExtent;\n"
            + "    float radius = radiusAt(coord, cornerRadii);\n"
            + "    float dist = sdRoundedRect(local, halfExtent, radius);\n"
            + "    if (-dist >= refractionHeight) return content.eval(coord);\n"
            + "    float rim = clamp(-dist / refractionHeight, 0.0, 1.0);\n"
            + "    float bend = 1.0 - sqrt(max(0.0, 1.0 - rim * rim));\n"
            + "    float gradLimit = min(radius * 1.5, min(halfExtent.x, halfExtent.y));\n"
            + "    float2 normal = normalize(gradSdRoundedRect(local, halfExtent, gradLimit)\n"
            + "                             + depthEffect * normalize(local));\n"
            + "    return content.eval(coord + bend * refractionAmount * normal);\n"
            + "}\n";

    private static final String BLOOM_PROGRAM = ""
            + "uniform float2 bounds;\n"
            + "uniform float2 center;\n"
            + "uniform float reach;\n"
            + "uniform float strength;\n"
            + "half4 main(float2 coord) {\n"
            + "    float near = smoothstep(reach, reach * 0.5, distance(coord, center));\n"
            + "    half a = half(strength * near);\n"
            + "    return half4(a, a, a, a);\n"
            + "}\n";

    private final WeakReference<ViewGroup> backdropRef;
    private final float density;
    private final int samplePad;

    private final Paint materialWash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeWash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Rect visibleRect = new Rect();
    private final int[] selfPos = new int[2];
    private final int[] childPos = new int[2];

    private final RenderNode captureNode = new RenderNode("qselfGlassCapture");
    private final RenderNode embeddedNode = new RenderNode("qselfGlassEmbedded");
    private final RenderEffect vibrancy;
    private RuntimeShader lens;
    private RuntimeShader bloom;
    private RenderEffect chain;
    private int chainW = -1;
    private int chainH = -1;
    private boolean usable;

    private boolean night;
    private int pageColor;
    private float press;
    private float pressX;

    LiquidGlassPanel(Context context, ViewGroup backdrop, float density, boolean night) {
        super(context);
        this.backdropRef = new WeakReference<>(backdrop);
        this.density = density;
        this.samplePad = Math.round(RIM_DP * density);
        ColorMatrix lift = new ColorMatrix();
        lift.setSaturation(VIBRANCY);
        this.vibrancy = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(lift));
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                lens = new RuntimeShader(LENS_PROGRAM);
                bloom = new RuntimeShader(BLOOM_PROGRAM);
                usable = true;
            } catch (Throwable t) {
                usable = false;
                LiquidGlassModule.logErr("lens program rejected", t);
            }
        }
        setTheme(night);
        setWillNotDraw(false);
    }

    void setTheme(boolean detectedNight) {
        night = GlassConfig.resolveNight(detectedNight);
        pageColor = GlassConfig.backgroundColor(night);
        invalidate();
    }

    boolean isSupported() {
        return usable;
    }

    /** Press bloom strength and focal x, in local coordinates. */
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
                MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(widthSpec) : 0,
                MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : 0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        outline.reset();
        float radius = h * 0.5f;
        outline.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        render(canvas, w, h, h * 0.5f, captureNode, ViewGeom.cumulativeScale(this));
    }

    /** Paint the resting material into the droplet's captured surface. */
    void drawEmbedded(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        render(canvas, w, h, h * 0.5f, embeddedNode, 1f);
    }

    private void render(Canvas canvas, int w, int h, float radius,
                        RenderNode node, float drawScale) {
        int alpha = GlassConfig.materialAlpha();
        if (alpha == 0) {
            return;
        }
        int layer = alpha == 255 ? canvas.save() : canvas.saveLayerAlpha(0, 0, w, h, alpha);
        boolean live = GlassConfig.background == 0 && usable && canvas.isHardwareAccelerated();
        if (live) {
            try {
                renderLiveBackdrop(canvas, w, h, radius, node, drawScale);
            } catch (Throwable t) {
                usable = false;
                LiquidGlassModule.logErr("live glass failed, flat fallback", t);
            }
        }
        materialWash.setColor(GlassConfig.background == 0
                ? (night ? WASH_DARK : WASH_LIGHT) : GlassConfig.backgroundColor(night));
        canvas.drawRoundRect(0, 0, w, h, radius, radius, materialWash);
        paintPressHighlight(canvas, w, h);
        canvas.restoreToCount(layer);
    }

    private void paintPressHighlight(Canvas canvas, int w, int h) {
        if (press <= 0.01f || bloom == null) {
            return;
        }
        int save = canvas.save();
        canvas.clipPath(outline);
        edgeWash.setColor(0xFFFFFFFF);
        edgeWash.setAlpha(Math.round(0xFF * PRESS_WASH * press));
        edgeWash.setBlendMode(BlendMode.PLUS);
        canvas.drawRect(0, 0, w, h, edgeWash);
        bloom.setFloatUniform("bounds", (float) w, (float) h);
        bloom.setFloatUniform("center", Math.max(0f, Math.min(pressX, w)), h * 0.5f);
        bloom.setFloatUniform("reach", Math.min(w, h) * 1.2f);
        bloom.setFloatUniform("strength", PRESS_BLOOM * press);
        bloomPaint.setShader(bloom);
        bloomPaint.setBlendMode(BlendMode.PLUS);
        canvas.drawRect(0, 0, w, h, bloomPaint);
        canvas.restoreToCount(save);
    }

    private void renderLiveBackdrop(Canvas canvas, int w, int h, float radius,
                                    RenderNode node, float drawScale) {
        ViewGroup pager = backdropRef.get();
        if (pager == null || pager.getWidth() <= 0) {
            return;
        }
        int captureW = w + samplePad * 2;
        int captureH = h + samplePad * 2;
        node.setPosition(0, 0, captureW, captureH);

        if (!ViewGeom.unscaledScreenPos(this, selfPos)) {
            getLocationOnScreen(selfPos);
        }

        RecordingCanvas rc = node.beginRecording(captureW, captureH);
        try {
            if (Math.abs(drawScale - 1f) > 0.001f) {
                rc.scale(1f / drawScale, 1f / drawScale, captureW * 0.5f, captureH * 0.5f);
            }
            // Gaps the pages do not cover must not blur into transparent black.
            rc.drawColor(pageColor);
            boolean captured = false;
            for (int i = 0; i < pager.getChildCount(); i++) {
                View page = pager.getChildAt(i);
                if (page.getVisibility() != VISIBLE
                        || !page.getGlobalVisibleRect(visibleRect) || visibleRect.isEmpty()) {
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
            }
        } finally {
            node.endRecording();
        }

        if (chain == null || chainW != w || chainH != h) {
            lens.setFloatUniform("size", (float) w, (float) h);
            lens.setFloatUniform("offset", (float) -samplePad, (float) -samplePad);
            lens.setFloatUniform("cornerRadii", radius, radius, radius, radius);
            lens.setFloatUniform("refractionHeight", RIM_DP * density);
            lens.setFloatUniform("refractionAmount", -RIM_DP * density);
            lens.setFloatUniform("depthEffect", 0f);
            float blurRadius = BLUR_DP * density;
            chain = RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(lens, "content"),
                    RenderEffect.createBlurEffect(blurRadius, blurRadius, vibrancy, Shader.TileMode.CLAMP));
            chainW = w;
            chainH = h;
        }
        node.setRenderEffect(chain);

        canvas.save();
        canvas.clipPath(outline);
        canvas.translate(-samplePad, -samplePad);
        canvas.drawRenderNode(node);
        canvas.restore();
    }
}
