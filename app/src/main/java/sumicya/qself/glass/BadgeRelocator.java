// Vendored-derived from liuran001/WeChat-LiquidGlass (MIT): https://github.com/liuran001/WeChat-LiquidGlass
// Extension: QQ unread badge as a plain number (no red capsule) in the tab's top margin band.
package sumicya.qself.glass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;

/**
 * Final badge form: the stock QUIBadge (red capsule painted in its own
 * onDraw, unrestyleable) is hidden, and the count is drawn by an overlay
 * view as a plain number, horizontally centred in the tab's top margin
 * band. The overlay attaches to the glass host layout (a FrameLayout), so
 * nothing is injected into the app's own view trees; counts are still read
 * from the hidden stock badge, and a hook on {@code QUIBadge.updateNum(int)}
 * keeps the overlay repainting when unread counts change.
 */
public final class BadgeRelocator {

    private static final int INSTALLED_TAG_KEY = 0x7F5A0004;

    /** Pure: the label to draw for a badge's current text; null = nothing. */
    static String numberLabel(CharSequence raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Attaches the number overlay once per tab row. */
    public static void install(ViewGroup tabRow, float density) {
        if (tabRow.getTag(INSTALLED_TAG_KEY) != null) {
            return;
        }
        View parent = (View) tabRow.getParent();
        if (!(parent instanceof ViewGroup)) {
            return;
        }
        tabRow.setTag(INSTALLED_TAG_KEY, Boolean.TRUE);
        ViewGroup host = (ViewGroup) parent;
        NumberOverlay overlay = new NumberOverlay(tabRow, density);
        overlay.setClickable(false);
        host.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        syncBounds(tabRow, overlay);
        tabRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            syncBounds(tabRow, overlay);
            overlay.invalidate();
        });
    }

    private static void syncBounds(ViewGroup tabRow, View overlay) {
        int hl = tabRow.getLeft();
        int ht = tabRow.getTop();
        overlay.layout(hl, ht, hl + tabRow.getWidth(), ht + tabRow.getHeight());
    }

    /** Draws one plain number per tab, centred in the top margin band. */
    private static final class NumberOverlay extends View {

        private final ViewGroup mTabRow;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mTextSize;
        private final float mBaseline;
        private boolean mUpdateHooked;

        NumberOverlay(ViewGroup tabRow, float density) {
            super(tabRow.getContext());
            mTabRow = tabRow;
            mTextSize = 10f * density;
            mBaseline = 12f * density;
            mPaint.setTextSize(mTextSize);
            mPaint.setTextAlign(Paint.Align.CENTER);
            mPaint.setColor(Color.WHITE);
            mPaint.setShadowLayer(2f * density, 0f, 0f, 0x99000000);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            for (int i = 0; i < mTabRow.getChildCount(); i++) {
                View tab = mTabRow.getChildAt(i);
                if (!(tab instanceof ViewGroup) || tab.getVisibility() != View.VISIBLE) {
                    continue;
                }
                View badge = findBadge((ViewGroup) tab, 0);
                if (badge == null) {
                    continue;
                }
                badge.setAlpha(0f);
                maybeHookUpdateNum(badge);
                String label = badge instanceof TextView
                        ? numberLabel(((TextView) badge).getText()) : null;
                if (label == null) {
                    continue;
                }
                float cx = tab.getLeft() + tab.getWidth() * 0.5f;
                canvas.drawText(label, cx, mBaseline, mPaint);
            }
        }

        private View findBadge(ViewGroup group, int depth) {
            if (depth > 6) {
                return null;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getWidth() > 0 && child.getClass().getName().contains("Badge")) {
                    return child;
                }
                if (child instanceof ViewGroup) {
                    View found = findBadge((ViewGroup) child, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        private void maybeHookUpdateNum(View badge) {
            if (mUpdateHooked) {
                return;
            }
            mUpdateHooked = true;
            try {
                Method m = badge.getClass().getMethod("updateNum", int.class);
                LiquidGlassModule.hookAfter(m, param -> postInvalidate());
            } catch (Throwable t) {
                LiquidGlassModule.logErr("updateNum hook unavailable", t);
            }
        }
    }

    private BadgeRelocator() {
    }
}
