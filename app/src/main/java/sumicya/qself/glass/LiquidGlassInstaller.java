/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;

import sumicya.qself.diagnostics.FeatureJournal;

/**
 * Moves QQ's bottom tab bar into the floating liquid-glass pill.
 *
 * <p>The content area already lays out full height with the bar floating over
 * it inside a FrameLayout, so only the bar itself is reparented into
 * {@link LiquidGlassHostLayout}; the solid surface behind it is stripped, the
 * host's own blur layer and separator lines are hidden, equal-weight tab
 * columns become content-sized, and the pager's pages are stretched under the
 * now-floating bar. Bar discovery and hooks live in {@link TabBarBridge}.
 *
 * <p>The mechanics are split into focused collaborators:
 * <ul>
 *   <li>{@link GlassBarChrome} — stock chrome stripping and re-hiding;</li>
 *   <li>{@link GlassLabelStyle} — label visibility, tone and icon-only
 *       centreing;</li>
 *   <li>{@link GlassHug} — content-hugging widths and rim trim;</li>
 *   <li>{@link GlassPageExtender} — page stretching, scroller padding and
 *       the clear navigation bar.</li>
 * </ul>
 */
public final class LiquidGlassInstaller {

    private static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_DELAY_MS = 250L;
    private static final long REVEAL_TIMEOUT_MS = 8000L;

    static final GlassBarChrome CHROME = new GlassBarChrome();
    static final GlassLabelStyle LABELS = new GlassLabelStyle();
    static final GlassHug HUG = new GlassHug(LABELS);
    static final GlassPageExtender PAGES = new GlassPageExtender();

    private static final int[] sLoc = new int[2];
    private static boolean sKeepFailed;

    private static WeakReference<Activity> sActivityRef = new WeakReference<>(null);
    private static WeakReference<LiquidGlassHostLayout> sHostRef =
            new WeakReference<>(null);
    private static WeakReference<View> sTabViewRef = new WeakReference<>(null);
    private static WeakReference<GlassSurface> sGlassRef =
            new WeakReference<>(null);
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

    private static float sDropletBaseY;

    private LiquidGlassInstaller() {
    }

    /* --------------------------------------------------------- scheduling */

    public static void scheduleInstall(Activity activity) {
        sActivityRef = new WeakReference<>(activity);
        PAGES.setActivity(activity);
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
                                    CHROME.showOwnBlurLayers(
                                            (ViewGroup) bar.getParent());
                                }
                                LiquidGlassModule.log(android.util.Log.WARN,
                                        "pill never installed, stock bar restored");
                                FeatureJournal.record("GLASS", "install.fail",
                                        "reason=timeout stock-bar-restored");
                            }
                            decor.getViewTreeObserver()
                                    .removeOnPreDrawListener(this);
                        } else if (bar != null && bar.getAlpha() != 0f) {
                            bar.setAlpha(0f);
                            if (bar.getParent() instanceof ViewGroup) {
                                CHROME.hideOwnBlurLayers(
                                        (ViewGroup) bar.getParent(), bar);
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
                LABELS.apply(sTabRowRef.get());
                live.refreshConfiguration();
                decor.post(() -> refreshTabStructure(live));
                PAGES.reassertBottom();
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
                    FeatureJournal.record("GLASS", "install.fail",
                            "reason=no-tab-bar attempts=" + MAX_ATTEMPTS);
                }
                return;
            }
            install(tabView);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("install failed", t);
            FeatureJournal.record("GLASS", "install.fail",
                    "reason=exception " + t.getClass().getSimpleName());
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
        sLastIndex = -1;
        sDropletBaseY = 0f;
        CHROME.reset();
        PAGES.reset();
    }

    static ViewGroup currentPager() {
        return sPagerRef.get();
    }

    /* ------------------------------------------------------------ install */

    private static void install(ViewGroup tabView) {
        ViewGroup parent = tabView.getParent() instanceof ViewGroup
                ? (ViewGroup) tabView.getParent() : null;
        if (parent == null || parent instanceof LiquidGlassHostLayout) {
            FeatureJournal.record("GLASS", "install.fail",
                    "reason=no-parent already-hosted="
                            + (parent instanceof LiquidGlassHostLayout));
            return;
        }
        ViewGroup backdrop = CHROME.findBackdrop(parent, tabView);
        if (backdrop == null) {
            LiquidGlassModule.log(android.util.Log.WARN,
                    "no backdrop sibling found; glass would refract nothing");
            FeatureJournal.record("GLASS", "install.fail",
                    "reason=no-backdrop");
            return;
        }
        int index = parent.indexOfChild(tabView);
        if (index < 0) {
            FeatureJournal.record("GLASS", "install.fail",
                    "reason=bar-not-child");
            return;
        }
        ViewGroup.LayoutParams originalLp = tabView.getLayoutParams();

        Context ctx = tabView.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int bottomOffset = Math.round(density * GlassConfig.barOffsetDp);
        int navigationInset = PAGES.rememberNavigationInset(parent);

        LiquidGlassHostLayout host = new LiquidGlassHostLayout(
                ctx, backdrop, tabView);

        int navReserve = tabView.getPaddingBottom();
        ViewGroup tabRow = TabBarBridge.findTabRow(tabView);
        int barHeight = CHROME.contentBarHeight(tabRow,
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
            FeatureJournal.record("GLASS", "install.fail", "reason=reparent");
            return;
        }

        try {
            CHROME.dropNavReserve(tabView);
            int barWidth = HUG.hugContentWidth(tabRow, density);
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
            FeatureJournal.record("GLASS", "install.fail", "reason=sizing");
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
            CHROME.hideOwnBlurLayers(parent, tabView);
            CHROME.hideBarHairline(parent, tabView);
            CHROME.stripSolidBackgrounds(tabView);
            CHROME.suppressTabSeparators(tabView, tabRow);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("partial stock chrome removal", t);
        }

        // QQ lays its decor out edge to edge already, so the nav inset counts
        // toward the anchor even without growing the window ourselves.
        hostLp.bottomMargin = bottomOffset - shadowPad + navigationInset;
        host.setLayoutParams(hostLp);
        host.post(() -> syncHostBottomInset(host, navigationInset));

        GlassPageExtender.unclipAncestors(parent);
        PAGES.bind(host, backdrop, barHeight);
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
                        GlassSurface glass = sGlassRef.get();
                        if (glass != null) {
                            // Pixel-level blur proof, fired once frames settle.
                            GlassBlurProbe.verify(glass);
                        }
                        PAGES.extendPagesToBottom(backdrop);
                        host.postDelayed(
                                () -> PAGES.extendPagesToBottom(backdrop),
                                500L);
                        LiquidGlassModule.log(android.util.Log.INFO,
                                "pill installed: hostW=" + host.getWidth()
                                        + " hostH=" + host.getHeight()
                                        + " barH=" + tabView.getHeight());
                        FeatureJournal.record("GLASS", "install.ok",
                                "hostW=" + host.getWidth()
                                        + " hostH=" + host.getHeight()
                                        + " barH=" + tabView.getHeight()
                                        + " navInset=" + navigationInset
                                        + " glassPath=" + (glass == null ? "none"
                                                : glass.renderPathName())
                                        + " sdk=" + Build.VERSION.SDK_INT);
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
            GlassLabelStyle.applyQqIconOnlyAlignment(mRow, false, density);
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

            final GlassSurface glass =
                    new GlassSurface(ctx, backdrop, density, night);
            host.addView(glass, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            final DropletPanel droplet = new DropletPanel(
                    ctx, backdrop, sTabRowRef.get(), density, night);
            droplet.setVisibility(View.INVISIBLE);
            // The droplet must sit BELOW the host's own tab row: its surface is an
            // opaque backdrop capture, and on top of the real tabs it would hide
            // the selected tab's icon and label (seen on device as a blank disc).
            host.addView(droplet, 1, new FrameLayout.LayoutParams(0, 0,
                    android.view.Gravity.TOP | android.view.Gravity.START));
            droplet.setPill(glass);
            sDropletRef = new WeakReference<>(droplet);
            // Probe: on-device evidence that the droplet sits BELOW the tab row
            // (child order glass -> droplet -> tabBar). Visible in the module's
            // diagnostics journal, copyable from Settings -> 功能开关与错误记录.
            FeatureJournal.record("GLASS", "droplet.install",
                    "children=" + host.getChildCount()
                            + " dropletIndex=" + host.indexOfChild(droplet)
                            + " glassIndex=" + host.indexOfChild(glass)
                            + " sdk=" + Build.VERSION.SDK_INT);
            // The droplet scales past the pill while held; both clipping
            // defaults would shear the overflow off.
            host.setClipChildren(false);
            host.setClipToPadding(false);
            sGlassRef = new WeakReference<>(glass);

            host.setGlassTuner(new LiquidGlassHostLayout.GlassTuner() {
                @Override
                public void onSize(int w, int h, float cornerRadius) {
                    glass.invalidate();
                    HUG.applyMeasuredRimTrim(host, glass, sTabRowRef.get());
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
                    "renderer attached, path=" + glass.renderPathName()
                            + " drag=" + (tabRow != null));
        } catch (Throwable t) {
            LiquidGlassModule.logErr("glass renderer unavailable", t);
            FeatureJournal.record("GLASS", "install.fail",
                    "reason=renderer " + t.getClass().getSimpleName());
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
            // Upstream droplet: one full tab slot wide, 4dp padding inside a
            // 64dp row (the tinted glyph capture stays 1:1, so width matters).
            int inset = Math.round(density * 4f);
            int w = tab.getWidth();
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
            boolean firstShow = droplet.getVisibility() != View.VISIBLE;
            droplet.setVisibility(View.VISIBLE);
            if (firstShow) {
                // Probe: droplet live above the bar, sized to one tab slot.
                FeatureJournal.record("GLASS", "droplet.show",
                        "w=" + w + " h=" + h + " y=" + sDropletBaseY
                                + " zIndex=" + ((ViewGroup) droplet.getParent())
                                        .indexOfChild(droplet));
            }
        } catch (Throwable t) {
            LiquidGlassModule.logErr("droplet sizing failed", t);
        }
    }

    /* ----------------------------------------------------- bar following */

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
            CHROME.stripSolidBackgrounds(tabView);
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

    /* ----------------------------------------------------- inset watcher */

    private static void watchBottomInset(LiquidGlassHostLayout host,
                                         ViewGroup backdrop) {
        backdrop.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or2, ob) -> {
                    PAGES.extendPagesToBottom(backdrop);
                    PAGES.keepNavBarClear();
                });
        host.getViewTreeObserver().addOnWindowFocusChangeListener(
                hasFocus -> {
                    if (hasFocus) {
                        PAGES.reassertBottom();
                    }
                });
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
        signature = signature * 31
                + (GlassLabelStyle.isQqIconOnlyRow(tabRow) ? 1 : 0);
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

            CHROME.stripSolidBackgrounds(tabView);
            CHROME.suppressTabSeparators(tabView, tabRow);
            CHROME.dropNavReserve(tabView);
            ViewGroup.LayoutParams tabLp = tabView.getLayoutParams();
            if (tabLp != null && sBarHeight > 0
                    && tabLp.height != sBarHeight) {
                tabLp.height = sBarHeight;
                tabView.setLayoutParams(tabLp);
            }
            float density = host.getResources().getDisplayMetrics().density;
            int barWidth = HUG.hugContentWidth(tabRow, density);
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
        boolean changed = CHROME.dropNavReserve(tabView) > 0;
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
                PAGES.keepNavBarClear();
                CHROME.holdOwnBarChromeHidden(host, sTabViewRef.get());
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
                        pager.post(() -> PAGES.extendPagesToBottom(pager));
                    }
                }
            } catch (Throwable t) {
                LiquidGlassModule.logErr("selection watcher frame failed", t);
            }
            return true;
        });
    }
}
