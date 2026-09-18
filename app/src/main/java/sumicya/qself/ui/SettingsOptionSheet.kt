/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/** Legacy route compatibility; this no longer creates a window or a fragment stack. */
object SettingsOptionSheet {
    const val TAG = "qself-options"
    fun restore(activity: FragmentActivity, args: Bundle) {
        show(activity, args.getString("home"), args.getString("currentGroup") ?: args.getString("group"), args.getString("focus"))
    }
    fun show(activity: FragmentActivity, home: String? = null, group: String? = null, focus: String? = null) {
        if (focus == null && InlineSettings.collapseAnchor(activity)) {
            // Probe: the tap toggled an already-open panel closed instead of
            // opening a new one - the expected re-tap behaviour.
            sumicya.qself.diagnostics.FeatureJournal.record("UI", "sheet.show", "collapsed-anchor home=$home group=$group")
            return
        }
        sumicya.qself.diagnostics.FeatureJournal.record("UI", "sheet.show", "open home=$home group=$group focus=$focus")
        InlineSettings.show(activity, InlineFeatureList(activity, group, home, focus))
    }
}
