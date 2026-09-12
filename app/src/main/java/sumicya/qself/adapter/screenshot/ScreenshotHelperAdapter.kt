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
package sumicya.qself.adapter.screenshot

import android.content.Context
import android.os.Handler
import io.github.qauxv.util.dexkit.CScreenShotHelper
import io.github.qauxv.util.dexkit.DexKit
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import sumicya.qself.hostapi.chat.ScreenshotHelperApi
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Adapter for the screenshot-share helper (RFC-03 pilot).
 *
 * Resolution strategy chain — the first real use of capability degradation:
 * 1. DexKit cached descriptor (device main path: per-version deobfuscated
 *    trait lookup via [CScreenShotHelper]);
 * 2. direct FQN + method-trait match (degraded path: builds where the class
 *    name is not obfuscated, and the JVM contract tests).
 *
 * Every strategy is wrapped in runCatching: resolution failures degrade to
 * null and the feature reports ABSENT to the CapabilityRegistry; exceptions
 * never leak past the adapter boundary.
 */
object ScreenshotHelperAdapter : ScreenshotHelperApi {

    private const val HOST_CLASS = "com.tencent.mobileqq.screendetect.ScreenShotHelper"

    override fun resolveShowMethod(classLoader: ClassLoader): Method? {
        // strategy 1: DexKit deobfuscated cache (host runtime)
        val viaDexKit = runCatching {
            DexKit.requireClassFromCache(CScreenShotHelper).declaredMethods
                .firstOrNull(::matchesTrait)
        }.getOrNull()
        if (viaDexKit != null) {
            return viaDexKit
        }
        // strategy 2: direct class name (degraded/test path)
        return runCatching {
            classLoader.loadClass(HOST_CLASS).declaredMethods.firstOrNull(::matchesTrait)
        }.getOrNull()
    }

    override fun installSuppressor(
        method: Method,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean = runCatching {
        XposedBridge.hookMethod(method, object : XC_MethodHook(50) {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    if (isEnabled()) {
                        // suppress the helper popup entirely
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

    /** static void a(Context, String, Handler) — trait of the show entry. */
    private fun matchesTrait(m: Method): Boolean {
        if (m.name != "a" || !Modifier.isStatic(m.modifiers) || m.returnType != Void.TYPE) {
            return false
        }
        val argt = m.parameterTypes
        return argt.size == 3 && argt[0] == Context::class.java
            && argt[1] == String::class.java && argt[2] == Handler::class.java
    }
}
