/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * Tab label appearance: visibility, forced tone colour, size, and the
 * icon-only mode that hides labels and recentres glyphs. All changes are
 * reversible; original values are remembered per view.
 */
final class GlassLabelStyle {

    private static final int ICON_ONLY_TAG_KEY = 0x7F5A0003;

    private final WeakHashMap<TextView, Float> titleAlphas =
            new WeakHashMap<>();
    private final WeakHashMap<TextView, ColorStateList> titleColors =
            new WeakHashMap<>();

    /** Applies label visibility, colour and size, then aligns icon-only rows. */
    void apply(ViewGroup row) {
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
            TextView title = findTabTitle(tab);
            if (title == null) {
                continue;
            }
            if (GlassConfig.labelMode == 1) {
                // Alpha only: keeps the host text as the badge layout anchor.
                if (!titleAlphas.containsKey(title)) {
                    titleAlphas.put(title, title.getAlpha());
                }
                title.setAlpha(0f);
            } else if (titleAlphas.containsKey(title)) {
                title.setAlpha(titleAlphas.remove(title));
            }
            if (GlassConfig.tone != 0) {
                if (!titleColors.containsKey(title)) {
                    titleColors.put(title, title.getTextColors());
                }
                title.setTextColor(GlassConfig.tone == 2
                        ? 0xFFF4F4F4 : 0xFF202020);
            } else if (titleColors.containsKey(title)) {
                title.setTextColor(titleColors.remove(title));
            }
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    GlassConfig.labelSize);
        }
        GlassConfig.visibleTabCount = count;
        float density = row.getResources().getDisplayMetrics().density;
        boolean iconOnly = GlassConfig.labelMode == 1
                || isQqIconOnlyRow(row);
        applyQqIconOnlyAlignment(row, iconOnly, density);
    }

    /**
     * Finds a tab's real title TextView, excluding unread badges: badges are
     * named Badge/RedTouch, titles carry their original title as a tag, and a
     * plain backgroundless TextView is treated as a title.
     */
    private static TextView findTabTitle(View v) {
        if (v instanceof TextView) {
            TextView text = (TextView) v;
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
                TextView found = findTabTitle(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean hasUsableTabTitle(View tab) {
        TextView title = findTabTitle(tab);
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
    static boolean isQqIconOnlyRow(ViewGroup row) {
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

    /* -------------------------------------------------- icon-only centre */

    /**
     * With titles removed the icon must move up about 7.5dp to recentre. Only
     * glyphs and badges move; shifting the full-size touch wrapper as well
     * would apply the offset twice on some tabs.
     */
    static void applyQqIconOnlyAlignment(ViewGroup row, boolean iconOnly,
                                         float density) {
        float offset = density * 7.5f;
        for (int i = 0; i < row.getChildCount(); i++) {
            View tab = row.getChildAt(i);
            if (tab.getVisibility() != View.GONE) {
                alignIconOnlyContent(tab, iconOnly, offset);
            }
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
}
