// Vendored-derived from liuran001/WeChat-LiquidGlass (MIT): https://github.com/liuran001/WeChat-LiquidGlass
// Extension: plain unread numbers drawn over the bar, replacing the red capsule.
package sumicya.qself.glass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Plain unread numbers on top of the bar. The stock QUIBadge (red capsule
 * painted in its own onDraw) is hidden by alpha and its count is captured
 * from an {@code updateNum} hook. Numbers are drawn from the host layout's
 * dispatchDraw, on top of every layer including the droplet: no view is ever
 * added to any tree, so no layout round is disturbed (the lesson of the
 * withdrawn v3, which called addView during a measure pass and tripped the
 * installer's stock-bar-restore safety valve).
 *
 * <p>QQ 9.2.10 lesson: {@code updateNum(int)} no longer exists there with
 * that exact signature (NoSuchMethodException on device), so the match is
 * name-based over declared methods with a numeric first argument, and the
 * exact one-argument form wins when present. If no {@code updateNum} exists
 * at all, the class's declared methods are dumped once to the module log for
 * a data-driven follow-up — and the stock capsule is left visible: hiding it
 * without a count source would make the badge disappear entirely.
 */
public final class BadgeNumbers {

    /** one-shot device diagnostics switch (set after the first log). */
    private static boolean sDiagLogged = false;

    /** badge view -> last count reported via an updateNum-style hook. */
    private static final WeakHashMap<View, Integer> sCounts = new WeakHashMap<>();

    /** badge classes already hooked (or dumped because no hook exists). */
    private static final Set<Class<?>> sHooked = Collections.synchronizedSet(new HashSet<>());

    /** cached tab row per bar. */
    private static final WeakHashMap<View, View> sRow = new WeakHashMap<>();

    /**
     * Pure: label for a captured count; null for nothing-to-draw. A hooked
     * count is the true number and is shown verbatim - no 99+ cap: the user
     * runs QQ's exact-count display, and the stock capsule's own "99+" text
     * (the fallback source) caps regardless of that setting.
     */
    static String countLabel(Integer count, CharSequence text) {
        if (count != null) {
            if (count <= 0) {
                return null;
            }
            return String.valueOf(count.intValue());
        }
        if (text == null) {
            return null;
        }
        String s = text.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Called from LiquidGlassHostLayout.dispatchDraw, after all children. */
    static void drawOver(View host, ViewGroup bar, Canvas canvas, float density) {
        if (bar == null) {
            return;
        }
        View row = sRow.get(bar);
        if (row == null) {
            row = TabBarBridge.findTabRow(bar);
            if (row == null) {
                return;
            }
            sRow.put(bar, row);
        }
        if (!(row instanceof ViewGroup)) {
            return;
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(10f * density);
        paint.setTextAlign(Paint.Align.CENTER);
        // Slightly translucent: the number overlays the icon itself, so the
        // glyph must let the artwork show through (user direction: give it
        // some transparency).
        paint.setColor(Color.WHITE);
        paint.setAlpha(215);
        paint.setShadowLayer(2f * density, 0f, 0f, 0x66000000);
        for (int i = 0; i < ((ViewGroup) row).getChildCount(); i++) {
            View tab = ((ViewGroup) row).getChildAt(i);
            if (!(tab instanceof ViewGroup) || tab.getVisibility() != View.VISIBLE) {
                continue;
            }
            View badge = findBadge((ViewGroup) tab, 0);
            if (badge == null) {
                continue;
            }
            hookUpdateNumOnce(badge, host);
            String label = badge instanceof TextView
                    ? countLabel(sCounts.get(badge), ((TextView) badge).getText())
                    : countLabel(sCounts.get(badge), null);
            if (label == null) {
                // No count source (yet): keep the stock capsule visible
                // instead of hiding it into nothing.
                continue;
            }
            // Anchor on the tab's text label, in host coordinates. The icon
            // anchor matched the drag-animation layer, which spans the whole
            // cell, so the number landed below the bar (device evidence,
            // QQ 9.2.10). The label is the one reliably identifiable view.
            View labelView = findLabel((ViewGroup) tab, badge, 0);
            if (labelView == null) {
                continue;
            }
            int[] labelAt = offsetInHost(labelView, host);
            if (labelAt == null) {
                continue;
            }
            if (!sDiagLogged) {
                sDiagLogged = true;
                LiquidGlassModule.log(android.util.Log.INFO, "badge diag: cls="
                        + badge.getClass().getName()
                        + " tv=" + (badge instanceof TextView)
                        + " text=" + (badge instanceof TextView ? ((TextView) badge).getText() : null)
                        + " count=" + sCounts.get(badge)
                        + " label=" + label
                        + " labelTop=" + labelView.getTop()
                        + " iconBottom=" + iconBottomLocal
                        + " tabH=" + tab.getHeight());
            }
            badge.setAlpha(0f);
            // Centre of the icon itself: the number sits ON the icon
            // (user direction), horizontally on the shared label/icon axis.
            // The icon's bottom edge is the lowest sibling bottom above the
            // label; QQ tab icons are ~24dp, so the centre is half that
            // above the bottom edge.
            ViewGroup tabGroup = (ViewGroup) tab;
            int labelTopLocal = labelView.getTop();
            int iconBottomLocal = labelTopLocal - Math.round(6f * density);
            for (int s = 0; s < tabGroup.getChildCount(); s++) {
                View sib = tabGroup.getChildAt(s);
                if (sib == labelView || sib == badge) {
                    continue;
                }
                int bottom = sib.getTop() + sib.getHeight();
                if (bottom <= labelTopLocal + Math.round(2f * density) && bottom > iconBottomLocal) {
                    iconBottomLocal = bottom;
                }
            }
            if (labelTopLocal - iconBottomLocal > Math.round(20f * density)) {
                // Implausible geometry: settle for the 6dp-gap default.
                iconBottomLocal = labelTopLocal - Math.round(6f * density);
            }
            // User-confirmed placement: the number sits directly ABOVE the
            // icon's top edge (not overlapping it), centred on the shared
            // icon/label axis. Icon top estimated at 24dp above the measured
            // icon bottom; baseline 3dp above that edge.
            int iconTopLocal = iconBottomLocal - Math.round(24f * density);
            float baseline = labelAt[1] - (labelTopLocal - iconTopLocal)
                    - Math.round(3f * density);
            if (baseline < paint.getTextSize()) {
                baseline = paint.getTextSize();
            }
            float cx = labelAt[0] + labelView.getWidth() * 0.5f;
            canvas.drawText(label, cx, baseline, paint);
        }
    }

    /**
     * The tab's text label: the lowest-positioned visible TextView carrying
     * non-empty text, excluding the badge itself (a badge TextView also
     * carries text, but sits at the top of the cell).
     */
    private static View findLabel(ViewGroup group, View badge, int depth) {
        if (depth > 6) {
            return null;
        }
        View best = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == badge || child.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (child instanceof TextView) {
                CharSequence txt = ((TextView) child).getText();
                if (txt != null && txt.toString().trim().length() > 0) {
                    if (best == null || child.getTop() > best.getTop()) {
                        best = child;
                    }
                }
            }
            if (child instanceof ViewGroup) {
                View found = findLabel((ViewGroup) child, badge, depth + 1);
                if (found != null && (best == null || found.getTop() > best.getTop())) {
                    best = found;
                }
            }
        }
        return best;
    }

    /** [x, y] of {@code v}'s top-left in {@code ancestor} coordinates, null if unrelated. */
    private static int[] offsetInHost(View v, View ancestor) {
        int x = 0;
        int y = 0;
        while (v != ancestor) {
            android.view.ViewParent p = v.getParent();
            if (!(p instanceof View)) {
                return null;
            }
            x += v.getLeft();
            y += v.getTop();
            v = (View) p;
        }
        return new int[]{x, y};
    }

    private static View findBadge(ViewGroup group, int depth) {
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

    /**
     * The updateNum to hook: name-based over declared methods, first argument
     * numeric; the narrowest signature wins. Null when nothing matches.
     */
    static Method pickUpdateNum(Class<?> cls) {
        Method best = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals("updateNum")) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 0 || !isCountType(pt[0])) {
                continue;
            }
            if (best == null || best.getParameterTypes().length > pt.length) {
                best = m;
            }
        }
        return best;
    }

    private static boolean isCountType(Class<?> c) {
        return c == int.class || c == long.class
                || c == Integer.class || c == Long.class;
    }

    private static void hookUpdateNumOnce(View badge, final View host) {
        Class<?> cls = badge.getClass();
        if (sHooked.contains(cls)) {
            return;
        }
        sHooked.add(cls);
        Method target = pickUpdateNum(cls);
        if (target == null) {
            // Evidence for the next iteration: what does this version call
            // its badge refresh methods?
            StringBuilder sb = new StringBuilder("no updateNum on ")
                    .append(cls.getName()).append("; declared methods:");
            for (Method m : cls.getDeclaredMethods()) {
                sb.append(' ').append(m.getName()).append('/')
                        .append(m.getParameterTypes().length);
            }
            LiquidGlassModule.log(android.util.Log.WARN, sb.toString());
            return;
        }
        try {
            LiquidGlassModule.hookAfter(target, param -> {
                Object thiz = param.thisObject;
                Object arg = param.args.length > 0 ? param.args[0] : null;
                if (thiz instanceof View && arg instanceof Number) {
                    sCounts.put((View) thiz, ((Number) arg).intValue());
                    host.postInvalidate();
                }
            });
        } catch (Throwable t) {
            LiquidGlassModule.logErr("updateNum hook failed", t);
        }
    }

    private BadgeNumbers() {
    }
}
