/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * The selection light layer.
 *
 * <p>This view never captures, magnifies or repaints the tab bar: the real
 * tabs (and the host's own selection highlight) stay untouched beneath it.
 * It only paints two things, following the Nagram edge-refraction model:
 * <ul>
 *   <li>at rest, a very soft accent range block for the selected tab
 *       (a flat 9% accent rounded rect — deliberately edge-less because the
 *       fill is too light to read as a contour);</li>
 *   <li>while pressed, a local white radial ripple; the lens itself does
 *       not move, scale or redraw its contents.</li>
 * </ul>
 * The old magnified tab repaint, embedded pill repaint, seven-tap dispersion
 * and radial resting glow were the source of the centred dark "miniature"
 * ghosting and have all been removed.
 */
final class DropletPanel extends View {

    private static final int LABEL_SCAN_DEPTH = 4;
    private static final float GLOW_RADIUS_DP = 18f;
    private static final float GLOW_ALPHA_NIGHT = 23f / 255f;   // ~9%
    private static final float GLOW_ALPHA_DAY = 18f / 255f;
    private static final float RIPPLE_ALPHA = 0.14f;

    private WeakReference<ViewGroup> mTabRowRef;
    private final float mDensity;
    private boolean mNight;

    private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRipplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private RadialGradient mRippleShader;
    private int mGlowW = -1;
    private int mGlowH = -1;

    private int mAccentCache;
    private float mProgress;

    DropletPanel(Context context, ViewGroup backdrop, ViewGroup tabRow,
                 float density, boolean night) {
        super(context);
        mTabRowRef = new WeakReference<>(tabRow);
        mDensity = density;
        mNight = night;
        setWillNotDraw(false);
    }

    /** Retained for the installer contract; the light layer owns no pill copy. */
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

    /** Kept for the controller contract; translation alone needs no capture. */
    void refresh() {
        if (mProgress > 0.01f) {
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mGlowW = -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = Math.min(GLOW_RADIUS_DP * mDensity, h * 0.5f);

        // Resting range block: a faint, flat accent rounded rect.
        float glowAlpha = (mNight ? GLOW_ALPHA_NIGHT : GLOW_ALPHA_DAY)
                * (1f - 0.55f * mProgress);
        if (glowAlpha > 0.004f) {
            int accent = accentColour(mTabRowRef.get());
            mGlowPaint.setColor(accent != 0 ? accent : 0xFFFFFFFF);
            mGlowPaint.setAlpha(Math.round(0xFF * glowAlpha));
            mRect.set(0f, 0f, w, h);
            canvas.drawRoundRect(mRect, r, r, mGlowPaint);
        }

        // Press ripple: local white bloom, never a scaled copy of content.
        if (mProgress > 0.01f) {
            if (mGlowW != w || mGlowH != h || mRippleShader == null) {
                float cx = w * 0.5f;
                float cy = h * 0.5f;
                float radius = Math.max(w, h) * 0.72f;
                int core = Math.round(0xFF * RIPPLE_ALPHA * mProgress);
                mRippleShader = new RadialGradient(cx, cy, radius,
                        new int[]{(core << 24) | 0x00FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 1f}, Shader.TileMode.CLAMP);
                mRipplePaint.setShader(mRippleShader);
                mGlowW = w;
                mGlowH = h;
            } else {
                // Rebuild alpha cheaply when only progress changed.
                float cx = w * 0.5f;
                float cy = h * 0.5f;
                float radius = Math.max(w, h) * 0.72f;
                int core = Math.round(0xFF * RIPPLE_ALPHA * mProgress);
                mRippleShader = new RadialGradient(cx, cy, radius,
                        new int[]{(core << 24) | 0x00FFFFFF, 0x00FFFFFF},
                        new float[]{0f, 1f}, Shader.TileMode.CLAMP);
                mRipplePaint.setShader(mRippleShader);
            }
            canvas.drawRect(0f, 0f, w, h, mRipplePaint);
        }
    }

    /* ----------------------------------------------------------- accent */

    /**
     * Selection colour read from the currently selected tab's label, with
     * near-grey readings rejected (they come from an unsettled selection).
     * Returns 0 when no accent can be resolved; the caller then paints white.
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
