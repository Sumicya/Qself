/* SPDX-License-Identifier: GPL-3.0-or-later */
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
