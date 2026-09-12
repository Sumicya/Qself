/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.glass;

import android.content.Context;

import io.github.qauxv.config.ConfigManager;

/**
 * Tunable geometry and appearance of the bottom-bar glass. Every value is a
 * knob (never hard-wired), persisted under {@code qself.glass.bar.*} and
 * snap-loaded into volatile fields before an install attempt.
 */
public final class GlassConfig {

    public static final String PREFIX = "qself.glass.bar.";

    // ---- appearance ----
    public static volatile int transparency;   // 0..100, how much of the material to hide
    public static volatile int background;     // 0 live refraction, 1 flat material, 2 tinted
    public static volatile int tone;           // 0 follow host, 1 force light, 2 force dark
    public static volatile int labelMode;      // 0 keep host labels, 1 glyphs only
    public static volatile int labelSize = 12; // sp
    public static volatile int badgeMode;      // 0 number-exact, 1 capped 99+, 2 host badge, 3 hidden
    public static volatile int badgeSize = 10; // sp
    public static volatile int visibleTabCount;

    // ---- geometry, dp ----
    public static volatile int barOffsetDp = 12;
    public static volatile int hugPaddingDp = 32;

    private GlassConfig() {
    }

    /** Opacity left for the material after the transparency knob. */
    public static int materialAlpha() {
        return Math.round(255f * (100f - clamp(transparency, 0, 100)) / 100f);
    }

    /** Manual light/dark choice wins; auto follows the host detection. */
    public static boolean resolveNight(boolean hostIsDark) {
        return tone == 2 || (tone == 0 && hostIsDark);
    }

    /** Backing colour when the chosen background mode is not live refraction. */
    public static int backgroundColor(boolean night) {
        if (background == 2) {
            return night ? 0xFF18243F : 0xFFE3EAFF;
        }
        return night ? 0xFF111111 : 0xFFF7F7F7;
    }

    /** Re-read every knob, clamping bad values to their declared range. */
    public static void load(Context ignored) {
        ConfigManager c = ConfigManager.getDefaultConfig();
        transparency = intKnob(c, "transparency", 0, 0, 100);
        background = intKnob(c, "background", 0, 0, 2);
        tone = intKnob(c, "tone", 0, 0, 2);
        labelMode = intKnob(c, "labels", 0, 0, 1);
        labelSize = intKnob(c, "labelSize", 12, 9, 18);
        badgeMode = intKnob(c, "badges", 0, 0, 3);
        badgeSize = intKnob(c, "badgeSize", 10, 8, 18);
        barOffsetDp = intKnob(c, "barOffsetDp", 12, 0, 48);
        hugPaddingDp = intKnob(c, "hugPaddingDp", 32, 4, 96);
    }

    private static int intKnob(ConfigManager c, String key, int fallback, int low, int high) {
        try {
            return clamp(c.getIntOrDefault(PREFIX + key, fallback), low, high);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int clamp(int v, int low, int high) {
        return v < low ? low : (Math.min(v, high));
    }
}
