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

    /** badge view -> last count reported via an updateNum-style hook. */
    private static final WeakHashMap<View, Integer> sCounts = new WeakHashMap<>();

    /** badge classes already hooked (or dumped because no hook exists). */
    private static final Set<Class<?>> sHooked = Collections.synchronizedSet(new HashSet<>());

    /** cached tab row per bar. */
    private static final WeakHashMap<View, View> sRow = new WeakHashMap<>();

    /** Pure: label for a captured count; null for nothing-to-draw. */
    static String countLabel(Integer count, CharSequence text) {
        if (count != null) {
            if (count <= 0) {
                return null;
            }
            return count > 99 ? "99+" : String.valueOf(count.intValue());
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
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(2f * density, 0f, 0f, 0x99000000);
        float baseline = 11f * density;
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
            badge.setAlpha(0f);
            float cx = tab.getLeft() + tab.getWidth() * 0.5f;
            canvas.drawText(label, cx, baseline, paint);
        }
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
