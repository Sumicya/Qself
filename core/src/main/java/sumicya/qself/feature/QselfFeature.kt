/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature

import android.content.Context
import sumicya.qself.ProcessKind
import sumicya.qself.Qself
import sumicya.qself.config.Settings
import sumicya.qself.host.Host
import sumicya.qself.util.HostInfo

/** Everything a feature may need from the runtime. */
class FeatureContext(
    val context: Context,
    val settings: Settings,
    val host: Host,
    val hostInfo: HostInfo,
)

/**
 * A Qself feature. Implementations are `object`s annotated with
 * [sumicya.qself.annotation.QselfFeature]; the KSP processor collects them
 * into a generated registry, so the runtime never reflects on features.
 */
interface QselfFeature {

    /** Stable identifier, e.g. "misc.anti_update". */
    val id: String

    val name: String

    val summary: String

    val category: FeatureCategory

    val experimental: Boolean

    val targetProcesses: Set<ProcessKind>

    val defaultEnabled: Boolean

    /**
     * Which QQ generation this feature was written for. Defaults to
     * [sumicya.qself.util.HostGeneration.PRE_NT] because that is what the
     * current feature set targets; NT features must say so explicitly.
     */
    val hostGeneration: sumicya.qself.util.HostGeneration
        get() = sumicya.qself.util.HostGeneration.PRE_NT

    /**
     * Install the hooks. Called exactly once per target process at startup,
     * on the main thread before the app is fully created.
     *
     * Return `false` (or throw) to mark this feature as failed; the rest of
     * the module keeps running.
     */
    fun init(ctx: FeatureContext): Boolean

    /** Current switch state (persisted). */
    val isEnabled: Boolean
}

/**
 * A feature with an on/off switch. The switch is persisted through
 * [Qself.settings] and checked lazily, so the UI and the runtime always
 * agree without any extra plumbing.
 */
abstract class SwitchFeature : QselfFeature {

    override val isEnabled: Boolean
        get() = Qself.settings.isEnabled(id, defaultEnabled)

    abstract fun initOnce(ctx: FeatureContext): Boolean

    override fun init(ctx: FeatureContext): Boolean = initOnce(ctx)
}
