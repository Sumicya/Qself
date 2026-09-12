// Vendored from liuran001/WeChat-LiquidGlass (MIT): https://github.com/liuran001/WeChat-LiquidGlass
package sumicya.qself.glass;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.qauxv.config.ConfigManager;

/** Shared settings for the actual QQ renderer, not just its preview. */
public final class GlassConfig {

    /** Named before QQ was a target; kept so existing WeChat setups still read. */
    private static final String PREFS = "wx_liquid_glass_cfg";

    /**
     * Distance between the bottom of the glass pill and the screen edge, dp.
     */
    static volatile int barOffsetDp = 12;

    /**
     * Breathing room added to each tab column when the pill hugs its
     * content, dp. Trimmed to a minimum: the pill hugs the glyphs almost
     * flush (columns = 24dp basis + this).
     */
    static volatile int hugPaddingDp = 32;


    public static final String PREFIX = "qself.glass.bar.";
    public static volatile int transparency = 0;
    public static volatile int background = 0;
    public static volatile int tone = 0;
    public static volatile int labelMode = 0;
    public static volatile int labelSize = 12;
    public static volatile int badgeMode = 0;
    public static volatile int badgeSize = 10;
    public static volatile int visibleTabCount = 0;

    public static int materialAlpha() { return Math.round(255f * (100 - transparency) / 100f); }
    public static boolean resolveNight(boolean detected) { return tone == 0 ? detected : tone == 2; }
    public static int backgroundColor(boolean night) {
        return background == 2 ? (night ? 0xFF18243F : 0xFFE3EAFF) : (night ? 0xFF111111 : 0xFFF7F7F7);
    }
    private static int read(ConfigManager c, String key, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, c.getIntOrDefault(PREFIX + key, fallback))); }
        catch (Throwable ignored) { return fallback; }
    }
    private GlassConfig() {
    }

    public static void load(Context ctx) {
        ConfigManager c = ConfigManager.getDefaultConfig();
        transparency = read(c, "transparency", 0, 0, 100);
        background = read(c, "background", 0, 0, 2);
        tone = read(c, "tone", 0, 0, 2);
        labelMode = read(c, "labels", 0, 0, 1);
        labelSize = read(c, "labelSize", 12, 9, 18);
        badgeMode = read(c, "badges", 0, 0, 3);
        badgeSize = read(c, "badgeSize", 10, 8, 18);
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            barOffsetDp = p.getInt("barOffsetDp", barOffsetDp);
            hugPaddingDp = p.getInt("hugPaddingDp", hugPaddingDp);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("config load failed", t);
        }
    }
}
