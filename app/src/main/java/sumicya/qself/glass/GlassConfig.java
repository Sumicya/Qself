// Vendored from liuran001/WeChat-LiquidGlass (MIT): https://github.com/liuran001/WeChat-LiquidGlass
package sumicya.qself.glass;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The one tunable the bar exposes, read from the host app's own SharedPreferences.
 *
 * <p>Nothing in the module writes this file — there is no settings UI — but it
 * lets the float height be changed without a rebuild, which is the only value
 * that is really a matter of taste. Each host keeps its own copy, since the
 * file is read through that app's context and lands in that app's data dir.
 */
public final class GlassConfig {

    /** Named before QQ was a target; kept so existing WeChat setups still read. */
    private static final String PREFS = "wx_liquid_glass_cfg";

    /**
     * Distance between the bottom of the glass pill and the screen edge, dp.
     */
    static volatile int barOffsetDp = 12;

    /**
     * Breathing room added to each tab column when the pill hugs its content,
     * dp. Together with the 24dp icon basis this sizes the touch column:
     * 12 made 36dp columns (cramped), upstream 32 gives 56dp. 24 lands at
     * 48dp — comfortable and still tight to the rim.
     */
    static volatile int hugPaddingDp = 24;

    /** Whether the unread badge relocates to top-centre above the icon. */
    static volatile boolean badgeTopCenter = true;

    private GlassConfig() {
    }

    public static void load(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            barOffsetDp = p.getInt("barOffsetDp", barOffsetDp);
            hugPaddingDp = p.getInt("hugPaddingDp", hugPaddingDp);
            badgeTopCenter = p.getBoolean("badgeTopCenter", badgeTopCenter);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("config load failed", t);
        }
    }
}
