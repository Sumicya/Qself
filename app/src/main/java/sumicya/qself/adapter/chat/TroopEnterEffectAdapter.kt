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
package sumicya.qself.adapter.chat

import io.github.qauxv.util.Initiator
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireMinVersionAnyQQ
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import sumicya.qself.hostapi.chat.EnterEffectApi
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Adapter for the troop enter-effect pipeline (RFC-03 §7 batch-1).
 *
 * Version-branched resolution — the branch selection itself is volatile
 * host knowledge and lives here, not in the feature:
 * 1. NT kernel (&gt;= 8.9.63): DexKit cached method descriptor
 *    [TroopEnterEffect_QQNT];
 * 2. legacy kernel: [Initiator._TroopEnterEffectController] + trait match
 *    (instance method named a/l, zero args, void).
 *
 * On a plain JVM the version gate cannot be evaluated (hostInfo is
 * uninitialized), so resolution degrades to null; the volatile trait
 * predicate is exposed [VisibleForTesting] as the second testable seam.
 */
object TroopEnterEffectAdapter : EnterEffectApi {

    override fun resolveEffectEntry(classLoader: ClassLoader): Method? = runCatching {
        if (requireMinVersionAnyQQ(QQVersion.QQ_8_9_63_BETA_11345)) {
            io.github.qauxv.util.dexkit.DexKit
                .requireMethodFromCache(io.github.qauxv.util.dexkit.TroopEnterEffect_QQNT)
        } else {
            Initiator._TroopEnterEffectController()?.declaredMethods
                ?.firstOrNull(::matchesLegacyTrait)
        }
    }.getOrNull()

    override fun installSuppressor(
        method: Method,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean = runCatching {
        XposedBridge.hookMethod(method, object : XC_MethodHook(50) {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    if (isEnabled()) {
                        param.result = null
                    }
                } catch (e: Throwable) {
                    onError(e)
                }
            }
        })
        true
    }.getOrElse {
        onError(it)
        false
    }

    /** legacy trait: instance `a()`/`l()`, zero args, void return. */
        fun matchesLegacyTrait(m: Method): Boolean {
        return !Modifier.isStatic(m.modifiers)
            && (m.name == "a" || m.name == "l")
            && m.parameterTypes.isEmpty()
            && m.returnType == Void.TYPE
    }
}
