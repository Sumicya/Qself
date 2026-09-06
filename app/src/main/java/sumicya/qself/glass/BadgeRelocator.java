// Vendored-derived from liuran001/WeChat-LiquidGlass (MIT): https://github.com/liuran001/WeChat-LiquidGlass
// Extension: QQ unread badge relocation (top-centre above the icon).
package sumicya.qself.glass;

import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

/**
 * Moves each QQ tab's unread badge from its stock top-right hang to
 * top-centre above the icon, purely as translation deltas on top of whatever
 * layout the host (and the glass surgery) produced.
 *
 * <p>Translation-only is the point: the real bar, the refracting glass and
 * the droplet's own badge collection (which folds translationX/Y in) all
 * follow automatically, with zero changes to the render pipeline.</p>
 *
 * <p>Recomputed on every layout of the tab row, so it tracks the icon-only
 * alignment shift; self-converging — once at target, the delta is zero.</p>
 */
public final class BadgeRelocator {

    /** App-style tag key (see ICON_ONLY_TRANSLATION_TAG_KEY above). */
    private static final int INSTALLED_TAG_KEY = 0x7F5A0004;

    /**
     * Group-centring relocation with an overlay badge: the badge is proposed
     * straddling the icon's top edge — centre-x on the icon, centre-y
     * gapPx above the icon top — instead of hovering fully above it. A fully
     * elevated badge made the (icon + badge) union nearly as tall as the tab,
     * which pushed the badge right back onto the tab's top edge; the overlay
     * adds only half a badge of height, so the pair truly centres as one
     * body and nothing sticks out.
     *
     * <p>Returns additive deltas: {iconDx, iconDy, badgeDx, badgeDy}. The
     * target is a fixed point of the additive update (at target every value
     * is zero), so per-layout reapplication cannot drift.</p>
     */
    static float[] groupRelocation(
            float iconL, float iconT, float iconR, float iconB,
            float badgeL, float badgeT, float badgeR, float badgeB,
            float tabW, float tabH, float gapPx) {
        // proposed badge: centre-x on the icon centre; centre-y on the icon
        // top line, lifted by gapPx
        float bx = (iconL + iconR) * 0.5f - (badgeL + badgeR) * 0.5f;
        float badgeCy = (badgeT + badgeB) * 0.5f;
        float by = (iconT - gapPx) - badgeCy;
        // union of the icon and the proposed badge
        float ul = Math.min(iconL, badgeL + bx);
        float ut = Math.min(iconT, badgeT + by);
        float ur = Math.max(iconR, badgeR + bx);
        float ub = Math.max(iconB, badgeB + by);
        // centre the union within the tab
        float g0 = tabW * 0.5f - (ul + ur) * 0.5f;
        float g1 = tabH * 0.5f - (ut + ub) * 0.5f;
        return new float[]{g0, g1, bx + g0, by + g1};
    }

    /** Attaches the per-layout reapplication listener once per tab row. */
    public static void install(ViewGroup tabRow, float density) {
        if (tabRow.getTag(INSTALLED_TAG_KEY) != null) {
            return;
        }
        tabRow.setTag(INSTALLED_TAG_KEY, Boolean.TRUE);
        final float gapPx = 2f * density;
        tabRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            for (int i = 0; i < tabRow.getChildCount(); i++) {
                View tab = tabRow.getChildAt(i);
                if (tab instanceof ViewGroup && tab.getVisibility() == View.VISIBLE) {
                    applyToTab((ViewGroup) tab, gapPx);
                }
            }
        });
    }

    /** Applies the relocation delta to every badge inside one tab, if any. */
    static void applyToTab(ViewGroup tab, float gapPx) {
        HostApp app = LiquidGlassModule.app();
        if (app == null) {
            return;
        }
        ArrayList<Entry> icons = new ArrayList<>(1);
        ArrayList<Entry> badges = new ArrayList<>(2);
        collect(tab, 0f, 0f, app, icons, badges, 0);
        if (icons.isEmpty() || badges.isEmpty()) {
            return;
        }
        // icon and badge may sit at different hierarchy depths: only the
        // tab-relative accumulated rects are comparable (upstream's
        // collectBadges folds translations in the same way)
        Entry icon = icons.get(0);
        float iconR = icon.l + icon.v.getWidth();
        float iconB = icon.t + icon.v.getHeight();
        float tabW = tab.getWidth();
        float tabH = tab.getHeight();
        for (Entry badge : badges) {
            float right = badge.l + badge.v.getWidth();
            float bottom = badge.t + badge.v.getHeight();
            float[] d = groupRelocation(icon.l, icon.t, iconR, iconB,
                    badge.l, badge.t, right, bottom, tabW, tabH, gapPx);
            icon.v.setTranslationX(icon.v.getTranslationX() + d[0]);
            icon.v.setTranslationY(icon.v.getTranslationY() + d[1]);
            badge.v.setTranslationX(badge.v.getTranslationX() + d[2]);
            badge.v.setTranslationY(badge.v.getTranslationY() + d[3]);
        }
    }

    /** A found view with its tab-relative left/top (translations folded in). */
    private static final class Entry {
        final View v;
        final float l;
        final float t;

        Entry(View v, float l, float t) {
            this.v = v;
            this.l = l;
            this.t = t;
        }
    }

    private static void collect(View v, float ox, float oy, HostApp app,
            ArrayList<Entry> icons, ArrayList<Entry> badges, int depth) {
        if (depth > 6) {
            return;
        }
        float cx = ox + v.getLeft() + v.getTranslationX();
        float cy = oy + v.getTop() + v.getTranslationY();
        String name = v.getClass().getName();
        if (app.isTabIconClass(name)) {
            icons.add(new Entry(v, cx, cy));
        } else if (name.contains("Badge") && v.getWidth() > 0) {
            badges.add(new Entry(v, cx, cy));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), cx, cy, app, icons, badges, depth + 1);
            }
        }
    }

    private BadgeRelocator() {
    }
}
