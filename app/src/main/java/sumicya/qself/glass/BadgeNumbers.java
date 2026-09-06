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
 * from a {@code QUIBadge.updateNum(int)} hook — no assumption that the badge
 * is a TextView. Numbers are drawn from the host layout's dispatchDraw, on
 * top of every layer including the droplet: no view is ever added to any
 * tree, so no layout round is disturbed (the lesson of the withdrawn v3,
 * which called addView during a measure pass and tripped the installer's
 * stock-bar-restore safety valve).
 */
public final class BadgeNumbers {

    /** badge view -> last count reported via updateNum(int). */
    private static final WeakHashMap<View, Integer> sCounts = new WeakHashMap<>();

    /** badge classes whose updateNum is already hooked. */
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
            badge.setAlpha(0f);
            hookUpdateNumOnce(badge, host);
            String label = badge instanceof TextView
                    ? countLabel(sCounts.get(badge), ((TextView) badge).getText())
                    : countLabel(sCounts.get(badge), null);
            if (label == null) {
                continue;
            }
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

    private static void hookUpdateNumOnce(View badge, final View host) {
        Class<?> cls = badge.getClass();
        if (sHooked.contains(cls)) {
            return;
        }
        sHooked.add(cls);
        try {
            Method m = cls.getMethod("updateNum", int.class);
            LiquidGlassModule.hookAfter(m, param -> {
                Object thiz = param.thisObject;
                if (thiz instanceof View) {
                    sCounts.put((View) thiz, (Integer) param.args[0]);
                    host.postInvalidate();
                }
            });
        } catch (Throwable t) {
            LiquidGlassModule.logErr("updateNum hook unavailable", t);
        }
    }

    private BadgeNumbers() {
    }
}
