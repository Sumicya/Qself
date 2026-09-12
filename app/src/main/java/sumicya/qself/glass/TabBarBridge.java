/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

/**
 * Locates and observes the host's bottom navigation bar without relying on
 * resource identifiers, which the host ships obfuscated.
 *
 * <p>The bar is found either by its known class names (see {@link HostApp}) or
 * by a strict structural scan; the tab-switch method declared on the bar class
 * is hooked once per process to drive installation and droplet motion, and the
 * selected slot is read from view state ({@link View#isSelected()}) because
 * the switch callback does not fire on ordinary taps.
 */
public final class TabBarBridge {

    private static final int MIN_TABS = 3;
    private static final int MAX_TABS = 5;
    private static final float MIN_TAB_HEIGHT_DP = 32f;

    private static volatile boolean sSwitchHooked;
    private static volatile boolean sPagerHooked;

    private TabBarBridge() {
    }

    /* --------------------------------------------------------------- hooks */

    /**
     * Hooks the declared {@code int}-argument tab switch method of every known
     * bar class. Declared methods only: hooking an inherited framework method
     * (the classic bar extends {@link android.widget.TabWidget}) would affect
     * every tab widget in the process. Absent classes are normal when the host
     * switches bar implementations via server config.
     */
    public static void install(HostApp app, ClassLoader loader) {
        if (sSwitchHooked || app == null) {
            return;
        }
        int ok = 0;
        for (String className : app.tabViewClasses) {
            Class<?> barClass;
            try {
                barClass = loader.loadClass(className);
            } catch (Throwable t) {
                LiquidGlassModule.log(android.util.Log.INFO,
                        "tab bar class not present: " + className);
                continue;
            }
            for (String methodName : app.tabSwitchMethods) {
                try {
                    Method target = barClass.getDeclaredMethod(methodName, int.class);
                    LiquidGlassModule.hookAfter(target, param -> {
                        Object self = param.thisObject;
                        Object indexArg = param.args.length > 0 ? param.args[0] : null;
                        if (self instanceof View && indexArg instanceof Integer) {
                            LiquidGlassInstaller.onTabChanged((View) self, (Integer) indexArg);
                        }
                    });
                    ok++;
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "hooked " + className + "." + methodName + "(int)");
                } catch (Throwable t) {
                    LiquidGlassModule.log(android.util.Log.WARN,
                            "could not hook " + className + "." + methodName
                                    + "(int): " + t);
                }
            }
        }
        sSwitchHooked = ok > 0;
        if (!sSwitchHooked) {
            LiquidGlassModule.log(android.util.Log.WARN,
                    "no tab switch method hooked; relying on polling");
        }
    }

    /**
     * Restores the smooth page slide on tab taps. The host's bar handler calls
     * {@code setCurrentItem(index, false)} so pages hard-cut; flipping the flag
     * to {@code true} gives the pager back its transition, which the droplet
     * animation travels alongside.
     *
     * <p>Only the refracted backdrop pager may be rewritten. The same pager
     * class is shared by carousels elsewhere in the app, and those callers ask
     * for an instant jump on purpose. The hook is installed once per class and
     * left in place.
     */
    static void tryHookPager(ViewGroup pager) {
        if (sPagerHooked || pager == null) {
            return;
        }
        try {
            Method target = null;
            for (Class<?> c = pager.getClass(); c != null && c != Object.class;
                 c = c.getSuperclass()) {
                try {
                    target = c.getDeclaredMethod(
                            "setCurrentItem", int.class, boolean.class);
                    break;
                } catch (NoSuchMethodException expected) {
                    // walk up
                }
            }
            if (target == null) {
                LiquidGlassModule.log(android.util.Log.WARN,
                        "setCurrentItem(int,boolean) missing on "
                                + pager.getClass().getName()
                                + "; page changes will hard-cut");
                return;
            }
            final Method setItem = target;
            LiquidGlassModule.hookIntercept(setItem, param -> {
                if (param.thisObject != LiquidGlassInstaller.currentPager()) {
                    return;
                }
                Object[] args = param.args;
                boolean smooth = args.length >= 2 && Boolean.TRUE.equals(args[1]);
                if (!smooth) {
                    // Re-enters this advice with smooth == true, which then
                    // falls through; the original no-animation call is dropped.
                    setItem.invoke(param.thisObject, args[0], true);
                    param.setResult(null);
                }
            });
            sPagerHooked = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "hooked " + setItem.getDeclaringClass().getName()
                            + ".setCurrentItem(int,boolean) for page slides");
        } catch (Throwable t) {
            LiquidGlassModule.log(android.util.Log.WARN,
                    "pager slide hook failed: " + t);
        }
    }

    /* ------------------------------------------------------------ locating */

    static boolean isTabView(View v) {
        HostApp app = LiquidGlassModule.app();
        return v != null && app != null
                && app.isTabViewClass(v.getClass().getName());
    }

    /** Depth-first search by known class name; identity is name based because
     * hot-patched loaders can make {@code instanceof} fail. */
    static ViewGroup findTabView(View root) {
        if (root == null) {
            return null;
        }
        if (isTabView(root)) {
            return root instanceof ViewGroup ? (ViewGroup) root : null;
        }
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup found = findTabView(group.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Finds the bar by name first, then by a deliberately strict shape scan.
     * A false negative only costs the feature; a false positive would reparent
     * an unrelated control row into a pill and break the host UI.
     */
    static ViewGroup locateTabView(View root) {
        ViewGroup named = findTabView(root);
        if (named != null) {
            return named;
        }
        ViewGroup row = findRowByShape(root);
        if (row == null) {
            return null;
        }
        ViewGroup wrapper = tightestWrapper(row);
        LiquidGlassModule.log(android.util.Log.WARN,
                "bar matched by shape only: " + wrapper.getClass().getName()
                        + " slots=" + row.getChildCount());
        return wrapper;
    }

    /**
     * The horizontal row that actually holds the tab children. Classic bars
     * are themselves horizontal layouts; material-style bars keep the tabs in
     * a nested horizontal layout (indicator view), preferring a visible one and
     * remembering a hidden candidate as a fallback for construction frames.
     */
    static ViewGroup findTabRow(ViewGroup bar) {
        if (bar == null) {
            return null;
        }
        ViewGroup hiddenCandidate = null;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (isHorizontalRow(child) && ((ViewGroup) child).getChildCount() >= 2) {
                if (child.getVisibility() == View.VISIBLE) {
                    return (ViewGroup) child;
                }
                if (hiddenCandidate == null) {
                    hiddenCandidate = (ViewGroup) child;
                }
            }
        }
        if (isHorizontalRow(bar) && bar.getChildCount() >= 2) {
            return bar;
        }
        if (looksLikeTabRow(bar)) {
            return bar;
        }
        return hiddenCandidate;
    }

    private static boolean isHorizontalRow(View v) {
        return v instanceof LinearLayout
                && ((LinearLayout) v).getOrientation() == LinearLayout.HORIZONTAL;
    }

    /**
     * Structural bar test: visible, wide, bottom-anchored, three to five
     * equally wide ordered children, with either index tags or exactly one
     * selected child. Geometry alone would also match toolbars.
     */
    private static boolean looksLikeTabRow(View v) {
        if (!(v instanceof ViewGroup) || v.getVisibility() != View.VISIBLE
                || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        ViewGroup group = (ViewGroup) v;
        View first = null;
        int previousRight = Integer.MIN_VALUE;
        int slots = 0;
        int selected = 0;
        boolean indexTagged = true;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (first == null) {
                first = child;
            } else if (Math.abs(child.getWidth() - first.getWidth()) > 2) {
                return false;
            }
            if (child.getLeft() < previousRight) {
                return false;
            }
            previousRight = child.getRight();
            Object tag = child.getTag();
            if (!(tag instanceof Integer) || (Integer) tag != i) {
                indexTagged = false;
            }
            if (child.isSelected()) {
                selected++;
            }
            slots++;
        }
        if (first == null || slots < MIN_TABS || slots > MAX_TABS) {
            return false;
        }
        View rootView = v.getRootView();
        if (rootView == null || rootView.getWidth() <= 0
                || rootView.getHeight() <= 0) {
            return false;
        }
        if (v.getWidth() < rootView.getWidth() * 0.6f) {
            return false;
        }
        float density = v.getResources().getDisplayMetrics().density;
        if (first.getHeight() < MIN_TAB_HEIGHT_DP * density) {
            return false;
        }
        int[] here = new int[2];
        int[] rootPos = new int[2];
        v.getLocationOnScreen(here);
        rootView.getLocationOnScreen(rootPos);
        float gapBelow = (rootPos[1] + rootView.getHeight())
                - (here[1] + v.getHeight());
        if (gapBelow > rootView.getHeight() * 0.25f) {
            return false;
        }
        return indexTagged || selected == 1;
    }

    /** Lowest (nearest screen bottom) group that passes the shape test. */
    private static ViewGroup findRowByShape(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE
                || root instanceof LiquidGlassHostLayout) {
            return null;
        }
        ViewGroup best = looksLikeTabRow(root) ? (ViewGroup) root : null;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                ViewGroup candidate = findRowByShape(group.getChildAt(i));
                if (candidate != null && (best == null
                        || lowerOnScreen(candidate, best))) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static boolean lowerOnScreen(View a, View b) {
        int[] pa = new int[2];
        int[] pb = new int[2];
        a.getLocationOnScreen(pa);
        b.getLocationOnScreen(pb);
        return pa[1] + a.getHeight() > pb[1] + b.getHeight();
    }

    /**
     * Smallest ancestor still wrapping the row; the first ancestor markedly
     * taller than the row is page content rather than bar chrome.
     */
    private static ViewGroup tightestWrapper(ViewGroup row) {
        ViewGroup best = row;
        android.view.ViewParent parent = row.getParent();
        while (parent instanceof ViewGroup
                && !(parent instanceof LiquidGlassHostLayout)) {
            ViewGroup group = (ViewGroup) parent;
            if (group.getHeight() > row.getHeight() * 1.6f) {
                break;
            }
            best = group;
            parent = group.getParent();
        }
        return best;
    }

    /* ------------------------------------------------------------- slots */

    /** Count of children that still occupy layout space. */
    static int tabCount(ViewGroup row) {
        if (row == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i).getVisibility() != View.GONE) {
                count++;
            }
        }
        return count;
    }

    /** The tab at a visible-slot index; GONE placeholders are skipped. */
    static View tabAt(ViewGroup row, int slot) {
        if (row == null || slot < 0) {
            return null;
        }
        int seen = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            if (seen == slot) {
                return child;
            }
            seen++;
        }
        return null;
    }

    /**
     * Maps a logical/app index to a visible layout slot. A tagged index wins
     * when present; otherwise the raw child position is used.
     */
    static int slotForIndex(ViewGroup row, int index) {
        if (row == null || index < 0) {
            return -1;
        }
        int slot = 0;
        int rawSlot = -1;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            Object tag = child.getTag();
            if (tag instanceof Integer && (Integer) tag == index) {
                return slot;
            }
            if (i == index) {
                rawSlot = slot;
            }
            slot++;
        }
        return rawSlot;
    }

    /** Visible-slot index of the selected tab, read from view state. */
    static int selectedIndex(ViewGroup row) {
        if (row == null) {
            return -1;
        }
        int slot = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            if (child.isSelected()) {
                return slot;
            }
            slot++;
        }
        return -1;
    }

    /**
     * Selected slot, asking the bar's own getter first when it declares one and
     * falling back to observed selection state, which every bar keeps current
     * through {@code setCurrentTab}.
     */
    static int currentIndex(View bar) {
        HostApp app = LiquidGlassModule.app();
        if (app == null || !(bar instanceof ViewGroup)) {
            return -1;
        }
        ViewGroup row = findTabRow((ViewGroup) bar);
        int selected = selectedIndex(row);
        if (selected >= 0) {
            return selected;
        }
        try {
            Method getter = bar.getClass().getMethod(app.currentIndexMethod);
            Object value = getter.invoke(bar);
            if (value instanceof Integer) {
                int slot = slotForIndex(row, (Integer) value);
                if (slot >= 0) {
                    return slot;
                }
            }
        } catch (Throwable ignored) {
            // Bar does not expose a getter; selection state is the signal.
        }
        return -1;
    }

    /* ------------------------------------------------------------- debug */

    /** Lists host-package views under the root for install-failure logs. */
    static String describeTree(View root) {
        StringBuilder out = new StringBuilder();
        HostApp app = LiquidGlassModule.app();
        String prefix = app == null ? "com.tencent." : app.uiPrefix;
        collectNames(root, out, 0, prefix);
        return out.length() == 0 ? "(no host views found)" : out.toString();
    }

    private static void collectNames(View v, StringBuilder out,
                                     int depth, String prefix) {
        if (v == null || depth > 30 || out.length() > 2000) {
            return;
        }
        String name = v.getClass().getName();
        if (name.startsWith(prefix) || name.contains("TabView")
                || name.contains("TabWidget")) {
            out.append(depth).append(':').append(name).append(' ');
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectNames(group.getChildAt(i), out, depth + 1, prefix);
            }
        }
    }
}
