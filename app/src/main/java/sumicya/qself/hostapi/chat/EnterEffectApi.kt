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
