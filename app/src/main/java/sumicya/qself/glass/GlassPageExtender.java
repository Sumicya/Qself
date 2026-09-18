/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;

/**
 * Keeps content flowing under the floating pill: the pager, its pages and
 * their scrollers stretch to the full screen height, scrollers gain bottom
 * padding (clipToPadding off) so the last row clears the pill, and the
 * system navigation bar stays transparent over the edge-to-edge window.
 */
final class GlassPageExtender {

    /** Scroll clearance below the last row once it clears the pill. */
    private static final float LAST_ROW_GAP_DP = 8f;
    private static final int EXTEND_TAG_KEY = 0x7F5A0001;

    private final int[] loc = new int[2];

    private WeakReference<Activity> activityRef = new WeakReference<>(null);
    private WeakReference<LiquidGlassHostLayout> hostRef =
            new WeakReference<>(null);
    private WeakReference<ViewGroup> pagerRef = new WeakReference<>(null);
    private int barHeight;

    private WeakReference<View> navBgRef = new WeakReference<>(null);
    private int navBgId = -1;
    private int navigationInset;

    void setActivity(Activity activity) {
        activityRef = new WeakReference<>(activity);
    }

    void bind(LiquidGlassHostLayout host, ViewGroup pager, int barHeight) {
        hostRef = new WeakReference<>(host);
        pagerRef = new WeakReference<>(pager);
        this.barHeight = barHeight;
    }

    /** Clears per-window memory when the installer resets. */
    void reset() {
        activityRef = new WeakReference<>(null);
        hostRef = new WeakReference<>(null);
        pagerRef = new WeakReference<>(null);
        barHeight = 0;
        navBgRef = new WeakReference<>(null);
    }

    /* ---------------------------------------------------------- stretching */

    static void unclipAncestors(ViewGroup from) {
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
     * scrolling views bottom padding (with clipToPadding off) so the last
     * row can scroll clear of it.
     */
    void extendPagesToBottom(ViewGroup pager) {
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

    private void extendOnePage(ViewGroup page, int targetHeight) {
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
    private int bottomReserve(View anchor) {
        LiquidGlassHostLayout host = hostRef.get();
        if (host == null || host.getHeight() <= 0) {
            return 0;
        }
        host.getLocationOnScreen(loc);
        float pillTop = loc[1] - host.getTranslationY()
                + host.getPaddingTop();
        View root = host.getRootView();
        if (root == null || root.getHeight() <= 0) {
            return 0;
        }
        root.getLocationOnScreen(loc);
        float density = anchor.getResources().getDisplayMetrics().density;
        float reserve = (loc[1] + root.getHeight()) - pillTop
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
    private void keepStretchedToBottom(View v) {
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
    private void dropParentBottomReserve(ViewGroup parent, View child) {
        int gap = parent.getHeight() - child.getBottom();
        int reserve = parent.getPaddingBottom();
        ViewGroup pager = pagerRef.get();
        int pageHeight = pager == null ? parent.getHeight()
                : pager.getHeight();
        if (child.getTop() > 8 || gap <= 8 || reserve <= 0
                || Math.abs(parent.getHeight() - pageHeight) > 8
                || (barHeight > 0 && Math.abs(gap - barHeight) > 8)
                || Math.abs(reserve - gap) > 8) {
            return;
        }
        int remaining = Math.max(0, reserve - gap);
        parent.setClipToPadding(false);
        parent.setPadding(parent.getPaddingLeft(), parent.getPaddingTop(),
                parent.getPaddingRight(), remaining);
    }

    private void padScrollersBottom(ViewGroup root, int pad, int depth) {
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

    /* ----------------------------------------------------------- nav bar */

    private View navBarBackground(View decor) {
        View v = navBgRef.get();
        if (v != null && v.getParent() != null
                && v.getRootView() == decor.getRootView()) {
            return v;
        }
        if (navBgId == -1) {
            navBgId = decor.getResources().getIdentifier(
                    "navigationBarBackground", "id", "android");
        }
        if (navBgId == 0) {
            return null;
        }
        v = decor.findViewById(navBgId);
        navBgRef = new WeakReference<>(v);
        return v;
    }

    /**
     * Keeps the system navigation bar transparent over the edge-to-edge
     * content: hides its framework backdrop view (setting the colour does not
     * hold on some OEM skins) and preserves the layout flag that lets the
     * window reach under the gesture bar.
     */
    void keepNavBarClear() {
        Activity a = activityRef.get();
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

    /** Re-stretches pages on resume of an already-installed window. */
    void reassertBottom() {
        keepNavBarClear();
        ViewGroup pager = pagerRef.get();
        if (pager == null) {
            return;
        }
        pager.post(() -> extendPagesToBottom(pager));
        pager.postDelayed(() -> extendPagesToBottom(pager), 400L);
    }

    /* ------------------------------------------------------------- insets */

    static int navInset(View anchor) {
        try {
            WindowInsets insets = anchor.getRootWindowInsets();
            return insets == null ? 0 : navigationInset(insets);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Caches the last non-zero inset; theme rebuilds dispatch a transient 0. */
    int rememberNavigationInset(View anchor) {
        int inset = navInset(anchor);
        if (inset > 0) {
            navigationInset = inset;
        }
        return inset > 0 ? inset : navigationInset;
    }

    private static int navigationInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }
}
