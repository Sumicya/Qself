/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * The moving selection lens.
 *
 * <p>At rest it is a flat tinted capsule matching the pill; while pressed a
 * RuntimeShader lens samples an enlarged, blurred capture of the page behind
 * the bar plus a magnified repaint of the tab row, bending the rim through a
 * rounded-rect signed distance field and splitting it into spectral taps for
 * chromatic dispersion. A second shader paints a smooth inner shadow.
 *
 * <p>The view is sized and positioned by {@link LiquidGlassInstaller} and the
 * {@link DropletDragController}; it only paints.
 */
final class DropletPanel extends View {

    private static final float REFRACTION_BAND_DP = 10f;
    private static final float REFRACTION_AMOUNT_DP = 14f;
    private static final float DISPERSION = 0.5f;
    private static final float MAX_BAND_FRACTION = 0.18f;
    private static final float PAGE_BLUR_DP = 4f;
    private static final float PAGE_SATURATION = 1.5f;
    private static final float TAB_ZOOM = 0.1f;
    private static final int LABEL_SCAN_DEPTH = 4;

    /** Edge refraction with seven spectral taps sampled across the rim. */
    private static final String LENS_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float band;\n"
            + "uniform float bend;\n"
            + "uniform float depth;\n"
            + "uniform float dispersion;\n"
            + LiquidGlassPanel.SDF_SOURCE
            + "half4 main(float2 p) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 c = (p + offset) - halfSize;\n"
            + "    float r = radiusAt(p, cornerRadii);\n"
            + "    float d = sdRoundedRect(c, halfSize, r);\n"
            + "    if (-d >= band) { return content.eval(p); }\n"
            + "    float rim = clamp(-d / band, 0.0, 1.0);\n"
            + "    float fall = 1.0 - sqrt(1.0 - rim * rim);\n"
            + "    float shift = fall * bend;\n"
            + "    float gr = min(r * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 g = normalize(gradSdRoundedRect(c, halfSize, gr)\n"
            + "            + depth * normalize(c));\n"
            + "    float2 q = p + shift * g;\n"
            + "    float spread = dispersion * 4.0\n"
            + "            * ((c.x * c.y) / (size.x * size.y));\n"
            + "    float2 s = shift * g * spread;\n"
            + "    half4 outc = half4(0.0);\n"
            + "    half4 t1 = content.eval(q + s);\n"
            + "    outc.r += t1.r / 3.5; outc.a += t1.a / 7.0;\n"
            + "    half4 t2 = content.eval(q + s * (2.0 / 3.0));\n"
            + "    outc.r += t2.r / 3.5; outc.g += t2.g / 7.0; outc.a += t2.a / 7.0;\n"
            + "    half4 t3 = content.eval(q + s * (1.0 / 3.0));\n"
            + "    outc.r += t3.r / 3.5; outc.g += t3.g / 3.5; outc.a += t3.a / 7.0;\n"
            + "    half4 t4 = content.eval(q);\n"
            + "    outc.g += t4.g / 3.5; outc.a += t4.a / 7.0;\n"
            + "    half4 t5 = content.eval(q - s * (1.0 / 3.0));\n"
            + "    outc.g += t5.g / 3.5; outc.b += t5.b / 3.0; outc.a += t5.a / 7.0;\n"
            + "    half4 t6 = content.eval(q - s * (2.0 / 3.0));\n"
            + "    outc.b += t6.b / 3.0; outc.a += t6.a / 7.0;\n"
            + "    half4 t7 = content.eval(q - s);\n"
            + "    outc.r += t7.r / 7.0; outc.b += t7.b / 3.0; outc.a += t7.a / 7.0;\n"
            + "    return outc;\n"
            + "}\n";

    /** Smooth inner shadow driven by the same rounded-rect SDF. */
    private static final String INNER_SHADOW_SHADER = ""
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float blur;\n"
            + "uniform float alpha;\n"
            + LiquidGlassPanel.SDF_SOURCE
            + "half4 main(float2 p) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float d = sdRoundedRect(p - halfSize, halfSize, radius);\n"
            + "    float t = 1.0 - smoothstep(0.0, blur, -d);\n"
            + "    return half4(0.0, 0.0, 0.0, half(alpha * t * t));\n"
            + "}\n";

    private final WeakReference<ViewGroup> mPagerRef;
    private WeakReference<ViewGroup> mTabRowRef;
    private WeakReference<View> mPillRef = new WeakReference<>(null);
    private final float mDensity;
    private final int mPad;
    private boolean mNight;

    private final RenderNode mLensNode = new RenderNode("qselfDroplet");
    private final RenderNode mBackdropNode = new RenderNode("qselfDropletBackdrop");
    private RenderEffect mBackdropEffect;
    private RuntimeShader mLens;
    private RuntimeShader mInnerShadow;

    private final Paint mWash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPressTint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mInnerShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSurfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClipPath = new Path();

    private static final PorterDuffXfermode SRC_ATOP =
            new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP);

    private final int[] mTmpPos = new int[2];
    private final int[] mSelfPos = new int[2];
    private final int[] mSrcPos = new int[2];
    private final Rect mVisibleRect = new Rect();

    private float mProgress;
    private boolean mSupported;

    private final ArrayList<RectF> mBadges = new ArrayList<>(4);
    private int mBadgeCount;
    private final Path mBadgeClip = new Path();
    private int mAccentCache;

    DropletPanel(Context context, ViewGroup pager, ViewGroup tabRow,
                 float density, boolean night) {
        super(context);
        mPagerRef = new WeakReference<>(pager);
        mTabRowRef = new WeakReference<>(tabRow);
        mDensity = density;
        mPad = Math.round(REFRACTION_AMOUNT_DP * density)
                + Math.round(density * 4f);

        mSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        if (mSupported) {
            try {
                mLens = new RuntimeShader(LENS_SHADER);
                mInnerShadow = new RuntimeShader(INNER_SHADOW_SHADER);
                ColorMatrix saturation = new ColorMatrix();
                saturation.setSaturation(PAGE_SATURATION);
                RenderEffect saturate = RenderEffect.createColorFilterEffect(
                        new ColorMatrixColorFilter(saturation));
                float blur = PAGE_BLUR_DP * density;
                mBackdropEffect = RenderEffect.createBlurEffect(
                        blur, blur, saturate, Shader.TileMode.CLAMP);
            } catch (Throwable t) {
                mSupported = false;
                LiquidGlassModule.logErr("droplet shader unavailable", t);
            }
        }
        setTheme(night);
        mPressTint.setColor(0x08000000);
        mInnerShadowPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    /** The resting glass pill, composited into the lens capture. */
    void setPill(View pill) {
        mPillRef = new WeakReference<>(pill);
    }

    void setTheme(boolean night) {
        mNight = night;
        mSurfacePaint.setColor(night ? 0x662C2C2E : 0x66F2F2F7);
        mWash.setColor(night ? 0x1AFFFFFF : 0x1A000000);
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

    /** Re-captures while pressed; translation alone does not redraw. */
    void refresh() {
        if (mProgress > 0.01f) {
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mClipPath.reset();
        float radius = h * 0.5f;
        mClipPath.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float radius = h * 0.5f;
        float p = mProgress;

        boolean lensDrawn = false;
        if (mSupported && p > 0.01f && canvas.isHardwareAccelerated()) {
            try {
                lensDrawn = drawLens(canvas, w, h, radius, p);
            } catch (Throwable t) {
                mSupported = false;
                LiquidGlassModule.logErr("droplet lens failed", t);
            }
        }
        if (!lensDrawn) {
            drawSurfaceTints(canvas, 0f, 0f, w, h, radius, p);
            drawRestingTab(canvas);
        }
        if (p > 0f && mInnerShadow != null && canvas.isHardwareAccelerated()) {
            float blur = 8f * mDensity * p;
            if (blur > 0.5f) {
                mInnerShadow.setFloatUniform("size", (float) w, (float) h);
                mInnerShadow.setFloatUniform("radius", radius);
                mInnerShadow.setFloatUniform("blur", blur);
                mInnerShadow.setFloatUniform("alpha", 0.15f * p);
                mInnerShadowPaint.setShader(mInnerShadow);
                mInnerShadowPaint.setAlpha(255);
                canvas.drawRoundRect(0, 0, w, h, radius, radius,
                        mInnerShadowPaint);
            }
        }
    }

    /* ------------------------------------------------------ fallback draw */

    private void drawSurfaceTints(Canvas canvas, float left, float top,
                                  float right, float bottom,
                                  float radius, float p) {
        // Equal-intensity black reads heavier than white on a light surface.
        int restingAlpha = mNight ? 0x1A : 0x0D;
        int washAlpha = Math.round(restingAlpha * (1f - p));
        if (washAlpha > 0) {
            mWash.setAlpha(washAlpha);
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, mWash);
        }
        int pressAlpha = Math.round(0x08 * p);
        if (pressAlpha > 0) {
            mPressTint.setAlpha(pressAlpha);
            canvas.drawRoundRect(left, top, right, bottom, radius, radius,
                    mPressTint);
        }
    }

    /** Repaints the selected tab above the resting capsule. */
    private void drawRestingTab(Canvas canvas) {
        ViewGroup row = mTabRowRef.get();
        View tab = TabBarBridge.tabAt(row, TabBarBridge.selectedIndex(row));
        if (tab == null || tab.getVisibility() != VISIBLE
                || !ViewGeom.unscaledScreenPos(this, mSelfPos)
                || !ViewGeom.unscaledScreenPos(tab, mSrcPos)) {
            return;
        }
        int save = canvas.save();
        canvas.translate(mSrcPos[0] - mSelfPos[0],
                mSrcPos[1] - mSelfPos[1]);
        tab.draw(canvas);
        canvas.restoreToCount(save);
    }

    /* ------------------------------------------------------------ capture */

    /** Records visible backdrop pages into the pre-blurred page layer. */
    private void recordBlurredBackdrop(int nw, int nh, int[] self) {
        ViewGroup pager = mPagerRef.get();
        if (pager == null) {
            return;
        }
        mBackdropNode.setPosition(0, 0, nw, nh);
        RecordingCanvas c = mBackdropNode.beginRecording(nw, nh);
        try {
            int[] src = mSrcPos;
            c.drawColor(mNight ? 0xFF111111 : 0xFFF7F7F7);
            boolean drewAny = false;
            for (int i = 0; i < pager.getChildCount(); i++) {
                View page = pager.getChildAt(i);
                if (page.getVisibility() != VISIBLE
                        || !page.getGlobalVisibleRect(mVisibleRect)
                        || mVisibleRect.isEmpty()) {
                    continue;
                }
                page.getLocationOnScreen(src);
                int save = c.save();
                c.translate(mPad - (self[0] - src[0]),
                        mPad - (self[1] - src[1]));
                c.clipRect(self[0] - src[0] - mPad,
                        self[1] - src[1] - mPad,
                        self[0] - src[0] - mPad + nw,
                        self[1] - src[1] - mPad + nh);
                page.draw(c);
                c.restoreToCount(save);
                drewAny = true;
            }
            if (!drewAny) {
                pager.getLocationOnScreen(src);
                int save = c.save();
                c.translate(mPad - (self[0] - src[0]),
                        mPad - (self[1] - src[1]));
                pager.draw(c);
                c.restoreToCount(save);
            }
        } finally {
            mBackdropNode.endRecording();
        }
        mBackdropNode.setRenderEffect(mBackdropEffect);
    }

    /** Builds the lens input: blurred page, surface, resting pill, tabs. */
    private void paintLensContent(Canvas c, int nw, int nh, int[] self,
                                  float p, float viewScale) {
        ViewGroup row = mTabRowRef.get();
        int[] src = mSrcPos;

        // Compensate the outer droplet scale at composite time so the blur
        // radius itself stays constant.
        int pageSave = c.save();
        if (Math.abs(viewScale - 1f) > 0.001f) {
            c.scale(1f / viewScale, 1f / viewScale, nw * 0.5f, nh * 0.5f);
        }
        c.drawRenderNode(mBackdropNode);
        c.restoreToCount(pageSave);

        float innerRadius = (nh - mPad * 2) * 0.5f;
        c.drawRoundRect(mPad, mPad, nw - mPad, nh - mPad,
                innerRadius, innerRadius, mSurfacePaint);

        // Preserve the real resting glass where the pill already sits, so the
        // enlarged overflow is the only new surface.
        View pill = mPillRef.get();
        if (row != null && ViewGeom.unscaledScreenPos(row, src)) {
            int save = c.save();
            if (Math.abs(viewScale - 1f) > 0.001f) {
                c.scale(1f / viewScale, 1f / viewScale, nw * 0.5f, nh * 0.5f);
            }
            c.translate(mPad - (self[0] - src[0]),
                    mPad - (self[1] - src[1]));
            if (pill instanceof LiquidGlassPanel
                    && ViewGeom.unscaledScreenPos(pill, mTmpPos)) {
                int ps = c.save();
                c.translate(mTmpPos[0] - src[0], mTmpPos[1] - src[1]);
                ((LiquidGlassPanel) pill).drawEmbedded(c);
                c.restoreToCount(ps);
            }
            c.restoreToCount(save);
        }

        drawSurfaceTints(c, mPad, mPad, nw - mPad, nh - mPad,
                innerRadius, p);

        // Magnified tab repaint; each tab scales about its own centre.
        if (row != null && ViewGeom.unscaledScreenPos(row, src)) {
            int save = c.save();
            if (Math.abs(viewScale - 1f) > 0.001f) {
                c.scale(1f / viewScale, 1f / viewScale, nw * 0.5f, nh * 0.5f);
            }
            c.translate(mPad - (self[0] - src[0]),
                    mPad - (self[1] - src[1]));
            float scale = 1f + TAB_ZOOM * p;
            int accent = accentColour(row);
            for (int i = 0; i < row.getChildCount(); i++) {
                View tab = row.getChildAt(i);
                if (tab.getVisibility() != VISIBLE) {
                    continue;
                }
                int ts = c.save();
                c.scale(scale, scale,
                        tab.getLeft() + tab.getWidth() * 0.5f,
                        tab.getTop() + tab.getHeight() * 0.5f);
                c.translate(tab.getLeft(), tab.getTop());
                drawTab(c, tab, accent);
                c.restoreToCount(ts);
            }
            c.restoreToCount(save);
        }
    }

    private boolean drawLens(Canvas canvas, int w, int h, float radius, float p) {
        ViewGroup pager = mPagerRef.get();
        if (pager == null) {
            return false;
        }
        int nw = w + mPad * 2;
        int nh = h + mPad * 2;
        mLensNode.setPosition(0, 0, nw, nh);

        int[] self = mSelfPos;
        if (!ViewGeom.unscaledScreenPos(this, self)) {
            return false;
        }
        float viewScale = ViewGeom.cumulativeScale(this);
        recordBlurredBackdrop(nw, nh, self);

        RecordingCanvas rc = mLensNode.beginRecording(nw, nh);
        try {
            paintLensContent(rc, nw, nh, self, p, viewScale);
        } finally {
            mLensNode.endRecording();
        }

        float band = REFRACTION_BAND_DP * mDensity;
        float contentHalf = contentHalfHeight(mTabRowRef.get());
        if (contentHalf > 0f) {
            float safe = Math.max(0f, h * 0.5f
                    - contentHalf * (1f + TAB_ZOOM * p) / viewScale);
            float preferred = Math.min(band, h * MAX_BAND_FRACTION);
            band = Math.min(band, Math.max(safe, preferred));
        } else if (LiquidGlassModule.app() == HostApp.QQ) {
            band = Math.min(band, h * MAX_BAND_FRACTION);
        }
        mLens.setFloatUniform("size", (float) w, (float) h);
        mLens.setFloatUniform("offset", (float) -mPad, (float) -mPad);
        mLens.setFloatUniform("cornerRadii", radius, radius, radius, radius);
        mLens.setFloatUniform("band", band * p);
        mLens.setFloatUniform("bend",
                -band * (REFRACTION_AMOUNT_DP / REFRACTION_BAND_DP) * p);
        // Zero on this flat capsule: the depth term would ring at mid-span.
        mLens.setFloatUniform("depth", 0f);
        mLens.setFloatUniform("dispersion", DISPERSION);
        mLensNode.setRenderEffect(
                RenderEffect.createRuntimeShaderEffect(mLens, "content"));

        canvas.save();
        canvas.clipPath(mClipPath);
        canvas.translate(-mPad, -mPad);
        canvas.drawRenderNode(mLensNode);
        canvas.restore();
        return true;
    }

    /* ----------------------------------------------------------- tab tint */

    /**
     * Selection colour read from the currently selected tab's label, with
     * near-grey readings rejected (they come from an unsettled selection).
     * Returns 0 when no accent can be resolved, in which case tabs are copied
     * untinted rather than painted an arbitrary hue.
     */
    private int accentColour(ViewGroup row) {
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
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
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

    /**
     * Draws one tab, accent-tinted as one layer over its own draw so container
     * painting (badges) is not lost, then repaints badge regions untinted.
     * The selected tab is copied as-is: its glyph is already multi-coloured.
     */
    private void drawTab(Canvas c, View tab, int accent) {
        if (tab.getVisibility() != VISIBLE
                || tab.getWidth() <= 0 || tab.getHeight() <= 0) {
            return;
        }
        // The selected tab is already painted by the host (and its glyph is
        // multi-coloured art that a flat tint would silhouette); with no
        // resolved accent, the tab is copied untouched as well.
        if (tab.isSelected() || accent == 0) {
            tab.draw(c);
            return;
        }
        int w = tab.getWidth();
        int h = tab.getHeight();
        // Badges overhang the tab bounds; grow the layer rather than clip.
        float pad = h * 0.5f;
        int layer = c.saveLayer(-pad, -pad, w + pad, h + pad, null);
        tab.draw(c);
        mTintPaint.setColor(accent);
        mTintPaint.setXfermode(SRC_ATOP);
        c.drawRect(-pad, -pad, w + pad, h + pad, mTintPaint);
        mTintPaint.setXfermode(null);
        c.restoreToCount(layer);

        if (tab instanceof ViewGroup) {
            mBadgeCount = 0;
            collectBadges((ViewGroup) tab, 0f, 0f, 0);
            if (mBadgeCount > 0) {
                int save = c.save();
                mBadgeClip.reset();
                for (int i = 0; i < mBadgeCount; i++) {
                    RectF r = mBadges.get(i);
                    float rr = r.height() * 0.5f;
                    mBadgeClip.addRoundRect(r, rr, rr, Path.Direction.CW);
                }
                c.clipPath(mBadgeClip);
                tab.draw(c);
                c.restoreToCount(save);
            }
        }
    }

    /** Views that own their colour (badges, dots) survive the tint pass. */
    private static boolean ownsItsColour(View v) {
        HostApp app = LiquidGlassModule.app();
        if (app != null && app.isTabIconClass(v.getClass().getName())) {
            return false;
        }
        return !(v instanceof android.widget.TextView && v.getBackground() == null);
    }

    private void addBadge(float l, float t, float r, float b) {
        if (mBadgeCount == mBadges.size()) {
            mBadges.add(new RectF());
        }
        mBadges.get(mBadgeCount++).set(l, t, r, b);
    }

    private void collectBadges(ViewGroup parent, float ox, float oy, int depth) {
        if (depth > LABEL_SCAN_DEPTH) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != VISIBLE
                    || child.getWidth() <= 0 || child.getHeight() <= 0) {
                continue;
            }
            // Badges are often positioned with translation rather than layout.
            float cx = ox + child.getLeft() + child.getTranslationX();
            float cy = oy + child.getTop() + child.getTranslationY();
            if (child instanceof ViewGroup
                    && ((ViewGroup) child).getChildCount() > 0) {
                collectBadges((ViewGroup) child, cx, cy, depth + 1);
            } else if (ownsItsColour(child) && !child.willNotDraw()) {
                addBadge(cx, cy, cx + child.getWidth(),
                        cy + child.getHeight());
            }
        }
    }

    /**
     * Half-height of the tab content stack from the tab centre, used to keep
     * the refraction band out of the icon glyphs. Returns -1 when the first
     * child is full-column layout chrome rather than the content stack.
     */
    private static float contentHalfHeight(ViewGroup row) {
        if (row == null || row.getChildCount() == 0) {
            return 0f;
        }
        View tab = TabBarBridge.tabAt(row, 0);
        if (tab == null || tab.getHeight() <= 0) {
            return 0f;
        }
        float centre = tab.getHeight() * 0.5f;
        if (!(tab instanceof ViewGroup)
                || ((ViewGroup) tab).getChildCount() == 0) {
            return centre;
        }
        View content = ((ViewGroup) tab).getChildAt(0);
        if (content.getVisibility() != VISIBLE || content.getHeight() <= 0
                || (content.getTop() <= 1
                && content.getBottom() >= tab.getHeight() - 1)) {
            return -1f;
        }
        return Math.max(centre - content.getTop(),
                content.getTop() + content.getHeight() - centre);
    }
}
