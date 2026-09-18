/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import sumicya.qself.diagnostics.FeatureJournal

/**
 * The one route into a settings panel.
 *
 * A sheet is addressed by three optional parts - [show] takes a home section,
 * a catalog group and a row to focus - and every call ends in exactly one
 * journal line, so a panel that never appeared is always explained:
 *
 * ```
 * UI sheet.show collapsed-anchor …   re-tap closed the open panel instead
 * UI sheet.show open … rows=N        panel mounted with N rows
 * UI sheet.show denied …             no host anchoring (see panel.open dead=…)
 * ```
 *
 * There is no window and no fragment stack here: the panel is inline, mounted
 * under the row that opened it.
 */
object SettingsOptionSheet {

    /** Kept for callers that still pass a dialog tag. */
    const val TAG = "qself-options"

    private const val KEY_HOME = "home"
    private const val KEY_GROUP = "group"
    private const val KEY_CURRENT_GROUP = "currentGroup"
    private const val KEY_FOCUS = "focus"

    /** Restores a panel from a saved argument bundle. */
    fun restore(activity: FragmentActivity, args: Bundle) {
        show(
            activity,
            home = args.getString(KEY_HOME),
            group = args.getString(KEY_CURRENT_GROUP) ?: args.getString(KEY_GROUP),
            focus = args.getString(KEY_FOCUS),
        )
    }

    /**
     * Shows the panel for a route. A plain tap on an already-open row collapses
     * it instead of opening a second panel; a [focus] arrival always opens.
     */
    fun show(activity: FragmentActivity, home: String? = null, group: String? = null, focus: String? = null) {
        if (focus == null && InlineSettings.collapseAnchor(activity)) {
            FeatureJournal.record("UI", "sheet.show", "collapsed-anchor home=$home group=$group")
            return
        }
        val content = InlineFeatureList(activity, group, home, focus)
        val mounted = InlineSettings.show(activity, content) != null
        if (!mounted) {
            // panel.open already recorded the dead reason; this names the route.
            FeatureJournal.record("UI", "sheet.show", "denied home=$home group=$group focus=$focus")
            return
        }
        FeatureJournal.record("UI", "sheet.show",
            "open home=$home group=$group focus=$focus rows=${content.rowCount}")
    }
}
