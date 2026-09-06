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

import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireMinQQVersion
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import sumicya.qself.hostapi.chat.LightInteractionApi
<<<<<<< HEAD
=======
import sumicya.qself.hostapi.chat.LightInteractionApi.Handle
>>>>>>> dc35d5a (fix: import nested Handle type explicitly (Kotlin scoping rule))
import java.lang.reflect.Method

/**
 * Adapter for the light-interaction config source (RFC-03 §7 batch-1).
 *
 * Version-branched resolution:
 * 1. NT kernel (&gt;= 9.0.8): direct FQN
 *    com.tencent.qqnt.biz.lightbusiness.lightinteraction.LIAConfigManager,
 *    the single 1-param list-provider method -> [LightInteractionApi.Handle.NtListProvider];
 * 2. legacy kernel: DexKit cached [DisableLightInteractionMethod] ->
 *    [LightInteractionApi.Handle.LegacySwitch].
 *
 * The blank semantics (empty list vs null) is carried by the sealed handle;
 * the install path never branches on versions.
 */
object LightInteractionAdapter : LightInteractionApi {

    private const val NT_CONFIG_MANAGER =
        "com.tencent.qqnt.biz.lightbusiness.lightinteraction.LIAConfigManager"

    override fun resolveConfigSource(classLoader: ClassLoader): Handle? = runCatching {
        if (requireMinQQVersion(QQVersion.QQ_9_0_8)) {
            val clz = classLoader.loadClass(NT_CONFIG_MANAGER)
            Handle.NtListProvider(clz.declaredMethods.single(::matchesNtTrait))
        } else {
            Handle.LegacySwitch(
                io.github.qauxv.util.dexkit.DexKit
                    .requireMethodFromCache(io.github.qauxv.util.dexkit.DisableLightInteractionMethod)
            )
        }
    }.getOrNull()

    override fun installBlank(
        handle: Handle,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean = runCatching {
        val method: Method = handle.method
        XposedBridge.hookMethod(method, object : XC_MethodHook(50) {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    if (isEnabled()) {
                        param.result = when (handle) {
                            is Handle.NtListProvider -> emptyList<Any?>()
                            is Handle.LegacySwitch -> null
                        }
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

    /** NT trait: exactly one parameter, returns java.util.List. */
        fun matchesNtTrait(m: Method): Boolean {
        return m.parameterTypes.size == 1 && m.returnType == java.util.List::class.java
    }
}
