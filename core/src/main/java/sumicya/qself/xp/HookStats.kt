/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.xp

import java.util.concurrent.ConcurrentHashMap

/**
 * How many hooks each feature actually managed to install.
 *
 * A feature that reports "ok" is not the same as a feature that hooked
 * something: on a QQ build where every signature moved, `initOnce` can return
 * true having installed zero hooks (every `NtHooks.replace*` call quietly
 * returning false is the *designed* behaviour). That difference is invisible in
 * the log — "ok" twice, once with 8 hooks and once with 0 — and it is exactly
 * the difference between "shielded update check" and "nothing happened".
 *
 * Counted at the single place hooks are installed ([Hooks]), printed next to
 * the feature in the boot summary, and readable from the UI process through
 * [summary] when the bridge is asked for diagnostics.
 */
object HookStats {

    private val counts = ConcurrentHashMap<String, Int>()

    /** One hook for [featureId] was accepted by the engine. */
    fun installed(featureId: String) {
        counts.merge(featureId, 1) { current, one -> current + one }
    }

    /** Hooks installed by [featureId] in this process. */
    fun count(featureId: String): Int = counts[featureId] ?: 0

    /** Every feature that installed at least one hook, id -> count. */
    fun summary(): Map<String, Int> = LinkedHashMap(counts)

    /** Total across features — the one number worth putting in a bug report. */
    fun total(): Int = counts.values.sum()
}
