/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.hostapi.chat

import java.lang.reflect.Method

/**
 * Port: the host's troop enter-effect pipeline (进场特效).
 *
 * Batch-1 pilot (RFC-03 §7): the adapter owns a **version-branched**
 * resolution strategy (NT kernel via DexKit method cache, legacy kernel
 * via Initiator class + method-name traits). The port stays blind to
 * versions — features ask for "the effect entry", not "which QQ build".
 */
interface EnterEffectApi {

    /**
     * Pure resolution. Returns the effect-trigger entry method, or null
     * when the host provides no such capability.
     */
    fun resolveEffectEntry(classLoader: ClassLoader): Method?

    /**
     * Install the suppression hook (original effect rendering skipped).
     *
     * @param isEnabled runtime toggle semantics
     * @param onError exception fence
     * @return true when installed
     */
    fun installSuppressor(
        method: Method,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean
}
