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
     * The number rides the icon's top edge: the badge centre lands on the
     * icon's centre-x and top line (half above, half over the glyph). No
     * group math, the icon is never moved. gapPx lifts the centre line for
     * fine-tuning (0 = exactly on the edge).
     *
     * <p>Returns additive deltas {dx, dy}; a fixed point by construction.</p>
     */
    static float[] overlayDeltas(
            float iconL, float iconT, float iconR,
            float badgeL, float badgeT, float badgeR, float badgeB,
            float gapPx) {
        float dx = (iconL + iconR) * 0.5f - (badgeL + badgeR) * 0.5f;
        float dy = (iconT - gapPx) - (badgeT + badgeB) * 0.5f;
        return new float[]{dx, dy};
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
        for (Entry badge : badges) {
            float right = badge.l + badge.v.getWidth();
            float bottom = badge.t + badge.v.getHeight();
            float[] d = overlayDeltas(icon.l, icon.t, iconR,
                    badge.l, badge.t, right, bottom, gapPx);
            badge.v.setTranslationX(badge.v.getTranslationX() + d[0]);
            badge.v.setTranslationY(badge.v.getTranslationY() + d[1]);
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
