/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.profile;

import android.content.SharedPreferences;

/** Non-destructive, repeatable migration, including after restoring an older backup. */
public final class ProfileMigration {
    public static final String SCHEMA = "qself.profile.schema";
    public static final String LEGACY_GLASS = "qself.settings.glass";
    public static final String OVERLAY_GLASS = "qself.overlays.glass";
    private ProfileMigration() {}

    public static synchronized void migrate(SharedPreferences config) {
        // Never down-migrate a backup produced by a newer edition.
        if (config.getInt(SCHEMA, 0) >= 1) return;
        SharedPreferences.Editor editor = config.edit();
        if (!config.contains(OVERLAY_GLASS)) {
            int old = config.getInt(LEGACY_GLASS, 1);
            editor.putInt(OVERLAY_GLASS, old >= 0 && old <= 2 ? old : 1);
        }
        // Keep every legacy feature value (including O3 and mixed ad-suite children),
        // account store, mark and unknown key in place for backup and future re-addition.
        // Runtime policy, NOT destructive config rewriting, parks excluded features.
        editor.putInt(SCHEMA, 1).apply();
    }
}
