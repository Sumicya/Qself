/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * The moving selected-tab droplet, a View-world port of Kyant0/AndroidLiquidGlass
 * {@code LiquidBottomTabs} (Apache-2.0, see app/NOTICE). Three things are
 * composited, and every press-only piece fades with {@code progress}:
 *
 * <ol>
 *   <li>a combined backdrop: live pager pages plus a 1:1 recording of the tab
 *       row tinted to the QQ accent (the glyph that "flows" between slots),
 *       bent at the rim by an edge-only lens with chromatic aberration;</li>
 *   <li>directional edge specular, inner/outer shadow and a finger-following
 *       white highlight while pressed;</li>
 *   <li>a neutral surface wash: 10% black (light) / white (dark) at rest
 *       cross-fading to 3% black while pressed.</li>
 * </ol>
 *
 * Content is never scaled down: all recordings are 1:1. The view's own
 * press scale (applied by {@link DropletDragController}) magnifies the
 * composited result around its centre, exactly like the upstream layer.
 *
 * <p>On pre-API 33 devices (no AGSL) it falls back to a flat accent range
 * block with a local ripple.
 */
final class DropletPanel extends View {

    // ---- AGSL programs ------------------------------------------------
    // The refraction/dispersion/edge-highlight shader strings below are
    // derived from Kyant0/AndroidLiquidGlass (android branch),
    // Copyright 2025 Kyant, licensed under the Apache License 2.0.
    // Dispersion and the hairline rim light are the upstream signature.
    private static final String DISPERSION_PROGRAM = ""
            + GlassShader.SDF_SOURCE
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + "uniform float chromaticAberration;\n"
            + "float circleMap(float x) {\n"
            + "    return 1.0 - sqrt(1.0 - x * x);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = (coord + offset) - halfSize;\n"
            + "    float radius = radiusAt(centeredCoord, cornerRadii);\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            + "    if (-sd >= refractionHeight) {\n"
            + "        return content.eval(coord);\n"
            + "    }\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)\n"
            + "                             + depthEffect * normalize(centeredCoord));\n"
            + "    float2 refractedCoord = coord + d * grad;\n"
            + "    float dispersionIntensity = chromaticAberration\n"
            + "            * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n"
            + "    float2 dispersedCoord = d * grad * dispersionIntensity;\n"
            + "    half4 color = half4(0.0);\n"
            + "    half4 red = content.eval(refractedCoord + dispersedCoord);\n"
            + "    color.r += red.r / 3.5;\n"
            + "    color.a += red.a / 7.0;\n"
            + "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n"
            + "    color.r += orange.r / 3.5;\n"
            + "    color.g += orange.g / 7.0;\n"
            + "    color.a += orange.a / 7.0;\n"
            + "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n"
            + "    color.r += yellow.r / 3.5;\n"
            + "    color.g += yellow.g / 3.5;\n"
            + "    color.a += yellow.a / 7.0;\n"
            + "    half4 green = content.eval(refractedCoord);\n"
            + "    color.g += green.g / 3.5;\n"
            + "    color.a += green.a / 7.0;\n"
            + "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n"
            + "    color.g += cyan.g / 3.5;\n"
            + "    color.b += cyan.b / 3.0;\n"
            + "    color.a += cyan.a / 7.0;\n"
            + "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n"
            + "    color.b += blue.b / 3.0;\n"
            + "    color.a += blue.a / 7.0;\n"
            + "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n"
            + "    color.r += purple.r / 7.0;\n"
            + "    color.b += purple.b / 3.0;\n"
            + "    color.a += purple.a / 7.0;\n"
            + "    return color;\n"
            + "}\n";

    private static final String EDGE_HIGHLIGHT_PROGRAM = ""
            + GlassShader.SDF_SOURCE
            + "uniform float2 size;\n"
            + "uniform float4 cornerRadii;\n"
            + "layout(color) uniform half4 color;\n"
            + "uniform float angle;\n"
            + "uniform float falloff;\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = coord - halfSize;\n"
            + "    float radius = radiusAt(centeredCoord, cornerRadii);\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n"
            + "    float2 normal = float2(cos(angle), sin(angle));\n"
            + "    float intensity = pow(abs(dot(grad, normal)), falloff);\n"
            + "    return color * intensity;\n"
            + "}\n";

    // ---- tuning (dp / upstream values) --------------------------------
    private static final float LABEL_SCAN_DEPTH = 4;
    private static final float LENS_HEIGHT_DP = 10f;
    private static final float LENS_AMOUNT_DP = 14f;
    private static final float EDGE_BLUR_PAD_DP = 18f;
    private static final float SHADOW_BLUR_DP = 24f;
    private static final float SHADOW_OFFSET_DP = 4f;
    private static final float INNER_BLUR_DP = 8f;
    private static final float RIM_STROKE_DP = 1f;
    private static final float RIM_ALPHA = 0.5f;
    private static final float SHADOW_ALPHA = 0.10f;
    private static final float INNER_ALPHA = 0.15f;
    private static final float WASH_REST_LIGHT = 0.10f;
    private static final float WASH_REST_DARK = 0.10f;
    private static final float WASH_PRESS = 0.03f;
    private static final float GLOW_ALPHA_NIGHT = 23f / 255f;
    private static final float GLOW_ALPHA_DAY = 18f / 255f;
    private static final float RIPPLE_ALPHA = 0.14f;

    private final WeakReference<ViewGroup> mBackdropRef;
    private WeakReference<ViewGroup> mTabRowRef;
    private final float mDensity;
    private boolean mNight;
    private boolean mUsable;

    private float mProgress;
    private float mTouchX;
    private float mTouchY;
    private boolean mHasTouch;
    private float mRowGrowth = 1f;

    // reusable geometry
    private final Path mOutline = new Path();
    private final Rect mVisibleRect = new Rect();
    private final int[] mSelfPos = new int[2];
    private final int[] mChildPos = new int[2];

    // GPU nodes
    private RuntimeShader mLens;
    private RuntimeShader mEdge;
    private final RenderNode mContentNode = new RenderNode("qselfDropletContent");
    private final RenderNode mRowNode = new RenderNode("qselfTintedTabs");
    private final RenderNode mShadowNode = new RenderNode("qselfDropletShadow");
    private final RenderNode mInnerNode = new RenderNode("qselfDropletInner");
    private RenderEffect mShadowEffect;
    private RenderEffect mInnerEffect;
    private RenderEffect mLensChain;
    private int mChainW = -1;
    private int mChainH = -1;
    private int mTintAccent;

    // paints
    private final Paint mSolidBlack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWashRest = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWashPress = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mInnerEraser = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRipplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient mRippleShader;
    private final Paint mFallbackGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient mFallbackRipple;

    private int mAccentCache;

    DropletPanel(Context context, ViewGroup backdrop, ViewGroup tabRow,
                 float density, boolean night) {
        super(context);
        mBackdropRef = new WeakReference<>(backdrop);
        mTabRowRef = new WeakReference<>(tabRow);
        mDensity = density;
        mNight = night;
        boolean usable = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                mLens = new RuntimeShader(DISPERSION_PROGRAM);
                mEdge = new RuntimeShader(EDGE_HIGHLIGHT_PROGRAM);
                float shadowBlur = SHADOW_BLUR_DP * density;
                float innerBlur = INNER_BLUR_DP * density;
                mShadowEffect = RenderEffect.createBlurEffect(
                        shadowBlur, shadowBlur, Shader.TileMode.CLAMP);
                mInnerEffect = RenderEffect.createBlurEffect(
                        innerBlur, innerBlur, Shader.TileMode.CLAMP);
                mShadowNode.setRenderEffect(mShadowEffect);
                mInnerNode.setRenderEffect(mInnerEffect);
                usable = true;
            } catch (Throwable t) {
                LiquidGlassModule.logErr("droplet shaders failed, flat fallback", t);
            }
        }
        mUsable = usable;
        mEdgePaint.setStyle(Paint.Style.STROKE);
        mEdgePaint.setBlendMode(BlendMode.PLUS);
        mSolidBlack.setColor(0xFF000000);
        mWashPress.setColor(0xFF000000);
        mWashPress.setBlendMode(BlendMode.SRC_OVER);
        mHighlightPaint.setColor(0xFFFFFFFF);
        mHighlightPaint.setBlendMode(BlendMode.PLUS);
        mInnerEraser.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        mInnerEraser.setColor(0xFFFFFFFF);
        setWillNotDraw(false);
    }

    /** Retained for the installer contract; the droplet owns no pill copy. */
    void setPill(View pill) {
        // no-op
    }

    void setTheme(boolean night) {
        mNight = night;
        invalidate();
    }

    void setTabRow(ViewGroup tabRow) {
        mTabRowRef = new WeakReference<>(tabRow);
        invalidate();
    }

    /** Press progress 0..1 driven by the drag controller. */
    void setProgress(float progress) {
        if (mProgress != progress) {
            mProgress = progress;
            invalidate();
        }
    }

    /** Finger position in host coordinates, for the interactive highlight. */
    void setTouchPoint(float hostX, float hostY, boolean active) {
        int left = getLeft() + Math.round(getTranslationX());
        int top = getTop() + Math.round(getTranslationY());
        float scaleX = Math.abs(getScaleX()) < 0.01f ? 1f : getScaleX();
        float scaleY = Math.abs(getScaleY()) < 0.01f ? 1f : getScaleY();
        float cx = left + getWidth() / 2f;
        float cy = top + getHeight() / 2f;
        mTouchX = (hostX - cx) / scaleX + getWidth() / 2f;
        mTouchY = (hostY - cy) / scaleY + getHeight() / 2f;
        mHasTouch = active;
    }

    /** Container press growth applied to the tinted row capture. */
    void setMotion(float rowGrowth) {
        mRowGrowth = rowGrowth;
    }

    /** Kept for the controller contract; translations invalidate via progress. */
    void refresh() {
        if (mProgress > 0.01f) {
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mChainW = -1;
        mOutline.reset();
        mOutline.addRoundRect(0f, 0f, w, h, h * 0.5f, h * 0.5f, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (!mUsable) {
            drawFallback(canvas, w, h);
            return;
        }
        try {
            drawGpu(canvas, w, h);
        } catch (Throwable t) {
            mUsable = false;
            LiquidGlassModule.logErr("droplet GPU path failed, flat fallback", t);
            // Probe: the flat fallback must never be silent - it changes what
            // the user sees on the bar.
            sumicya.qself.diagnostics.FeatureJournal.error("droplet.gpuFallback", t);
            drawFallback(canvas, w, h);
        }
    }

    private void drawGpu(Canvas canvas, int w, int h) {
        float p = mProgress;
        float pad = EDGE_BLUR_PAD_DP * mDensity;

        // 1) outer drop shadow (press only)
        if (p > 0.01f) {
            drawOuterShadow(canvas, w, h, pad, p);
        }

        // 2) combined backdrop through the rim lens, clipped to the capsule
        canvas.save();
        canvas.clipPath(mOutline);
        recordAndDrawContent(canvas, w, h, pad, p);

        // 3) surface washes: rest neutral 10% -> pressed 3% black
        int restAlpha = Math.round(0xFF * (mNight ? WASH_REST_DARK : WASH_REST_LIGHT)
                * (1f - p));
        if (restAlpha > 1) {
            mWashRest.setColor(mNight ? 0xFFFFFFFF : 0xFF000000);
            mWashRest.setAlpha(restAlpha);
            canvas.drawRect(0f, 0f, w, h, mWashRest);
        }
        int pressAlpha = Math.round(0xFF * WASH_PRESS * p);
        if (pressAlpha > 0) {
            mWashPress.setAlpha(pressAlpha);
            canvas.drawRect(0f, 0f, w, h, mWashPress);
        }

        // Upstream places InteractiveHighlight on the container row and the
        // invisible tinted row only -- never on the droplet surface. The
        // tinted-row copy is recorded through the lens in recordTintedRow;
        // the container copy is painted by GlassSurface.
        canvas.restore();

        // 4) directional rim specular (press only, hairline)
        if (p > 0.01f) {
            drawEdgeHighlight(canvas, w, h, p);
        }

        // 5) inner shadow (press only)
        if (p > 0.01f) {
            drawInnerShadow(canvas, w, h, p);
        }
    }

    /* ----------------------------------------------------- content/lens */

    private void recordAndDrawContent(Canvas canvas, int w, int h, float pad, float p) {
        int cw = w + Math.round(pad) * 2;
        int ch = h + Math.round(pad) * 2;
        mContentNode.setPosition(0, 0, cw, ch);

        if (!ViewGeom.unscaledScreenPos(this, mSelfPos)) {
            getLocationOnScreen(mSelfPos);
        }

        RecordingCanvas rc = mContentNode.beginRecording(cw, ch);
        try {
            // Gaps the pages do not cover must not blur into transparent black.
            rc.drawColor(GlassConfig.backgroundColor(mNight));
            ViewGroup pager = mBackdropRef.get();
            if (pager != null && pager.getWidth() > 0) {
                boolean captured = false;
                for (int i = 0; i < pager.getChildCount(); i++) {
                    View page = pager.getChildAt(i);
                    if (page.getVisibility() != VISIBLE
                            || !page.getGlobalVisibleRect(mVisibleRect) || mVisibleRect.isEmpty()) {
                        continue;
                    }
                    page.getLocationOnScreen(mChildPos);
                    float dx = pad - (mSelfPos[0] - mChildPos[0]);
                    float dy = pad - (mSelfPos[1] - mChildPos[1]);
                    int save = rc.save();
                    rc.translate(dx, dy);
                    rc.clipRect(-dx, -dy, -dx + cw, -dy + ch);
                    page.draw(rc);
                    rc.restoreToCount(save);
                    captured = true;
                }
                if (!captured) {
                    pager.getLocationOnScreen(mChildPos);
                    rc.translate(pad - (mSelfPos[0] - mChildPos[0]),
                            pad - (mSelfPos[1] - mChildPos[1]));
                    pager.draw(rc);
                }
            }
            recordTintedRow(rc, pad, p, w, h);
        } finally {
            mContentNode.endRecording();
        }

        // The lens fades in with press; at rest the backdrop passes untouched.
        if (p > 0.02f) {
            mLens.setFloatUniform("size", (float) w, (float) h);
            mLens.setFloatUniform("offset", -pad, -pad);
            float r = h * 0.5f;
            mLens.setFloatUniform("cornerRadii", r, r, r, r);
            mLens.setFloatUniform("refractionHeight", LENS_HEIGHT_DP * mDensity * p);
            mLens.setFloatUniform("refractionAmount", -LENS_AMOUNT_DP * mDensity * p);
            mLens.setFloatUniform("depthEffect", 0f);
            mLens.setFloatUniform("chromaticAberration", 1f);
            if (mLensChain == null || mChainW != w || mChainH != h) {
                mLensChain = RenderEffect.createRuntimeShaderEffect(mLens, "content");
                mChainW = w;
                mChainH = h;
            }
            mContentNode.setRenderEffect(mLensChain);
        } else {
            mContentNode.setRenderEffect(null);
        }

        canvas.save();
        canvas.translate(-pad, -pad);
        canvas.drawRenderNode(mContentNode);
        canvas.restore();
    }

    /**
     * Records the real tab row (icons + labels) tinted to the accent colour,
     * 1:1 with the screen and carrying the controller's parallax/growth and
     * each tab's own 1.2x press scale.
     */
    private void recordTintedRow(RecordingCanvas rc, float pad, float p, int w, int h) {
        ViewGroup row = mTabRowRef.get();
        if (row == null || row.getWidth() <= 0) {
            return;
        }
        int rw = row.getWidth();
        int rh = row.getHeight();
        if (rw <= 0 || rh <= 0) {
            return;
        }
        mRowNode.setPosition(0, 0, rw, rh);
        RecordingCanvas rrc = mRowNode.beginRecording(rw, rh);
        try {
            int[] pos = new int[2];
            for (int i = 0; i < row.getChildCount(); i++) {
                View tab = row.getChildAt(i);
                if (tab.getVisibility() != VISIBLE || tab.getWidth() <= 0) {
                    continue;
                }
                ViewGeom.offsetWithin(tab, row, pos);
                int save = rrc.save();
                rrc.translate(pos[0], pos[1]);
                float sx = Math.abs(tab.getScaleX()) < 0.01f ? 1f : tab.getScaleX();
                float sy = Math.abs(tab.getScaleY()) < 0.01f ? 1f : tab.getScaleY();
                if (sx != 1f || sy != 1f) {
                    rrc.scale(sx, sy, tab.getWidth() / 2f, tab.getHeight() / 2f);
                }
                tab.draw(rrc);
                rrc.restoreToCount(save);
            }
        } finally {
            mRowNode.endRecording();
        }
        int accent = accentColour(row);
        if (accent == 0) {
            accent = 0xFF0091FF;
        }
        if (accent != mTintAccent) {
            mTintAccent = accent;
            mRowNode.setRenderEffect(RenderEffect.createColorFilterEffect(
                    new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)));
        }
        int[] rowPos = new int[2];
        ViewGeom.unscaledScreenPos(row, rowPos);
        int save = rc.save();
        rc.translate(pad - (mSelfPos[0] - rowPos[0]),
                pad - (mSelfPos[1] - rowPos[1]));
        if (mRowGrowth != 1f) {
            rc.scale(mRowGrowth, mRowGrowth, rw / 2f, rh / 2f);
        }
        if (p > 0.01f) {
            // Upstream row2 InteractiveHighlight (verbatim): 8% white PLUS
            // wash plus a 15% radial centred on the droplet, radius
            // minDim * 1.5. It belongs to the tinted-row capture, so it
            // reaches the screen only through the droplet lens.
            mHighlightPaint.setAlpha(Math.round(0xFF * 0.08f * p));
            rc.drawRect(0f, 0f, rw, rh, mHighlightPaint);
            float cx = (mSelfPos[0] - rowPos[0]) + w / 2f;
            float cy = (mSelfPos[1] - rowPos[1]) + h / 2f;
            float rippleRadius = Math.min(rw, rh) * 1.5f;
            int core = Math.round(0xFF * 0.15f * p);
            mRippleShader = new RadialGradient(cx, cy, rippleRadius,
                    new int[]{(core << 24) | 0x00FFFFFF, (core << 24) | 0x00FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            mRipplePaint.setShader(mRippleShader);
            mRipplePaint.setBlendMode(BlendMode.PLUS);
            rc.drawRect(0f, 0f, rw, rh, mRipplePaint);
        }
        // Upstream keeps the tinted glyph layer at alpha 0 at rest; the
        // accent silhouette fades in together with the press lens. Glyphs
        // draw over the highlight, matching the Compose modifier order.
        mRowNode.setAlpha(Math.max(0f, Math.min(1f, p)));
        rc.drawRenderNode(mRowNode);
        rc.restoreToCount(save);
    }

    /* -------------------------------------------------------- shadows */

    private void drawOuterShadow(Canvas canvas, int w, int h, float pad, float p) {
        float blur = SHADOW_BLUR_DP * mDensity;
        float off = SHADOW_OFFSET_DP * mDensity;
        int sw = w + Math.round(blur * 2f);
        int sh = h + Math.round(blur * 2f);
        mShadowNode.setPosition(0, 0, sw, sh);
        RecordingCanvas rc = mShadowNode.beginRecording(sw, sh);
        rc.drawRoundRect(blur, blur, blur + w, blur + h, h * 0.5f, h * 0.5f, mSolidBlack);
        mShadowNode.endRecording();
        mShadowNode.setAlpha(SHADOW_ALPHA * p);
        canvas.save();
        canvas.translate(-blur, -blur + off);
        canvas.drawRenderNode(mShadowNode);
        canvas.restore();
    }

    private void drawInnerShadow(Canvas canvas, int w, int h, float p) {
        float blur = INNER_BLUR_DP * mDensity;
        float off = INNER_BLUR_DP * mDensity * p;
        int saveLayer = canvas.saveLayer(0f, 0f, w, h, null);
        canvas.save();
        canvas.clipPath(mOutline);
        int sw = w + Math.round(blur * 2f);
        int sh = h + Math.round(blur * 2f);
        mInnerNode.setPosition(0, 0, sw, sh);
        RecordingCanvas rc = mInnerNode.beginRecording(sw, sh);
        mSolidBlack.setAlpha(0xFF);
        rc.drawRoundRect(blur, blur, blur + w, blur + h, h * 0.5f, h * 0.5f, mSolidBlack);
        mInnerNode.endRecording();
        mInnerNode.setAlpha(INNER_ALPHA * p);
        canvas.save();
        canvas.translate(-blur, -blur + off);
        canvas.drawRenderNode(mInnerNode);
        canvas.restore();
        // erase the solid centre, leaving only the inward blurred ring.
        canvas.drawRoundRect(blur, blur, w - blur, h - blur + off,
                h * 0.5f - blur, h * 0.5f - blur, mInnerEraser);
        canvas.restore();
        canvas.restoreToCount(saveLayer);
    }

    /* ------------------------------------------------------- highlights */

    private void drawEdgeHighlight(Canvas canvas, int w, int h, float p) {
        float r = h * 0.5f;
        mEdge.setFloatUniform("size", (float) w, (float) h);
        mEdge.setFloatUniform("cornerRadii", r, r, r, r);
        int a = Math.round(0xFF * RIM_ALPHA * p);
        mEdge.setColorUniform("color", (a << 24) | 0x00FFFFFF);
        mEdge.setFloatUniform("angle", (float) Math.toRadians(45.0));
        mEdge.setFloatUniform("falloff", 1f);
        mEdgePaint.setShader(mEdge);
        mEdgePaint.setStrokeWidth(RIM_STROKE_DP * mDensity);
        float inset = RIM_STROKE_DP * mDensity * 0.5f;
        int save = canvas.save();
        canvas.clipPath(mOutline);
        canvas.drawRoundRect(inset, inset, w - inset, h - inset,
                r - inset, r - inset, mEdgePaint);
        canvas.restoreToCount(save);
        mEdgePaint.setShader(null);
    }

    /* --------------------------------------------------------- fallback */

    /** Pre-API 33: flat accent range block plus a local white ripple. */
    private void drawFallback(Canvas canvas, int w, int h) {
        float r = Math.min(18f * mDensity, h * 0.5f);
        float glowAlpha = (mNight ? GLOW_ALPHA_NIGHT : GLOW_ALPHA_DAY)
                * (1f - 0.55f * mProgress);
        if (glowAlpha > 0.004f) {
            int accent = accentColour(mTabRowRef.get());
            mFallbackGlow.setColor(accent != 0 ? accent : 0xFFFFFFFF);
            mFallbackGlow.setAlpha(Math.round(0xFF * glowAlpha));
            canvas.drawRoundRect(0f, 0f, w, h, r, r, mFallbackGlow);
        }
        if (mProgress > 0.01f) {
            float cx = mHasTouch ? mTouchX : w * 0.5f;
            float cy = mHasTouch ? mTouchY : h * 0.5f;
            float radius = Math.max(w, h) * 0.72f;
            int core = Math.round(0xFF * RIPPLE_ALPHA * mProgress);
            mFallbackRipple = new RadialGradient(cx, cy, radius,
                    new int[]{(core << 24) | 0x00FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP);
            mRipplePaint.setShader(mFallbackRipple);
            mRipplePaint.setBlendMode(BlendMode.PLUS);
            canvas.drawRect(0f, 0f, w, h, mRipplePaint);
            mRipplePaint.setShader(null);
        }
    }

    /* ----------------------------------------------------------- accent */

    /**
     * Selection colour read from the currently selected tab's label, with
     * near-grey readings rejected (they come from an unsettled selection).
     * Returns 0 when no accent can be resolved.
     */
    private int accentColour(ViewGroup row) {
        if (row == null) {
            return mAccentCache;
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            View tab = row.getChildAt(i);
            if (!tab.isSelected()) {
                continue;
            }
            int colour = firstLabelColour(tab, 0);
            if (colour != 0 && !isNeutral(colour)) {
                mAccentCache = colour;
                return colour;
            }
        }
        return mAccentCache;
    }

    private static boolean isNeutral(int colour) {
        int r = (colour >> 16) & 0xFF;
        int g = (colour >> 8) & 0xFF;
        int b = colour & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max - min < 24;
    }

    private int firstLabelColour(View v, int depth) {
        if (depth > LABEL_SCAN_DEPTH || v.getVisibility() != VISIBLE) {
            return 0;
        }
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (tv.getBackground() == null && tv.getText() != null
                    && tv.getText().length() > 0) {
                return tv.getCurrentTextColor() | 0xFF000000;
            }
            return 0;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                int colour = firstLabelColour(group.getChildAt(i), depth + 1);
                if (colour != 0) {
                    return colour;
                }
            }
        }
        return 0;
    }
}
