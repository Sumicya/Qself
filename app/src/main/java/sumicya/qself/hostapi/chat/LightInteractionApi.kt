/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */
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
