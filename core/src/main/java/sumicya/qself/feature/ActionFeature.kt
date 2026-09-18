/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature

import android.content.Context

/**
 * A feature without a switch: its row performs an action (dialog, activity
 * launch) when clicked. Actions run in the module's UI process, so they
 * must not depend on host classes — use explicit component names.
 */
interface ActionFeature : QselfFeature {

    override val isEnabled: Boolean
        get() = true

    override fun init(ctx: FeatureContext): Boolean = true

    fun onClick(context: Context)
}
