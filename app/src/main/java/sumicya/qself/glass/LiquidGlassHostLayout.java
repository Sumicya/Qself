/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

/**
 * Container for the floating navigation pill.
 *
 * <p>It hosts (bottom to top) the glass surface, the host app's own tab bar
 * moved into it, and the draggable droplet, reserves an even padding ring for
 * a self-drawn capsule shadow, shields horizontal drags from ancestor
 * interceptors while letting taps and the host's vertical swipe gestures
 * through, and includes a bitmap-capture frost fallback for API levels below
 * the RuntimeShader requirement.
 */
final class LiquidGlassHostLayout extends FrameLayout {

    static final Object GLASS_TAG = new Object();

    private static final float SHADOW_PAD_DP = 14f;
    private static final float SHADOW_BLUR_DP = 10f;
    private static final float SHADOW_OFFSET_DP = 2f;
    private static final float LEGACY_SAMPLE_SCALE = 0.4f;
    private static final int LEGACY_BLUR_RADIUS = 3;
    private static final float SATURATION_BOOST = 1.08f;
    private static final float MAX_CORNER_DP = 30f;
    private static final long REVEAL_DURATION_MS = 380L;

    private final ViewGroup mSampleRoot;
    private final ViewGroup mBar;
    private final float mDensity;
    private final int mTouchSlop;
    private final boolean mUseAgsl;

    private boolean mDarkMode;
    private int mCaptureCount;

    /** GPU renderer plug-in (API 33+); when set, no legacy frost is drawn. */
    interface GlassTuner {
        void onSize(int w, int h, float cornerRadius);
        void onTheme(boolean dark);
    }

    private GlassTuner mTuner;

    /* ------------------------------------------------------------ shadow */

    private int mShadowPad;
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mShadowHole = new Path();
    private int mShadowHoleW = -1;
    private int mShadowHoleH = -1;
    private float mShadowOffsetY;
    private boolean mShadowHidden;
    private int mShadowAlpha = 255;

    /** Follows the host bar's own slide/fade. {@code Float.MAX_VALUE} hides. */
    void setShadowOffsetY(float ty, float alpha) {
        boolean hidden = ty == Float.MAX_VALUE;
        int a = Math.round(255f * clamp01(alpha));
        if (mShadowHidden == hidden && mShadowOffsetY == ty
                && mShadowAlpha == a) {
            return;
        }
        mShadowHidden = hidden;
        mShadowOffsetY = hidden ? 0f : ty;
        mShadowAlpha = a;
        invalidate();
    }

    /**
     * Reserves an even padding ring and paints the capsule shadow into it.
     * The shadow layer is hardware accelerated since API 28 without promoting
     * the whole host to a layer (which would show as a rectangular texture
     * patch clipping content behind the pill).
     */
    void setupShadow(float density, boolean night) {
        mShadowPad = Math.round(density * SHADOW_PAD_DP);
        setPadding(mShadowPad, mShadowPad, mShadowPad, mShadowPad);
        mShadowPaint.setColor(0xFF000000);
        mShadowPaint.setShadowLayer(density * SHADOW_BLUR_DP, 0f,
                density * SHADOW_OFFSET_DP, night ? 0x33000000 : 0x1A000000);
        invalidate();
    }

    int shadowPad() {
        return mShadowPad;
    }

    private void drawCapsuleShadow(Canvas canvas) {
        if (mShadowPad <= 0 || mShadowHidden || mShadowAlpha == 0) {
            return;
        }
        float l = mShadowPad;
        float t = mShadowPad;
        float r = getWidth() - mShadowPad;
        float b = getHeight() - mShadowPad;
        if (r <= l || b <= t) {
            return;
        }
        float radius = (b - t) * 0.5f;
        int save = canvas.save();
        canvas.translate(0f, mShadowOffsetY);
        // Punch the capsule out of the shadow fill: the paint's opaque fill
        // exists only to cast the shadow, and a frame where the glass child
        // lags would otherwise flash a black capsule.
        if (mShadowHoleW != getWidth() || mShadowHoleH != getHeight()) {
            mShadowHole.reset();
            mShadowHole.addRoundRect(l, t, r, b, radius, radius,
                    Path.Direction.CW);
            mShadowHoleW = getWidth();
            mShadowHoleH = getHeight();
        }
        canvas.clipOutPath(mShadowHole);
        int previousAlpha = mShadowPaint.getAlpha();
        mShadowPaint.setAlpha(mShadowAlpha);
        canvas.drawRoundRect(l, t, r, b, radius, radius, mShadowPaint);
        mShadowPaint.setAlpha(previousAlpha);
        canvas.restoreToCount(save);
    }

    /* ------------------------------------------------------------- touch */

    /** Optional gesture owner with first refusal; it should claim only drags. */
    interface DragHandler {
        boolean onIntercept(MotionEvent ev);
        boolean onTouch(MotionEvent ev);
    }

    private DragHandler mDragHandler;
    private float mDownX;
    private float mDownY;
    private boolean mAncestorsBlocked;

    void setDragHandler(DragHandler handler) {
        mDragHandler = handler;
    }

    /**
     * Keeps ancestor drawers/pagers from stealing a drag that began on the
     * pill while releasing a clearly vertical gesture so the host bar's own
     * swipe-up action (fires after about 50px travel) still works.
     */
    private void shieldFromAncestors(MotionEvent ev) {
        android.view.ViewParent parent = getParent();
        if (parent == null) {
            return;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mAncestorsBlocked = true;
                parent.requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mAncestorsBlocked) {
                    break;
                }
                float dx = ev.getX() - mDownX;
                float dy = ev.getY() - mDownY;
                float verticalRelease = Math.max(mTouchSlop, 50f);
                if (Math.abs(dy) > verticalRelease
                        && Math.abs(dy) > Math.abs(dx)) {
                    mAncestorsBlocked = false;
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mAncestorsBlocked) {
                    mAncestorsBlocked = false;
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                break;
            default:
                break;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        shieldFromAncestors(ev);
        try {
            if (mDragHandler != null && mDragHandler.onIntercept(ev)) {
                return true;
            }
        } catch (Throwable t) {
            LiquidGlassModule.logErr("drag intercept failed", t);
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        shieldFromAncestors(ev);
        try {
            if (mDragHandler != null && mDragHandler.onTouch(ev)) {
                return true;
            }
        } catch (Throwable t) {
            LiquidGlassModule.logErr("drag touch failed", t);
        }
        return super.onTouchEvent(ev);
    }

    /* ------------------------------------------------------------ badges */

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        try {
            BadgeNumbers.drawOver(this, mBar, canvas, mDensity);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("badge overlay failed", t);
        }
    }

    /* ----------------------------------------------------------- drawing */

    private final Paint mBackdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();
    private float mCornerRadius;
    private Bitmap mRegionBuffer;
    private boolean mCapturing;
    private ViewTreeObserver.OnPreDrawListener mPreDrawListener;

    LiquidGlassHostLayout(Context context, ViewGroup sampleRoot, ViewGroup bar) {
        super(context);
        mSampleRoot = sampleRoot;
        mBar = bar;
        mDensity = context.getResources().getDisplayMetrics().density;
        mTouchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        mUseAgsl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        mDarkMode = resolveDark(context, detectDarkFromText(bar));
        setTag(GLASS_TAG);
        setWillNotDraw(false);
        setupFallbackPaints();
        LiquidGlassModule.log(android.util.Log.INFO,
                "glass host created: sdk=" + Build.VERSION.SDK_INT
                        + " dark=" + mDarkMode + " agsl=" + mUseAgsl);
    }

    /** Hand-off to the external GPU renderer and disables bitmap frost. */
    void setGlassTuner(GlassTuner tuner) {
        mTuner = tuner;
        if (tuner != null) {
            // Initialise from the already resolved host theme rather than
            // waiting for a transition that may never come.
            tuner.onTheme(mDarkMode);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBounds.set(0f, 0f, w, h);
        mCornerRadius = Math.min(h * 0.46f, MAX_CORNER_DP * mDensity);
        if (mTuner != null) {
            // The glass surface lives inside the shadow padding ring.
            mTuner.onSize(w - mShadowPad * 2, h - mShadowPad * 2, mCornerRadius);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawCapsuleShadow(canvas);
        if (mTuner != null || getWidth() <= 0 || getHeight() <= 0) {
            // GPU renderer paints through its own child view beneath us.
            return;
        }
        int save = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(),
                GlassConfig.materialAlpha());
        drawLegacyFrost(canvas);
        canvas.restoreToCount(save);
    }

    private void drawLegacyFrost(Canvas canvas) {
        float radius = mCornerRadius;
        if (GlassConfig.background == 0 && mRegionBuffer != null
                && !mRegionBuffer.isRecycled()) {
            BitmapShader shader = new BitmapShader(mRegionBuffer,
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix matrix = new Matrix();
            matrix.setScale(getWidth() / (float) mRegionBuffer.getWidth(),
                    getHeight() / (float) mRegionBuffer.getHeight());
            shader.setLocalMatrix(matrix);
            mBackdropPaint.setShader(shader);
        } else {
            mBackdropPaint.setShader(null);
            mBackdropPaint.setColor(GlassConfig.backgroundColor(mDarkMode));
        }
        canvas.drawRoundRect(mBounds, radius, radius, mBackdropPaint);
        canvas.drawRoundRect(mBounds, radius, radius, mTintPaint);
    }

    private void setupFallbackPaints() {
        if (mUseAgsl) {
            return;
        }
        if (mDarkMode) {
            mTintPaint.setColor(0x33000000);
            mBackdropPaint.setColor(0x40000000);
        } else {
            mTintPaint.setColor(0x4DFFFFFF);
            mBackdropPaint.setColor(0x8CFFFFFF);
        }
    }

    /* --------------------------------------------------------- lifecycle */

    void attach() {
        detach();
        mPreDrawListener = () -> {
            if (!mCapturing && isAttachedToWindow()
                    && getVisibility() == VISIBLE
                    && getWidth() > 0 && getHeight() > 0) {
                capture();
            }
            return true;
        };
        mSampleRoot.getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
        invalidate();
        playRevealAnimation();
    }

    void detach() {
        if (mPreDrawListener != null) {
            mSampleRoot.getViewTreeObserver()
                    .removeOnPreDrawListener(mPreDrawListener);
            mPreDrawListener = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        detach();
        super.onDetachedFromWindow();
    }

    /** Re-resolves dark/light and pushes it to the GPU renderer. */
    void refreshConfiguration() {
        mDarkMode = resolveDark(getContext(), detectDarkFromText(mBar));
        if (mTuner != null) {
            mTuner.onTheme(mDarkMode);
        }
        setupFallbackPaints();
        invalidate();
    }

    private void capture() {
        try {
            mCapturing = true;
            maybeRefreshTheme();
            if (mTuner != null) {
                // GPU renderer records content itself; no bitmap is needed.
                return;
            }
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || mSampleRoot.getWidth() <= 0) {
                return;
            }
            ensureRegionBuffer(w, h);

            int[] rootPos = new int[2];
            int[] selfPos = new int[2];
            mSampleRoot.getLocationOnScreen(rootPos);
            getLocationOnScreen(selfPos);
            float dx = selfPos[0] - rootPos[0];
            float dy = selfPos[1] - rootPos[1];

            Canvas c = new Canvas(mRegionBuffer);
            float scale = mUseAgsl ? 1f : LEGACY_SAMPLE_SCALE;
            c.save();
            c.clipRect(0f, 0f, w, h);
            c.scale(scale, scale);
            c.translate(-dx, -dy);
            int visibility = getVisibility();
            setVisibility(INVISIBLE);
            try {
                mSampleRoot.draw(c);
            } finally {
                setVisibility(visibility);
                c.restore();
            }

            boostSaturation(mRegionBuffer);
            if (!mUseAgsl) {
                StackBlur.blur(mRegionBuffer, LEGACY_BLUR_RADIUS);
            }
            invalidate();
        } catch (Throwable t) {
            LiquidGlassModule.logErr("background capture failed", t);
        } finally {
            mCapturing = false;
        }
    }

    private void ensureRegionBuffer(int w, int h) {
        float scale = mUseAgsl ? 1f : LEGACY_SAMPLE_SCALE;
        int bw = Math.max(Math.round(w * scale), 1);
        int bh = Math.max(Math.round(h * scale), 1);
        if (mRegionBuffer == null || mRegionBuffer.isRecycled()
                || mRegionBuffer.getWidth() != bw
                || mRegionBuffer.getHeight() != bh) {
            Bitmap old = mRegionBuffer;
            mRegionBuffer = Bitmap.createBitmap(bw, bh,
                    Bitmap.Config.ARGB_8888);
            if (old != null && !old.isRecycled()) {
                old.recycle();
            }
        } else {
            mRegionBuffer.eraseColor(android.graphics.Color.TRANSPARENT);
        }
    }

    /** Periodically re-probes host label colours so skin switches are
     *  followed live without walking the view tree every frame. */
    private void maybeRefreshTheme() {
        mCaptureCount++;
        if (mCaptureCount % 20 != 1) {
            return;
        }
        HostApp app = LiquidGlassModule.app();
        Boolean probe = app != null && app.preferTextColorProbe
                ? detectDarkFromText(mBar) : null;
        boolean dark = resolveDark(getContext(), probe);
        if (mCaptureCount == 1) {
            LiquidGlassModule.log(android.util.Log.INFO,
                    "theme probe: dark=" + dark + " current=" + mDarkMode);
        }
        if (dark != mDarkMode) {
            mDarkMode = dark;
            if (mTuner != null) {
                mTuner.onTheme(mDarkMode);
            }
            setupFallbackPaints();
            invalidate();
            LiquidGlassModule.log(android.util.Log.INFO,
                    "theme switched: dark=" + mDarkMode);
        }
    }

    private void boostSaturation(Bitmap bitmap) {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(SATURATION_BOOST);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        new Canvas(bitmap).drawBitmap(bitmap, 0f, 0f, paint);
    }

    /* ------------------------------------------------------------- theme */

    /** Config override first, live label probe for hosts that skin
     *  independently of the system, uiMode last. */
    private static boolean resolveDark(Context context, Boolean textProbe) {
        if (GlassConfig.tone != 0) {
            return GlassConfig.tone == 2;
        }
        HostApp app = LiquidGlassModule.app();
        if (app != null && app.preferTextColorProbe && textProbe != null) {
            return textProbe;
        }
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Votes across every plain label colour. Badges (which carry backgrounds)
     * are excluded, and the majority unselected colour wins over a single
     * selected label. Bright label text implies a dark bar.
     */
    static Boolean detectDarkFromText(ViewGroup bar) {
        if (bar == null) {
            return null;
        }
        try {
            java.util.HashMap<Integer, Integer> votes =
                    new java.util.HashMap<>();
            collectLabelColours(bar, votes);
            int bestColour = 0;
            int bestVotes = 0;
            for (java.util.Map.Entry<Integer, Integer> e : votes.entrySet()) {
                if (e.getValue() > bestVotes) {
                    bestVotes = e.getValue();
                    bestColour = e.getKey();
                }
            }
            if (bestVotes == 0) {
                return null;
            }
            float luminance = (0.299f * Color.red(bestColour)
                    + 0.587f * Color.green(bestColour)
                    + 0.114f * Color.blue(bestColour)) / 255f;
            return luminance > 0.5f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void collectLabelColours(View v,
                                            java.util.Map<Integer, Integer> votes) {
        if (v.getVisibility() != VISIBLE) {
            return;
        }
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            if (tv.getBackground() == null && tv.getText() != null
                    && tv.getText().length() > 0) {
                android.content.res.ColorStateList csl = tv.getTextColors();
                if (csl != null) {
                    int colour = csl.getDefaultColor() | 0xFF000000;
                    Integer previous = votes.get(colour);
                    votes.put(colour, previous == null ? 1 : previous + 1);
                }
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectLabelColours(group.getChildAt(i), votes);
            }
        }
    }

    /* ------------------------------------------------------------ reveal */

    private void playRevealAnimation() {
        try {
            setPivotX(getWidth() * 0.5f);
            setPivotY(getHeight());
            setScaleY(0.86f);
            setAlpha(0f);
            animate().alpha(1f).scaleY(1f)
                    .setDuration(REVEAL_DURATION_MS)
                    .setInterpolator(new OvershootInterpolator(1.1f))
                    .start();
        } catch (Throwable ignored) {
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
