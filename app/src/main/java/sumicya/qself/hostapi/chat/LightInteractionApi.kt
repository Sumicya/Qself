/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.hostapi.chat

import java.lang.reflect.Method

/**
 * Port: the host's "light interaction" config source (轻互动:
 * 聊天列表的表情快捷互动).
 *
 * Batch-1 pilot (RFC-03 §7), sealed-handle form: the two host generations
 * need different blanks (NT returns an empty list, legacy returns null),
 * so resolution yields a typed handle carrying both the method and the
 * blank semantics. Features never branch on host versions.
 */
interface LightInteractionApi {

    /** Resolved host target plus the blank value semantics. */
    sealed class Handle {
        abstract val method: Method

        /** NT kernel: a list-provider method; blanking = empty list. */
        class NtListProvider(override val method: Method) : Handle()

        /** Legacy kernel: a switch-style method; blanking = null. */
        class LegacySwitch(override val method: Method) : Handle()
    }

    /**
     * Pure resolution. Returns the config-source handle, or null when the
     * host provides no such capability.
     */
    fun resolveConfigSource(classLoader: ClassLoader): Handle?

    /**
     * Install the blank hook: when enabled, invocations return the blank
     * appropriate for the resolved handle.
     *
     * @param isEnabled runtime toggle semantics
     * @param onError exception fence
     * @return true when installed
     */
    fun installBlank(
        handle: Handle,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean
}
