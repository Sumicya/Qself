/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Content-hugging geometry: equal-weight tab columns become fixed
 * content-sized columns so the pill wraps its glyphs, and the glass surface
 * is trimmed to the outermost icon extents.
 */
final class GlassHug {

    private final GlassLabelStyle labelStyle;
    private boolean rimTrimDone;

    GlassHug(GlassLabelStyle labelStyle) {
        this.labelStyle = labelStyle;
    }

    /**
     * Replaces equal-weight tab columns with fixed content-sized columns so
     * the pill hugs its glyphs, and returns the total row width. Each column
     * gets the configured breathing room around its content, capped to the
     * screen. In icon-only rows a fixed glyph basis keeps the pill stable
     * instead of tracking badge widths.
     */
    int hugContentWidth(ViewGroup tabRow, float density) {
        if (tabRow == null || tabRow.getChildCount() == 0) {
            return 0;
        }
        labelStyle.apply(tabRow);
        boolean iconOnly = GlassConfig.labelMode == 1
                || GlassLabelStyle.isQqIconOnlyRow(tabRow);
        GlassLabelStyle.applyQqIconOnlyAlignment(tabRow, iconOnly, density);

        int childCount = tabRow.getChildCount();
        int count = 0;
        int unspecified = View.MeasureSpec.makeMeasureSpec(0,
                View.MeasureSpec.UNSPECIFIED);
        int measured = 0;
        int leaf = 0;
        int slot = 0;
        for (int i = 0; i < childCount; i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() == View.GONE) {
                continue;
            }
            count++;
            tab.measure(unspecified, unspecified);
            measured = Math.max(measured, tab.getMeasuredWidth());
            leaf = Math.max(leaf, leafContentWidth(tab));
            slot = Math.max(slot, tab.getWidth());
        }
        if (count == 0) {
            return 0;
        }
        // An unbounded measure wider than the laid-out column is a
        // MATCH_PARENT fallover, not a real content measurement.
        if (slot > 0 && measured > slot) {
            measured = 0;
        }
        int widest = iconOnly ? Math.round(density * 24f)
                : Math.max(measured, leaf);
        if (widest <= 0) {
            return 0;
        }
        int pad = Math.round(density * 4f);
        int tabWidth = widest + Math.round(
                density * GlassConfig.hugPaddingDp);
        int screen = tabRow.getResources().getDisplayMetrics().widthPixels;
        int maxTotal = screen - Math.round(density * 24f);
        if (tabWidth * count + pad * 2 > maxTotal) {
            tabWidth = (maxTotal - pad * 2) / count;
        }
        for (int i = 0; i < childCount; i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() == View.GONE) {
                continue;
            }
            ViewGroup.LayoutParams lp = tab.getLayoutParams();
            if (lp == null) {
                continue;
            }
            lp.width = tabWidth;
            if (lp instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) lp).weight = 0f;
            }
            tab.setLayoutParams(lp);
        }
        tabRow.setPadding(pad, 0, pad, 0);
        int total = tabWidth * count + pad * 2;
        LiquidGlassModule.log(android.util.Log.INFO,
                "row hugged: content=" + widest + " tabs=" + count
                        + " tabWidth=" + tabWidth + " total=" + total);
        return total;
    }

    /** Width of the widest visible leaf, ignoring MATCH_PARENT containers. */
    private static int leafContentWidth(View v) {
        if (v.getVisibility() != View.VISIBLE) {
            return 0;
        }
        if (v instanceof ViewGroup) {
            int widest = 0;
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                widest = Math.max(widest, leafContentWidth(g.getChildAt(i)));
            }
            return widest;
        }
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            return 0;
        }
        if (v instanceof android.widget.TextView
                && !v.getClass().getName().contains("Badge")) {
            android.widget.TextView text = (android.widget.TextView) v;
            return (int) Math.ceil(text.getPaint().measureText(
                    text.getText().toString()))
                    + text.getPaddingLeft() + text.getPaddingRight();
        }
        return v.getWidth();
    }

    /* ------------------------------------------------------------ rim trim */

    /**
     * Trims the glass pill's side margins to the outermost icon extents so a
     * content-hugged row does not leave wide blank glass beyond the glyphs.
     * Runs once after the first real layout.
     */
    void applyMeasuredRimTrim(final FrameLayout host,
                              final LiquidGlassPanel glass, final ViewGroup row) {
        if (rimTrimDone || host == null || glass == null || row == null) {
            return;
        }
        rimTrimDone = true;
        host.post(() -> {
            try {
                int n = row.getChildCount();
                if (n < 2 || host.getWidth() <= 0 || row.getWidth() <= 0) {
                    return;
                }
                View firstIcon = findSmallSquareIcon(row.getChildAt(0), 0);
                View lastIcon = findSmallSquareIcon(row.getChildAt(n - 1), 0);
                if (firstIcon == null || lastIcon == null) {
                    return;
                }
                int pad = Math.max(0, (host.getWidth() - row.getWidth()) / 2);
                View firstTab = row.getChildAt(0);
                View lastTab = row.getChildAt(n - 1);
                int insetL = (firstTab.getWidth() - firstIcon.getWidth()) / 2;
                int insetR = (lastTab.getWidth() - lastIcon.getWidth()) / 2;
                int left = pad + firstTab.getLeft() + insetL;
                int right = host.getWidth()
                        - (pad + lastTab.getLeft() + lastTab.getWidth()
                                - insetR);
                if (left < pad || insetL < 0 || insetR < 0
                        || host.getWidth() - left - right <= 0) {
                    return;
                }
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) glass.getLayoutParams();
                lp.leftMargin = left;
                lp.rightMargin = right;
                glass.setLayoutParams(lp);
                LiquidGlassModule.log(android.util.Log.INFO,
                        "rim trim: left=" + left + " right=" + right
                                + " hostW=" + host.getWidth());
            } catch (Throwable t) {
                LiquidGlassModule.logErr("rim trim failed", t);
            }
        });
    }

    /** The tab's real glyph: a small roughly square ImageView. */
    private static View findSmallSquareIcon(View view, int depth) {
        if (depth > 5) {
            return null;
        }
        if (view instanceof android.widget.ImageView) {
            int w = view.getWidth();
            int h = view.getHeight();
            float density = view.getResources().getDisplayMetrics().density;
            int min = Math.round(12f * density);
            int max = Math.round(44f * density);
            if (w >= min && w <= max && h >= min && h <= max
                    && Math.abs(w - h) <= 8) {
                return view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findSmallSquareIcon(g.getChildAt(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
