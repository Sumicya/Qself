/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Moves QQ's bottom tab bar into the floating liquid-glass pill.
 *
 * <p>The content area already lays out full height with the bar floating over
 * it inside a FrameLayout, so only the bar itself is reparented into
 * {@link LiquidGlassHostLayout}; the solid surface behind it is stripped, the
 * host's own blur layer and separator lines are hidden, equal-weight tab
 * columns become content-sized, and the pager's pages are stretched under the
 * now-floating bar. Bar discovery and hooks live in {@link TabBarBridge}.
 */
public final class LiquidGlassInstaller {

    private static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_DELAY_MS = 250L;
    private static final long REVEAL_TIMEOUT_MS = 8000L;

    /** Scroll clearance below the last row once it clears the pill. */
    private static final float LAST_ROW_GAP_DP = 8f;
    private static final int EXTEND_TAG_KEY = 0x7F5A0001;
    private static final int ICON_ONLY_TAG_KEY = 0x7F5A0003;

    private static final int[] sLoc = new int[2];
    private static boolean sKeepFailed;

    private static WeakReference<Activity> sActivityRef = new WeakReference<>(null);
    private static WeakReference<LiquidGlassHostLayout> sHostRef =
            new WeakReference<>(null);
    private static WeakReference<View> sTabViewRef = new WeakReference<>(null);
    private static WeakReference<View> sGlassRef = new WeakReference<>(null);
    private static WeakReference<View> sDropletRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> sTabRowRef =
            new WeakReference<>(null);
    private static WeakReference<ViewGroup> sPagerRef =
            new WeakReference<>(null);
    private static DropletDragController sDrag;

    private static int sLastIndex = -1;
    private static int sTabStructureSignature;
    private static boolean sTabStructureRefreshPosted;
    private static int sBarHeight;

    private static int sNavigationInset;
    private static WeakReference<View> sBlurLayerRef =
            new WeakReference<>(null);
    private static WeakReference<View> sHairlineRef =
            new WeakReference<>(null);
    private static final ArrayList<WeakReference<View>> sHiddenLines =
            new ArrayList<>();
    private static WeakReference<View> sNavBgRef = new WeakReference<>(null);
    private static int sNavBgId = -1;
    private static boolean sBlurRelit;
    private static boolean sSeparatorLogged;
    private static float sDropletBaseY;
    private static boolean sRimTrimDone;

    private static final WeakHashMap<android.widget.TextView, Float> sTitleAlphas =
            new WeakHashMap<>();
    private static final WeakHashMap<android.widget.TextView,
            android.content.res.ColorStateList> sTitleColors =
            new WeakHashMap<>();

    private LiquidGlassInstaller() {
    }

    /* --------------------------------------------------------- scheduling */

    public static void scheduleInstall(Activity activity) {
        sActivityRef = new WeakReference<>(activity);
        View decor = activity.getWindow().getDecorView();
        if (sHostRef.get() == null) {
            hideStockBarUntilInstalled(decor);
        }
        decor.post(() -> tryInstall(activity, decor, 0));
    }

    /**
     * Fades the stock bar out before the first pill frame and hands it back
     * if the pill never takes over. The app's own blur layer is hidden at the
     * same instant, otherwise a grey band stays lit during cold start.
     */
    private static void hideStockBarUntilInstalled(View decor) {
        decor.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private final long deadline = android.os.SystemClock
                            .uptimeMillis() + REVEAL_TIMEOUT_MS;
                    private View bar;

                    @Override
                    public boolean onPreDraw() {
                        if (bar == null || bar.getParent() == null) {
                            bar = TabBarBridge.findTabView(decor);
                        }
                        boolean installed = bar != null
                                && bar.getParent() instanceof LiquidGlassHostLayout;
                        boolean expired = android.os.SystemClock.uptimeMillis()
                                > deadline;
                        if (installed || expired) {
                            if (bar != null && !installed) {
                                bar.setAlpha(1f);
                                if (bar.getParent() instanceof ViewGroup) {
                                    showOwnBlurLayers((ViewGroup) bar.getParent());
                                }
                                LiquidGlassModule.log(android.util.Log.WARN,
                                        "pill never installed, stock bar restored");
                            }
                            decor.getViewTreeObserver()
                                    .removeOnPreDrawListener(this);
                        } else if (bar != null && bar.getAlpha() != 0f) {
                            bar.setAlpha(0f);
                            if (bar.getParent() instanceof ViewGroup) {
                                hideOwnBlurLayers((ViewGroup) bar.getParent(),
                                        bar);
                            }
                        }
                        return true;
                    }
                });
    }

    private static void tryInstall(Activity activity, View decor, int attempt) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            LiquidGlassHostLayout live = sHostRef.get();
            if (live != null && live.isAttachedToWindow()
                    && live.getRootView() == decor.getRootView()) {
                // Same window, coming back from another screen: re-assert.
                applyLabelSettings(sTabRowRef.get());
                live.refreshConfiguration();
                decor.post(() -> refreshTabStructure(live));
                reassertBottom();
                return;
            }
            if (live != null) {
                resetState();
            }
            ViewGroup tabView = TabBarBridge.locateTabView(decor);
            if (tabView == null) {
                if (attempt < MAX_ATTEMPTS) {
                    decor.postDelayed(
                            () -> tryInstall(activity, decor, attempt + 1),
                            RETRY_DELAY_MS);
                } else {
                    LiquidGlassModule.log(android.util.Log.WARN,
                            "tab bar not found after " + MAX_ATTEMPTS
                                    + " attempts; tree="
                                    + TabBarBridge.describeTree(decor));
                }
                return;
            }
            install(tabView);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("install failed", t);
        }
    }

    /** Drops a previous activity's view references so a relaunch reinstalls. */
    private static void resetState() {
        sHostRef = new WeakReference<>(null);
        sTabViewRef = new WeakReference<>(null);
        sGlassRef = new WeakReference<>(null);
        sDropletRef = new WeakReference<>(null);
        sTabRowRef = new WeakReference<>(null);
        sPagerRef = new WeakReference<>(null);
        sDrag = null;
        sTabStructureSignature = 0;
        sTabStructureRefreshPosted = false;
        sBarHeight = 0;
        sBlurLayerRef = new WeakReference<>(null);
        sHairlineRef = new WeakReference<>(null);
        sHiddenLines.clear();
        sNavBgRef = new WeakReference<>(null);
        sBlurRelit = false;
        sLastIndex = -1;
        sDropletBaseY = 0f;
    }

    static ViewGroup currentPager() {
        return sPagerRef.get();
    }

    /* ------------------------------------------------------------ install */

    private static void install(ViewGroup tabView) {
        ViewGroup parent = tabView.getParent() instanceof ViewGroup
                ? (ViewGroup) tabView.getParent() : null;
        if (parent == null || parent instanceof LiquidGlassHostLayout) {
            return;
        }
        ViewGroup backdrop = findBackdrop(parent, tabView);
        if (backdrop == null) {
            LiquidGlassModule.log(android.util.Log.WARN,
                    "no backdrop sibling found; glass would refract nothing");
            return;
        }
        int index = parent.indexOfChild(tabView);
        if (index < 0) {
            return;
        }
        ViewGroup.LayoutParams originalLp = tabView.getLayoutParams();

        Context ctx = tabView.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int bottomOffset = Math.round(density * GlassConfig.barOffsetDp);
        int navigationInset = rememberNavigationInset(parent);

        LiquidGlassHostLayout host = new LiquidGlassHostLayout(
                ctx, backdrop, tabView);

        int navReserve = tabView.getPaddingBottom();
        ViewGroup tabRow = TabBarBridge.findTabRow(tabView);
        int barHeight = contentBarHeight(tabRow,
                tabView.getHeight() - navReserve);

        host.setupShadow(density, isNight(ctx));
        int shadowPad = host.shadowPad();

        TabGeometrySnapshot geometry = new TabGeometrySnapshot(tabView, tabRow);
        FrameLayout.LayoutParams hostLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
                        | android.view.Gravity.CENTER_HORIZONTAL);
        hostLp.bottomMargin = bottomOffset - shadowPad;
        FrameLayout.LayoutParams tabLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight > 0 ? barHeight
                        : ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL);

        // Structural transaction: either both levels land or the bar returns
        // to its original slot; the native bar is never left detached.
        try {
            parent.removeView(tabView);
            parent.addView(host, index, hostLp);
            host.addView(tabView, tabLp);
        } catch (Throwable t) {
            restoreAfterFailedReparent(parent, tabView, host, index, originalLp);
            LiquidGlassModule.logErr("could not reparent the tab bar", t);
            return;
        }

        try {
            dropNavReserve(tabView);
            int barWidth = hugContentWidth(tabRow, density);
            if (barWidth > 0) {
                hostLp.width = barWidth + shadowPad * 2;
                host.setLayoutParams(hostLp);
            }
        } catch (Throwable t) {
            try {
                geometry.restore(density);
            } catch (Throwable restoreError) {
                LiquidGlassModule.logErr("could not restore tab geometry",
                        restoreError);
            }
            restoreAfterFailedReparent(parent, tabView, host, index, originalLp);
            LiquidGlassModule.logErr("could not size the floating bar", t);
            return;
        }

        tabView.setAlpha(1f);

        sHostRef = new WeakReference<>(host);
        sTabViewRef = new WeakReference<>(tabView);
        sTabRowRef = new WeakReference<>(tabRow);
        sTabStructureSignature = tabStructureSignature(tabRow);
        sTabStructureRefreshPosted = false;
        sPagerRef = new WeakReference<>(backdrop);
        sBarHeight = barHeight;
        sLastIndex = -1;

        TabBarBridge.tryHookPager(backdrop);

        try {
            hideOwnBlurLayers(parent, tabView);
            hideBarHairline(parent, tabView);
            stripSolidBackgrounds(tabView);
            suppressTabSeparators(tabView, tabRow);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("partial stock chrome removal", t);
        }

        // QQ lays its decor out edge to edge already, so the nav inset counts
        // toward the anchor even without growing the window ourselves.
        hostLp.bottomMargin = bottomOffset - shadowPad + navigationInset;
        host.setLayoutParams(hostLp);
        host.post(() -> syncHostBottomInset(host, sNavigationInset));

        unclipAncestors(parent);
        attachRenderer(ctx, host, backdrop, density);
        installSelectionWatcher(host);
        watchBottomInset(host, backdrop);

        host.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    private boolean done;

                    @Override
                    public void onGlobalLayout() {
                        if (done) {
                            return;
                        }
                        done = true;
                        host.getViewTreeObserver()
                                .removeOnGlobalLayoutListener(this);
                        host.attach();
                        syncDropletSize(TabBarBridge.currentIndex(tabView));
                        extendPagesToBottom(backdrop);
                        host.postDelayed(() -> extendPagesToBottom(backdrop),
                                500L);
                        LiquidGlassModule.log(android.util.Log.INFO,
                                "pill installed: hostW=" + host.getWidth()
                                        + " hostH=" + host.getHeight()
                                        + " barH=" + tabView.getHeight());
                    }
                });
    }

    private static void restoreAfterFailedReparent(
            ViewGroup parent, View tabView, LiquidGlassHostLayout host,
            int index, ViewGroup.LayoutParams originalLp) {
        try {
            if (tabView.getParent() instanceof ViewGroup
                    && tabView.getParent() != parent) {
                ((ViewGroup) tabView.getParent()).removeView(tabView);
            }
            if (host.getParent() instanceof ViewGroup) {
                ((ViewGroup) host.getParent()).removeView(host);
            }
            if (tabView.getParent() == null) {
                int safeIndex = Math.max(0,
                        Math.min(index, parent.getChildCount()));
                if (originalLp != null) {
                    parent.addView(tabView, safeIndex, originalLp);
                } else {
                    parent.addView(tabView, safeIndex);
                }
            }
            tabView.setAlpha(1f);
        } catch (Throwable restoreError) {
            LiquidGlassModule.logErr("could not restore the stock bar",
                    restoreError);
        }
    }

    /** Reversible geometry snapshot for equal-weight tab columns. */
    private static final class TabGeometrySnapshot {
        private final View mTabView;
        private final int[] mTabPadding = new int[4];
        private final ViewGroup mRow;
        private final int[] mRowPadding = new int[4];
        private final View[] mTabs;
        private final int[] mWidths;
        private final float[] mWeights;
        private final boolean[] mHadLayoutParams;

        TabGeometrySnapshot(View tabView, ViewGroup row) {
            mTabView = tabView;
            mTabPadding[0] = tabView.getPaddingLeft();
            mTabPadding[1] = tabView.getPaddingTop();
            mTabPadding[2] = tabView.getPaddingRight();
            mTabPadding[3] = tabView.getPaddingBottom();
            mRow = row;
            int count = row == null ? 0 : row.getChildCount();
            mTabs = new View[count];
            mWidths = new int[count];
            mWeights = new float[count];
            mHadLayoutParams = new boolean[count];
            if (row == null) {
                return;
            }
            mRowPadding[0] = row.getPaddingLeft();
            mRowPadding[1] = row.getPaddingTop();
            mRowPadding[2] = row.getPaddingRight();
            mRowPadding[3] = row.getPaddingBottom();
            for (int i = 0; i < count; i++) {
                View tab = row.getChildAt(i);
                mTabs[i] = tab;
                ViewGroup.LayoutParams lp = tab.getLayoutParams();
                if (lp == null) {
                    continue;
                }
                mHadLayoutParams[i] = true;
                mWidths[i] = lp.width;
                mWeights[i] = lp instanceof LinearLayout.LayoutParams
                        ? ((LinearLayout.LayoutParams) lp).weight : 0f;
            }
        }

        void restore(float density) {
            mTabView.setPadding(mTabPadding[0], mTabPadding[1],
                    mTabPadding[2], mTabPadding[3]);
            if (mRow == null) {
                return;
            }
            applyQqIconOnlyAlignment(mRow, false, density);
            mRow.setPadding(mRowPadding[0], mRowPadding[1],
                    mRowPadding[2], mRowPadding[3]);
            for (int i = 0; i < mTabs.length; i++) {
                if (!mHadLayoutParams[i]) {
                    continue;
                }
                View tab = mTabs[i];
                ViewGroup.LayoutParams lp = tab.getLayoutParams();
                if (lp == null) {
                    continue;
                }
                lp.width = mWidths[i];
                if (lp instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) lp).weight = mWeights[i];
                }
                tab.setLayoutParams(lp);
            }
        }
    }

    /* --------------------------------------------------- stock chrome cull */

    /** Largest visible sibling the bar shares its parent with. */
    private static ViewGroup findBackdrop(ViewGroup parent, View tabView) {
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
     * Clears the solid surfaces the bar used to paint: its own background, the
     * row's and the tab columns' (the top hairline is painted through a
     * non-colour background on some builds). Child badges and ripples keep
     * whatever they carry below the column level.
     */
    private static void stripSolidBackgrounds(View v) {
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

    private static void hideOwnBlurLayers(ViewGroup parent, View tabView) {
        HostApp app = LiquidGlassModule.app();
        if (app == null || app.hiddenSiblings.length == 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c != tabView && app.isHiddenSibling(c.getClass().getName())) {
                sBlurLayerRef = new WeakReference<>(c);
                if (c.getVisibility() != View.GONE) {
                    c.setVisibility(View.GONE);
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "hid host blur layer: " + c.getClass().getName());
                }
            }
        }
    }

    /** Restores the host blur layer on the give-up path. */
    private static void showOwnBlurLayers(ViewGroup parent) {
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
    private static void hideBarHairline(ViewGroup parent, View tabView) {
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
                sHairlineRef = new WeakReference<>(c);
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
    private static void suppressTabSeparators(View tabView, ViewGroup row) {
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
        int before = sHiddenLines.size();
        hideSubtreeLines(tabView, 0);
        int newlyHidden = sHiddenLines.size() - before;
        if (!sSeparatorLogged) {
            sSeparatorLogged = true;
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
    private static void dumpThinViews(View v, int depth) {
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

    private static void hideSubtreeLines(View v, int depth) {
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
                sHiddenLines.add(new WeakReference<>(c));
                LiquidGlassModule.log(android.util.Log.INFO,
                        "hid stock bar line: " + w + "x" + h
                                + (vertical ? " vertical" : " horizontal"));
            }
        }
    }

    private static void holdSubtreeLinesHidden() {
        for (int i = sHiddenLines.size() - 1; i >= 0; i--) {
            View v = sHiddenLines.get(i).get();
            if (v == null) {
                sHiddenLines.remove(i);
            } else if (v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
            }
        }
    }

    /** Drops the bottom padding the docked bar kept for the gesture area. */
    private static int dropNavReserve(View tabView) {
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
    private static int contentBarHeight(ViewGroup tabRow, int fallback) {
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

    /* ------------------------------------------------------------ renderer */

    private static boolean isNight(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static void attachRenderer(Context ctx, LiquidGlassHostLayout host,
                                       ViewGroup backdrop, float density) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            LiquidGlassModule.log(android.util.Log.INFO,
                    "SDK < 33, legacy frost path");
            return;
        }
        try {
            boolean night = isNight(ctx);

            final LiquidGlassPanel glass =
                    new LiquidGlassPanel(ctx, backdrop, density, night);
            host.addView(glass, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            final DropletPanel droplet = new DropletPanel(
                    ctx, backdrop, sTabRowRef.get(), density, night);
            droplet.setVisibility(View.INVISIBLE);
            host.addView(droplet, new FrameLayout.LayoutParams(0, 0,
                    android.view.Gravity.TOP | android.view.Gravity.START));
            droplet.setPill(glass);
            sDropletRef = new WeakReference<>(droplet);
            // The droplet scales past the pill while held; both clipping
            // defaults would shear the overflow off.
            host.setClipChildren(false);
            host.setClipToPadding(false);
            sGlassRef = new WeakReference<>(glass);

            host.setGlassTuner(new LiquidGlassHostLayout.GlassTuner() {
                @Override
                public void onSize(int w, int h, float cornerRadius) {
                    glass.invalidate();
                    applyMeasuredRimTrim(host, glass, sTabRowRef.get());
                }

                @Override
                public void onTheme(boolean dark) {
                    glass.setTheme(dark);
                    droplet.setTheme(dark);
                }
            });

            ViewGroup tabRow = sTabRowRef.get();
            if (tabRow != null) {
                sDrag = new DropletDragController(
                        droplet, tabRow, density, night);
                sDrag.setPill(glass);
                sDrag.setHost(host);
                host.setDragHandler(sDrag);
            }

            host.getViewTreeObserver().addOnPreDrawListener(() -> {
                glass.invalidate();
                return true;
            });

            LiquidGlassModule.log(android.util.Log.INFO,
                    "renderer attached, supported=" + glass.isSupported()
                            + " drag=" + (tabRow != null));
        } catch (Throwable t) {
            LiquidGlassModule.logErr("glass renderer unavailable", t);
        }
    }

    /* ------------------------------------------------------------- droplet */

    /** Hook/getter index resolved to the row's current visible slot. */
    private static int resolveTabSlot(int appIndex) {
        ViewGroup tabRow = sTabRowRef.get();
        int selected = TabBarBridge.selectedIndex(tabRow);
        return selected >= 0 ? selected
                : TabBarBridge.slotForIndex(tabRow, appIndex);
    }

    /**
     * Tab switch entry point, hooked on the bar class and also fired by the
     * host during startup — doubling as the install trigger once the bar is
     * guaranteed to exist.
     */
    static void onTabChanged(View tabView, int index) {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host != null && !host.isAttachedToWindow()) {
            resetState();
            host = null;
        }
        if (host == null || host.getParent() == null
                || tabView.getParent() != host) {
            if (tabView instanceof ViewGroup && tabView.getParent() != null) {
                tabView.post(() -> {
                    try {
                        install((ViewGroup) tabView);
                        syncDropletSize(resolveTabSlot(index));
                    } catch (Throwable t) {
                        LiquidGlassModule.logErr("install from switch failed", t);
                    }
                });
            }
            return;
        }
        LiquidGlassHostLayout installed = host;
        installed.post(() -> {
            if (scheduleTabStructureRefreshIfNeeded(installed)) {
                return;
            }
            int slot = resolveTabSlot(index);
            if (slot < 0) {
                return;
            }
            syncDropletSize(slot);
            if (sDrag != null) {
                sDrag.animateToIndex(slot, false);
            }
        });
    }

    /**
     * Sizes and vertically places the droplet for a tab column; horizontal
     * motion belongs to {@link DropletDragController}'s springs.
     */
    private static void syncDropletSize(int index) {
        try {
            View droplet = sDropletRef.get();
            ViewGroup tabRow = sTabRowRef.get();
            LiquidGlassHostLayout host = sHostRef.get();
            if (droplet == null || tabRow == null || host == null
                    || index < 0) {
                return;
            }
            View tab = TabBarBridge.tabAt(tabRow, index);
            if (tab == null || tab.getWidth() == 0) {
                return;
            }
            float density = host.getResources().getDisplayMetrics().density;
            // Light block hugs the pill inner wall (8dp top/bottom) and leaves
            // a 3dp soft seam between neighbouring tab slots.
            int inset = Math.round(density * 8f);
            int w = tab.getWidth() - Math.round(density * 6f);
            int h = tab.getHeight() - inset * 2;
            if (w <= 0 || h <= 0) {
                return;
            }
            ViewGroup.LayoutParams lp = droplet.getLayoutParams();
            if (lp.width != w || lp.height != h) {
                lp.width = w;
                lp.height = h;
                droplet.setLayoutParams(lp);
            }
            sDropletBaseY = tab.getTop() + tabRow.getTop() + inset;
            droplet.setTranslationY(sDropletBaseY);
            droplet.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("droplet sizing failed", t);
        }
    }

    /**
     * Trims the glass pill's side margins to the outermost icon extents so a
     * content-hugged row does not leave wide blank glass beyond the glyphs.
     * Runs once after the first real layout.
     */
    private static void applyMeasuredRimTrim(final FrameLayout host,
            final LiquidGlassPanel glass, final ViewGroup row) {
        if (sRimTrimDone || host == null || glass == null || row == null) {
            return;
        }
        sRimTrimDone = true;
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

    /* ------------------------------------------------- labels and widths */

    /**
     * Finds a tab's real title TextView, excluding unread badges: badges are
     * named Badge/RedTouch, titles carry their original title as a tag, and a
     * plain backgroundless TextView is treated as a title.
     */
    private static android.widget.TextView findTabTitle(View v) {
        if (v instanceof android.widget.TextView) {
            android.widget.TextView text = (android.widget.TextView) v;
            CharSequence value = text.getText();
            String name = v.getClass().getName();
            boolean nonEmpty = value != null
                    && value.toString().trim().length() > 0;
            boolean badge = name.contains("Badge")
                    || name.contains("RedTouch");
            if (nonEmpty && !badge
                    && (text.getTag() instanceof CharSequence
                    || name.contains("BlendTextView")
                    || v.getBackground() == null)) {
                return text;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found =
                        findTabTitle(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Applies label visibility, colour and size, then aligns icon-only rows. */
    private static void applyLabelSettings(ViewGroup row) {
        if (row == null) {
            return;
        }
        int count = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View tab = row.getChildAt(i);
            if (tab.getVisibility() != View.VISIBLE) {
                continue;
            }
            count++;
            android.widget.TextView title = findTabTitle(tab);
            if (title == null) {
                continue;
            }
            if (GlassConfig.labelMode == 1) {
                // Alpha only: keeps the host text as the badge layout anchor.
                if (!sTitleAlphas.containsKey(title)) {
                    sTitleAlphas.put(title, title.getAlpha());
                }
                title.setAlpha(0f);
            } else if (sTitleAlphas.containsKey(title)) {
                title.setAlpha(sTitleAlphas.remove(title));
            }
            if (GlassConfig.tone != 0) {
                if (!sTitleColors.containsKey(title)) {
                    sTitleColors.put(title, title.getTextColors());
                }
                title.setTextColor(GlassConfig.tone == 2
                        ? 0xFFF4F4F4 : 0xFF202020);
            } else if (sTitleColors.containsKey(title)) {
                title.setTextColor(sTitleColors.remove(title));
            }
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    GlassConfig.labelSize);
        }
        GlassConfig.visibleTabCount = count;
        float density = row.getResources().getDisplayMetrics().density;
        boolean iconOnly = GlassConfig.labelMode == 1
                || isQqIconOnlyRow(row);
        applyQqIconOnlyAlignment(row, iconOnly, density);
    }

    private static boolean hasUsableTabTitle(View tab) {
        android.widget.TextView title = findTabTitle(tab);
        if (title == null || title.getVisibility() != View.VISIBLE) {
            return false;
        }
        ViewGroup.LayoutParams lp = title.getLayoutParams();
        return lp == null || (lp.width != 0 && lp.height != 0);
    }

    /** Finds the host-owned icon view by class name inside one tab. */
    private static View findIconByClass(View v) {
        HostApp app = LiquidGlassModule.app();
        if (app != null && app.isTabIconClass(v.getClass().getName())) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findIconByClass(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** True when every tab still has an icon but none has a visible title. */
    private static boolean isQqIconOnlyRow(ViewGroup row) {
        if (LiquidGlassModule.app() != HostApp.QQ || row == null) {
            return false;
        }
        int icons = 0;
        int titles = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View tab = row.getChildAt(i);
            if (tab.getVisibility() == View.GONE
                    || findIconByClass(tab) == null) {
                continue;
            }
            icons++;
            if (hasUsableTabTitle(tab)) {
                titles++;
            }
        }
        return icons > 0 && titles == 0;
    }

    /** Adds or removes one reversible vertical offset on a host view. */
    private static void setIconOnlyTranslation(View v, boolean iconOnly,
                                               float offset) {
        Object saved = v.getTag(ICON_ONLY_TAG_KEY);
        if (iconOnly) {
            float base = saved instanceof Float ? (Float) saved
                    : v.getTranslationY();
            if (!(saved instanceof Float)) {
                v.setTag(ICON_ONLY_TAG_KEY, base);
            }
            float desired = base + offset;
            if (Math.abs(v.getTranslationY() - desired) > 0.5f) {
                v.setTranslationY(desired);
            }
        } else if (saved instanceof Float) {
            v.setTranslationY((Float) saved);
            v.setTag(ICON_ONLY_TAG_KEY, null);
        }
    }

    /** Moves glyphs and badges into the vacated title slot. */
    private static void alignIconOnlyContent(View v, boolean iconOnly,
                                             float offset) {
        String name = v.getClass().getName();
        HostApp app = LiquidGlassModule.app();
        if ((app != null && app.isTabIconClass(name))
                || name.contains("Badge")) {
            setIconOnlyTranslation(v, iconOnly, offset);
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                alignIconOnlyContent(group.getChildAt(i), iconOnly, offset);
            }
        }
    }

    /**
     * With titles removed the icon must move up about 7.5dp to recentre. Only
     * glyphs and badges move; shifting the full-size touch wrapper as well
     * would apply the offset twice on some tabs.
     */
    private static void applyQqIconOnlyAlignment(ViewGroup row,
                                                 boolean iconOnly,
                                                 float density) {
        float offset = density * 7.5f;
        for (int i = 0; i < row.getChildCount(); i++) {
            View tab = row.getChildAt(i);
            if (tab.getVisibility() != View.GONE) {
                alignIconOnlyContent(tab, iconOnly, offset);
            }
        }
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

    /**
     * Replaces equal-weight tab columns with fixed content-sized columns so
     * the pill hugs its glyphs, and returns the total row width. Each column
     * gets the configured breathing room around its content, capped to the
     * screen. In icon-only rows a fixed glyph basis keeps the pill stable
     * instead of tracking badge widths.
     */
    private static int hugContentWidth(ViewGroup tabRow, float density) {
        if (tabRow == null || tabRow.getChildCount() == 0) {
            return 0;
        }
        applyLabelSettings(tabRow);
        boolean iconOnly = GlassConfig.labelMode == 1
                || isQqIconOnlyRow(tabRow);
        applyQqIconOnlyAlignment(tabRow, iconOnly, density);

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
        // An unbounded measure wider than the laid-out column is a MATCH_PARENT
        // fallover, not a real content measurement.
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

    /* ------------------------------------------- page stretching / insets */

    private static void unclipAncestors(ViewGroup from) {
        ViewGroup v = from;
        for (int i = 0; i < 12 && v != null; i++) {
            v.setClipChildren(false);
            v.setClipToPadding(false);
            if (v.getId() == android.R.id.content) {
                return;
            }
            android.view.ViewParent p = v.getParent();
            v = p instanceof ViewGroup ? (ViewGroup) p : null;
        }
    }

    /**
     * Stretches the pager, its pages and their scrollers to the full screen
     * height so content keeps rendering under the floating pill, and gives
     * scrolling views bottom padding (with clipToPadding off) so the last row
     * can scroll clear of it.
     */
    private static void extendPagesToBottom(ViewGroup pager) {
        if (pager == null) {
            return;
        }
        ViewGroup pagerParent = pager.getParent() instanceof ViewGroup
                ? (ViewGroup) pager.getParent() : null;
        if (pagerParent != null) {
            stretchToBottom(pager, pagerParent.getHeight());
        }
        int target = pager.getHeight();
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            if (page instanceof ViewGroup) {
                stretchToBottom(page, target);
                extendOnePage((ViewGroup) page, target);
            }
        }
    }

    private static void extendOnePage(ViewGroup page, int targetHeight) {
        int pageHeight = Math.max(page.getHeight(), targetHeight);
        if (pageHeight <= 0) {
            return;
        }
        if (page.getPaddingBottom() > 0) {
            page.setClipToPadding(false);
            page.setPadding(page.getPaddingLeft(), page.getPaddingTop(),
                    page.getPaddingRight(), 0);
        }
        for (int i = 0; i < page.getChildCount(); i++) {
            View c = page.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE
                    || c.getHeight() < pageHeight / 2) {
                continue;
            }
            stretchToBottom(c, pageHeight);
            keepStretchedToBottom(c);
        }
        padScrollersBottom(page, bottomReserve(page), 0);
    }

    /** On-screen room the pill occupies at the bottom, plus a small gap. */
    private static int bottomReserve(View anchor) {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host == null || host.getHeight() <= 0) {
            return 0;
        }
        host.getLocationOnScreen(sLoc);
        float pillTop = sLoc[1] - host.getTranslationY()
                + host.getPaddingTop();
        View root = host.getRootView();
        if (root == null || root.getHeight() <= 0) {
            return 0;
        }
        root.getLocationOnScreen(sLoc);
        float density = anchor.getResources().getDisplayMetrics().density;
        float reserve = (sLoc[1] + root.getHeight()) - pillTop
                + LAST_ROW_GAP_DP * density;
        return reserve > 0f ? Math.round(reserve) : 0;
    }

    private static void stretchToBottom(View v, int parentHeight) {
        if (parentHeight <= 0) {
            return;
        }
        int gap = parentHeight - v.getBottom();
        if (gap <= 8) {
            return;
        }
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams mlp =
                (ViewGroup.MarginLayoutParams) lp;
        boolean changed = false;
        if (mlp.bottomMargin != 0) {
            mlp.bottomMargin = 0;
            changed = true;
        }
        if (mlp.height >= 0) {
            mlp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            changed = true;
        }
        if (changed) {
            v.setLayoutParams(mlp);
        }
    }

    /** Re-stretches a child when the host page restores its docked size. */
    private static void keepStretchedToBottom(View v) {
        if (Boolean.TRUE.equals(v.getTag(EXTEND_TAG_KEY))) {
            return;
        }
        v.setTag(EXTEND_TAG_KEY, Boolean.TRUE);
        v.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or2, ob) -> {
                    ViewGroup parent = view.getParent() instanceof ViewGroup
                            ? (ViewGroup) view.getParent() : null;
                    if (parent == null) {
                        return;
                    }
                    dropParentBottomReserve(parent, view);
                    ViewGroup grand = parent.getParent() instanceof ViewGroup
                            ? (ViewGroup) parent.getParent() : null;
                    if (grand != null) {
                        stretchToBottom(parent, grand.getHeight());
                    }
                    stretchToBottom(view, Math.max(parent.getHeight(),
                            grand == null ? 0 : grand.getHeight()));
                });
    }

    /** Drops a docked-bar reserve held as padding on the scroller's parent. */
    private static void dropParentBottomReserve(ViewGroup parent, View child) {
        int gap = parent.getHeight() - child.getBottom();
        int reserve = parent.getPaddingBottom();
        ViewGroup pager = sPagerRef.get();
        int pageHeight = pager == null ? parent.getHeight()
                : pager.getHeight();
        if (child.getTop() > 8 || gap <= 8 || reserve <= 0
                || Math.abs(parent.getHeight() - pageHeight) > 8
                || (sBarHeight > 0 && Math.abs(gap - sBarHeight) > 8)
                || Math.abs(reserve - gap) > 8) {
            return;
        }
        int remaining = Math.max(0, reserve - gap);
        parent.setClipToPadding(false);
        parent.setPadding(parent.getPaddingLeft(), parent.getPaddingTop(),
                parent.getPaddingRight(), remaining);
    }

    private static void padScrollersBottom(ViewGroup root, int pad,
                                           int depth) {
        if (depth > 12) {
            return;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            // The ViewPager2's own RecyclerView only hosts pages horizontally;
            // pass through it to the real vertical scroller inside.
            if (isViewPagerRecycler(c)) {
                padScrollersBottom((ViewGroup) c, pad, depth + 1);
            } else if (isScroller(c)) {
                ViewGroup sv = (ViewGroup) c;
                int parentHeight = root.getHeight();
                if (c.getTop() <= 8 && parentHeight - c.getBottom() > 8) {
                    dropParentBottomReserve(root, c);
                    stretchToBottom(c, parentHeight);
                    keepStretchedToBottom(c);
                }
                if (sv.getClipToPadding()) {
                    sv.setClipToPadding(false);
                }
                if (sv.getPaddingBottom() != pad) {
                    sv.setPadding(sv.getPaddingLeft(), sv.getPaddingTop(),
                            sv.getPaddingRight(), pad);
                }
            } else if (c instanceof ViewGroup) {
                padScrollersBottom((ViewGroup) c, pad, depth + 1);
            }
        }
    }

    private static boolean isViewPagerRecycler(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            if ("androidx.viewpager2.widget.ViewPager2$RecyclerViewImpl"
                    .equals(k.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isScroller(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        if (v instanceof android.widget.ScrollView
                || v instanceof android.widget.AbsListView) {
            return true;
        }
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            String n = k.getName();
            if ("androidx.recyclerview.widget.RecyclerView".equals(n)
                    || "androidx.core.widget.NestedScrollView".equals(n)) {
                return true;
            }
        }
        return false;
    }

    /* --------------------------------------------- bar follow / nav bar */

    /** Extra travel needed for the pill to clear the screen when the host
     *  slides its own bar away, as a fraction of the bar's travel. */
    private static float hideShortfall(LiquidGlassHostLayout host,
                                       View tabView) {
        float travel = tabView.getHeight();
        if (travel <= 0f) {
            return 0f;
        }
        host.getLocationOnScreen(sLoc);
        float pillTop = sLoc[1] - host.getTranslationY()
                + host.getPaddingTop();
        View rootView = host.getRootView();
        rootView.getLocationOnScreen(sLoc);
        float need = sLoc[1] + rootView.getHeight() - pillTop;
        return Math.max(0f, need / travel - 1f);
    }

    /**
     * Mirrors the bar's own slide/fade onto the pill layers so they travel as
     * one, and re-strips a background a skin refresh may have restored.
     */
    private static void followBarOffset(LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (tabView == null) {
            return;
        }
        if (tabView.getBackground() != null) {
            stripSolidBackgrounds(tabView);
        }
        float ty = tabView.getTranslationY();
        float alpha = tabView.getAlpha();
        boolean gone = tabView.getVisibility() != View.VISIBLE;

        host.setTranslationY(ty == 0f ? 0f : hideShortfall(host, tabView) * ty);

        View glass = sGlassRef.get();
        if (glass != null) {
            if (glass.getTranslationY() != ty) {
                glass.setTranslationY(ty);
            }
            glass.setAlpha(gone ? 0f : alpha);
        }
        View droplet = sDropletRef.get();
        if (droplet != null) {
            droplet.setTranslationY(sDropletBaseY + ty);
            droplet.setAlpha(gone ? 0f : alpha);
        }
        host.setShadowOffsetY(gone ? Float.MAX_VALUE : ty, alpha);
    }

    private static void holdHidden(View v, boolean report) {
        if (v == null || v.getVisibility() == View.GONE) {
            return;
        }
        v.setVisibility(View.GONE);
        if (report && !sBlurRelit) {
            sBlurRelit = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "host re-lit its blur layer; held hidden");
        }
    }

    /** Re-hides chrome a skin refresh or bar rebuild can bring back. */
    private static void holdOwnBarChromeHidden(LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        android.view.ViewParent rawParent = host.getParent();
        HostApp app = LiquidGlassModule.app();
        if (rawParent instanceof ViewGroup && app != null
                && app.hiddenSiblings.length > 0) {
            ViewGroup parent = (ViewGroup) rawParent;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View c = parent.getChildAt(i);
                if (c != tabView
                        && app.isHiddenSibling(c.getClass().getName())) {
                    sBlurLayerRef = new WeakReference<>(c);
                    holdHidden(c, true);
                }
            }
        } else {
            holdHidden(sBlurLayerRef.get(), true);
        }
        holdHidden(sHairlineRef.get(), false);
        holdSubtreeLinesHidden();
        if (tabView != null) {
            ViewGroup row = TabBarBridge.findTabRow(
                    tabView instanceof ViewGroup ? (ViewGroup) tabView : null);
            suppressTabSeparators(tabView, row);
        }
    }

    private static View navBarBackground(View decor) {
        View v = sNavBgRef.get();
        if (v != null && v.getParent() != null
                && v.getRootView() == decor.getRootView()) {
            return v;
        }
        if (sNavBgId == -1) {
            sNavBgId = decor.getResources().getIdentifier(
                    "navigationBarBackground", "id", "android");
        }
        if (sNavBgId == 0) {
            return null;
        }
        v = decor.findViewById(sNavBgId);
        sNavBgRef = new WeakReference<>(v);
        return v;
    }

    /**
     * Keeps the system navigation bar transparent over the edge-to-edge
     * content: hides its framework backdrop view (setting the colour does not
     * hold on some OEM skins) and preserves the layout flag that lets the
     * window reach under the gesture bar.
     */
    private static void keepNavBarClear() {
        Activity a = sActivityRef.get();
        if (a == null) {
            return;
        }
        View decor = a.getWindow().getDecorView();
        View navBg = navBarBackground(decor);
        if (navBg != null && navBg.getVisibility() != View.GONE) {
            navBg.setVisibility(View.GONE);
        }
        int vis = decor.getSystemUiVisibility();
        if ((vis & View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) == 0) {
            decor.setSystemUiVisibility(
                    vis | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private static void watchBottomInset(LiquidGlassHostLayout host,
                                         ViewGroup backdrop) {
        backdrop.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or2, ob) -> {
                    extendPagesToBottom(backdrop);
                    keepNavBarClear();
                });
        host.getViewTreeObserver().addOnWindowFocusChangeListener(
                hasFocus -> {
                    if (hasFocus) {
                        reassertBottom();
                    }
                });
    }

    /** Re-stretches pages on resume of an already-installed window. */
    private static void reassertBottom() {
        keepNavBarClear();
        ViewGroup pager = sPagerRef.get();
        if (pager == null) {
            return;
        }
        pager.post(() -> extendPagesToBottom(pager));
        pager.postDelayed(() -> extendPagesToBottom(pager), 400L);
    }

    private static int navInset(View anchor) {
        try {
            WindowInsets insets = anchor.getRootWindowInsets();
            return insets == null ? 0 : navigationInset(insets);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Caches the last non-zero inset; theme rebuilds dispatch a transient 0. */
    private static int rememberNavigationInset(View anchor) {
        int inset = navInset(anchor);
        if (inset > 0) {
            sNavigationInset = inset;
        }
        return inset > 0 ? inset : sNavigationInset;
    }

    private static int navigationInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    /** Keeps the pill anchored to the bottom as system insets change. */
    private static void syncHostBottomInset(LiquidGlassHostLayout host,
                                            int inset) {
        ViewGroup.LayoutParams raw = host.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        float density = host.getResources().getDisplayMetrics().density;
        int desired = Math.round(density * GlassConfig.barOffsetDp)
                - host.shadowPad() + Math.max(0, inset);
        if (lp.bottomMargin == desired) {
            return;
        }
        lp.bottomMargin = desired;
        host.setLayoutParams(lp);
        LiquidGlassModule.log(android.util.Log.INFO,
                "pill bottom anchor refreshed: inset=" + inset
                        + " margin=" + desired);
    }

    /* ------------------------------------------------ structure watcher */

    /**
     * Fingerprint of row topology and the equal-width layout bit, deliberately
     * excluding measured geometry. A rebuilt or re-weighted row must trigger a
     * re-hug of the existing pill.
     */
    private static int tabStructureSignature(ViewGroup tabRow) {
        if (tabRow == null) {
            return 0;
        }
        int signature = System.identityHashCode(tabRow);
        signature = signature * 31 + tabRow.getVisibility();
        signature = signature * 31 + tabRow.getChildCount();
        signature = signature * 31 + (isQqIconOnlyRow(tabRow) ? 1 : 0);
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            signature = signature * 31 + System.identityHashCode(tab);
            signature = signature * 31 + tab.getVisibility();
            ViewGroup.LayoutParams lp = tab.getLayoutParams();
            signature = signature * 31 + System.identityHashCode(lp);
            boolean equalWeight = lp != null && lp.width == 0;
            if (lp instanceof LinearLayout.LayoutParams) {
                equalWeight |=
                        ((LinearLayout.LayoutParams) lp).weight != 0f;
            }
            signature = signature * 31 + (equalWeight ? 1 : 0);
            if (tab instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) tab;
                signature = signature * 31 + group.getChildCount();
                for (int j = 0; j < group.getChildCount(); j++) {
                    View child = group.getChildAt(j);
                    signature = signature * 31
                            + System.identityHashCode(child);
                    signature = signature * 31 + child.getVisibility();
                }
            }
        }
        return signature;
    }

    /** Posts a re-hug when the row changed; true tells the frame to wait. */
    private static boolean scheduleTabStructureRefreshIfNeeded(
            LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (!(tabView instanceof ViewGroup)) {
            return false;
        }
        ViewGroup current = TabBarBridge.findTabRow((ViewGroup) tabView);
        if (current == null || current.getVisibility() != View.VISIBLE
                || TabBarBridge.tabCount(current) == 0) {
            return sTabRowRef.get() != null;
        }
        if (sTabStructureRefreshPosted) {
            return true;
        }
        int signature = tabStructureSignature(current);
        if (current == sTabRowRef.get()
                && signature == sTabStructureSignature) {
            return false;
        }
        sTabStructureRefreshPosted = true;
        host.post(() -> refreshTabStructure(host));
        return true;
    }

    /** Re-hugs and rebinds the bar after its tab topology changed at runtime. */
    private static void refreshTabStructure(LiquidGlassHostLayout host) {
        try {
            if (host != sHostRef.get() || host.getParent() == null) {
                return;
            }
            View tabView = sTabViewRef.get();
            if (!(tabView instanceof ViewGroup)) {
                return;
            }
            ViewGroup tabRow = TabBarBridge.findTabRow((ViewGroup) tabView);
            if (tabRow == null || tabRow.getVisibility() != View.VISIBLE
                    || TabBarBridge.tabCount(tabRow) == 0) {
                return;
            }

            stripSolidBackgrounds(tabView);
            suppressTabSeparators(tabView, tabRow);
            dropNavReserve(tabView);
            ViewGroup.LayoutParams tabLp = tabView.getLayoutParams();
            if (tabLp != null && sBarHeight > 0
                    && tabLp.height != sBarHeight) {
                tabLp.height = sBarHeight;
                tabView.setLayoutParams(tabLp);
            }
            float density = host.getResources().getDisplayMetrics().density;
            int barWidth = hugContentWidth(tabRow, density);
            if (barWidth <= 0) {
                return;
            }
            ViewGroup.LayoutParams lp = host.getLayoutParams();
            int desired = barWidth + host.shadowPad() * 2;
            if (lp != null && lp.width != desired) {
                lp.width = desired;
                host.setLayoutParams(lp);
            }

            sTabRowRef = new WeakReference<>(tabRow);
            sTabStructureSignature = tabStructureSignature(tabRow);
            View droplet = sDropletRef.get();
            if (droplet instanceof DropletPanel) {
                ((DropletPanel) droplet).setTabRow(tabRow);
            }
            if (sDrag != null) {
                sDrag.setTabRow(tabRow);
            }

            sLastIndex = -1;
            tabRow.requestLayout();
            tabView.requestLayout();
            host.requestLayout();
            LiquidGlassModule.log(android.util.Log.INFO,
                    "tab structure rebound: children="
                            + tabRow.getChildCount()
                            + " hostWidth=" + (lp == null ? 0 : lp.width));
        } catch (Throwable t) {
            LiquidGlassModule.logErr("tab structure refresh failed", t);
        } finally {
            sTabStructureRefreshPosted = false;
        }
    }

    /** Re-drops nav padding/height a skin refresh restored without rebuilding. */
    private static boolean restoreBarContentHeight(LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (tabView == null) {
            return false;
        }
        boolean changed = dropNavReserve(tabView) > 0;
        ViewGroup.LayoutParams lp = tabView.getLayoutParams();
        if (lp != null && sBarHeight > 0 && lp.height != sBarHeight) {
            lp.height = sBarHeight;
            tabView.setLayoutParams(lp);
            changed = true;
        }
        if (changed) {
            tabView.requestLayout();
            host.requestLayout();
        }
        return changed;
    }

    /**
     * Per-frame watcher: holds chrome hidden and nav clear, restores bar
     * geometry, follows the host bar's slide, re-hugs a changed row, and drives
     * the droplet when the selected tab changes.
     */
    private static void installSelectionWatcher(LiquidGlassHostLayout host) {
        host.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (host != sHostRef.get() || !host.isAttachedToWindow()) {
                return true;
            }
            try {
                keepNavBarClear();
                holdOwnBarChromeHidden(host);
            } catch (Throwable t) {
                if (!sKeepFailed) {
                    sKeepFailed = true;
                    LiquidGlassModule.logErr("chrome hold failed", t);
                }
            }
            try {
                if (restoreBarContentHeight(host)) {
                    return true;
                }
                followBarOffset(host);
                if (scheduleTabStructureRefreshIfNeeded(host)) {
                    return true;
                }
                ViewGroup tabRow = sTabRowRef.get();
                int selected = TabBarBridge.selectedIndex(tabRow);
                if (selected >= 0 && selected != sLastIndex) {
                    boolean first = sLastIndex < 0;
                    sLastIndex = selected;
                    syncDropletSize(selected);
                    if (sDrag != null) {
                        sDrag.animateToIndex(selected, first);
                    }
                    ViewGroup pager = sPagerRef.get();
                    if (pager != null) {
                        pager.post(() -> extendPagesToBottom(pager));
                    }
                }
            } catch (Throwable t) {
                LiquidGlassModule.logErr("selection watcher frame failed", t);
            }
            return true;
        });
    }
}
