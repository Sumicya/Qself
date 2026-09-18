/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Strips the stock bar's own chrome once the pill takes over: solid
 * backgrounds, the host's blur layers, the top hairline, tab separators and
 * the gesture-area padding reserve. Everything is remembered so a give-up
 * path can hand the bar back intact.
 */
final class GlassBarChrome {

    private WeakReference<View> blurLayerRef = new WeakReference<>(null);
    private WeakReference<View> hairlineRef = new WeakReference<>(null);
    private final ArrayList<WeakReference<View>> hiddenLines =
            new ArrayList<>();
    private boolean blurRelit;
    private boolean separatorLogged;

    /** Largest visible sibling the bar shares its parent with. */
    ViewGroup findBackdrop(ViewGroup parent, View tabView) {
        ViewGroup best = null;
        int bestArea = 0;
        HostApp app = LiquidGlassModule.app();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == tabView || !(c instanceof ViewGroup)
                    || c.getVisibility() != View.VISIBLE
                    || (app != null && app.isHiddenSibling(
                            c.getClass().getName()))) {
                continue;
            }
            int area = c.getWidth() * c.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = (ViewGroup) c;
            }
        }
        return best;
    }

    /**
     * Clears the solid surfaces the bar used to paint: its own background,
     * the row's and the tab columns' (the top hairline is painted through a
     * non-colour background on some builds). Child badges and ripples keep
     * whatever they carry below the column level.
     */
    void stripSolidBackgrounds(View v) {
        if (v.getBackground() != null) {
            v.setBackground(null);
        }
        if (!(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) v;
        for (int i = 0; i < group.getChildCount(); i++) {
            View row = group.getChildAt(i);
            if (row.getBackground() != null) {
                row.setBackground(null);
            }
            if (row instanceof ViewGroup) {
                ViewGroup columns = (ViewGroup) row;
                for (int j = 0; j < columns.getChildCount(); j++) {
                    View tab = columns.getChildAt(j);
                    if (tab.getBackground() != null) {
                        tab.setBackground(null);
                    }
                }
            }
        }
    }

    void hideOwnBlurLayers(ViewGroup parent, View tabView) {
        HostApp app = LiquidGlassModule.app();
        if (app == null || app.hiddenSiblings.length == 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c != tabView && app.isHiddenSibling(c.getClass().getName())) {
                blurLayerRef = new WeakReference<>(c);
                if (c.getVisibility() != View.GONE) {
                    c.setVisibility(View.GONE);
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "hid host blur layer: "
                                    + c.getClass().getName());
                }
            }
        }
    }

    /** Restores the host blur layer on the give-up path. */
    void showOwnBlurLayers(ViewGroup parent) {
        HostApp app = LiquidGlassModule.app();
        if (app == null || app.hiddenSiblings.length == 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c.getVisibility() == View.GONE
                    && app.isHiddenSibling(c.getClass().getName())) {
                c.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Hides a flat 1px horizontal hairline sibling left docked at the top of
     * the bar once it floats. Matched by shape (bare View, one pixel tall,
     * nearly parent width) because the framework class name is too generic.
     */
    void hideBarHairline(ViewGroup parent, View tabView) {
        float density = parent.getResources().getDisplayMetrics().density;
        int maxThickness = Math.max(2, Math.round(density * 1.5f));
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == tabView || c.getVisibility() != View.VISIBLE
                    || c instanceof ViewGroup) {
                continue;
            }
            if (c.getHeight() > 0 && c.getHeight() <= maxThickness
                    && c.getWidth() >= parent.getWidth() * 0.9f
                    && c.getBackground() != null) {
                c.setVisibility(View.GONE);
                hairlineRef = new WeakReference<>(c);
                LiquidGlassModule.log(android.util.Log.INFO,
                        "hid " + c.getHeight() + "px bar hairline sibling");
            }
        }
    }

    /**
     * Removes every separator the stock bar can carry between or across its
     * tabs: framework {@link android.widget.TabWidget} strips, LinearLayout
     * divider drawables (the material indicator is one), and stray hairline
     * views drawn inside the bar subtree itself — both vertical separators
     * (thin and nearly bar-height, appearing at tab boundaries) and
     * horizontal rules (one pixel and nearly bar-width).
     */
    void suppressTabSeparators(View tabView, ViewGroup row) {
        ArrayList<String> branches = new ArrayList<>();
        if (tabView instanceof android.widget.TabWidget) {
            android.widget.TabWidget tw = (android.widget.TabWidget) tabView;
            tw.setStripEnabled(false);
            tw.setDividerDrawable(null);
            branches.add("tabwidget");
        }
        if (tabView instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) tabView;
            ll.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE);
            ll.setDividerDrawable(null);
            branches.add("bar-divider");
        }
        if (row instanceof LinearLayout && row != tabView) {
            LinearLayout rowLayout = (LinearLayout) row;
            rowLayout.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE);
            rowLayout.setDividerDrawable(null);
            branches.add("row-divider");
        }
        int before = hiddenLines.size();
        hideSubtreeLines(tabView, 0);
        int newlyHidden = hiddenLines.size() - before;
        if (!separatorLogged) {
            separatorLogged = true;
            dumpThinViews(tabView, 0);
            LiquidGlassModule.log(android.util.Log.INFO,
                    "separator suppression: " + branches
                            + " shape-matched lines=" + newlyHidden);
        } else if (newlyHidden > 0) {
            LiquidGlassModule.log(android.util.Log.INFO,
                    "separator suppression re-hid lines=" + newlyHidden);
        }
    }

    /** One-shot inventory of every line-shaped view for device diagnosis. */
    private void dumpThinViews(View v, int depth) {
        if (depth > 8) {
            return;
        }
        float density = v.getResources().getDisplayMetrics().density;
        int thickness = Math.max(3, Math.round(density * 2f));
        if (v.getWidth() > 0 && v.getHeight() > 0
                && ((v.getWidth() <= thickness
                        && v.getHeight() >= v.getResources()
                                .getDisplayMetrics().heightPixels * 0.01f)
                || (v.getHeight() <= thickness
                        && v.getWidth() >= 40))) {
            LiquidGlassModule.log(android.util.Log.INFO,
                    "thin-view d" + depth + " "
                            + v.getClass().getName()
                            + " " + v.getWidth() + "x" + v.getHeight()
                            + "@(" + v.getLeft() + "," + v.getTop() + ")"
                            + " vis=" + v.getVisibility()
                            + " bg=" + (v.getBackground() != null)
                            + " leaf=" + !(v instanceof ViewGroup));
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpThinViews(group.getChildAt(i), depth + 1);
            }
        }
    }

    private void hideSubtreeLines(View v, int depth) {
        if (depth > 6 || !(v instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) v;
        float density = v.getResources().getDisplayMetrics().density;
        int maxThickness = Math.max(2, Math.round(density * 1.5f));
        int barWidth = group.getWidth();
        int barHeight = group.getHeight();
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE
                    || c instanceof ViewGroup || c.getBackground() == null) {
                if (c instanceof ViewGroup) {
                    hideSubtreeLines(c, depth + 1);
                }
                continue;
            }
            int w = c.getWidth();
            int h = c.getHeight();
            boolean vertical = w > 0 && w <= maxThickness
                    && h >= barHeight * 0.6f;
            boolean horizontal = h > 0 && h <= maxThickness
                    && barWidth > 0 && w >= barWidth * 0.7f;
            if (vertical || horizontal) {
                c.setVisibility(View.GONE);
                hiddenLines.add(new WeakReference<>(c));
                LiquidGlassModule.log(android.util.Log.INFO,
                        "hid stock bar line: " + w + "x" + h
                                + (vertical ? " vertical" : " horizontal"));
            }
        }
    }

    private void holdLinesHidden() {
        for (int i = hiddenLines.size() - 1; i >= 0; i--) {
            View v = hiddenLines.get(i).get();
            if (v == null) {
                hiddenLines.remove(i);
            } else if (v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
            }
        }
    }

    private void holdHidden(View v, boolean report) {
        if (v == null || v.getVisibility() == View.GONE) {
            return;
        }
        v.setVisibility(View.GONE);
        if (report && !blurRelit) {
            blurRelit = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "host re-lit its blur layer; held hidden");
        }
    }

    /** Re-hides chrome a skin refresh or bar rebuild can bring back. */
    void holdOwnBarChromeHidden(LiquidGlassHostLayout host, View tabView) {
        android.view.ViewParent rawParent = host.getParent();
        HostApp app = LiquidGlassModule.app();
        if (rawParent instanceof ViewGroup && app != null
                && app.hiddenSiblings.length > 0) {
            ViewGroup parent = (ViewGroup) rawParent;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View c = parent.getChildAt(i);
                if (c != tabView
                        && app.isHiddenSibling(c.getClass().getName())) {
                    blurLayerRef = new WeakReference<>(c);
                    holdHidden(c, true);
                }
            }
        } else {
            holdHidden(blurLayerRef.get(), true);
        }
        holdHidden(hairlineRef.get(), false);
        holdLinesHidden();
        if (tabView != null) {
            ViewGroup row = TabBarBridge.findTabRow(
                    tabView instanceof ViewGroup ? (ViewGroup) tabView
                            : null);
            suppressTabSeparators(tabView, row);
        }
    }

    /** Drops the bottom padding the docked bar kept for the gesture area. */
    int dropNavReserve(View tabView) {
        int reserve = tabView.getPaddingBottom();
        if (reserve <= 0) {
            return 0;
        }
        tabView.setPadding(tabView.getPaddingLeft(), tabView.getPaddingTop(),
                tabView.getPaddingRight(), 0);
        LiquidGlassModule.log(android.util.Log.INFO,
                "dropped " + reserve + "px navigation reserve");
        return reserve;
    }

    /**
     * Symmetric content height: mirror whatever gap the bar leaves above its
     * icons below them. The reported bar height can include the navigation
     * inset through routes padding inspection does not reach, which would
     * otherwise leave a dead band of glass under the tabs. Only ever shrinks.
     */
    int contentBarHeight(ViewGroup tabRow, int fallback) {
        if (tabRow == null || fallback <= 0) {
            return fallback;
        }
        int top = Integer.MAX_VALUE;
        int bottom = 0;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() != View.VISIBLE) {
                continue;
            }
            int[] b = {Integer.MAX_VALUE, 0};
            leafBounds(tab, 0, b);
            if (b[0] < b[1]) {
                int base = tabRow.getTop() + tab.getTop();
                top = Math.min(top, base + b[0]);
                bottom = Math.max(bottom, base + b[1]);
            }
        }
        if (top == Integer.MAX_VALUE || bottom <= top) {
            return fallback;
        }
        int symmetric = bottom + top;
        return symmetric > 0 && symmetric < fallback ? symmetric : fallback;
    }

    private static void leafBounds(View v, int offset, int[] out) {
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                leafBounds(c, offset + c.getTop(), out);
            }
            return;
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0) {
            return;
        }
        out[0] = Math.min(out[0], offset);
        out[1] = Math.max(out[1], offset + v.getHeight());
    }

    /** Clears per-window chrome memory when the installer resets. */
    void reset() {
        blurLayerRef = new WeakReference<>(null);
        hairlineRef = new WeakReference<>(null);
        hiddenLines.clear();
        blurRelit = false;
    }
}
