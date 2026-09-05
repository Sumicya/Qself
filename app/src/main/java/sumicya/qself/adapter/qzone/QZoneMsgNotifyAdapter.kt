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
package sumicya.qself.adapter.qzone

import io.github.qauxv.util.dexkit.CQzoneMsgNotify
import io.github.qauxv.util.dexkit.DexKit
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import sumicya.qself.hostapi.notification.QZoneMsgNotifyApi
import sumicya.qself.hostapi.notification.QZoneMsgNotifyApi.NotifierHandle
import java.lang.reflect.Method

/**
 * Adapter for the QZone push notification entry (RFC-03 §5 pilot).
 *
 * Resolution strategies (mirroring the legacy hook's proven heuristics):
 * 1. DexKit cached descriptor [CQzoneMsgNotify] (device main path);
 * 2. direct FQN fallback (unobfuscated name cooperation.qzone.push.MsgNotification).
 *
 * Within the resolved class, the entry is the **widest void method** (the
 * legacy heuristic), and the description argument is the **second String
 * parameter** (uin first, description second). Both analyses used to live
 * inside the old hook's callback (MSG_INFO_OFFSET state machine); they are
 * adapter knowledge now, resolved once and handed out as a [NotifierHandle].
 */
object QZoneMsgNotifyAdapter : QZoneMsgNotifyApi {

    private const val HOST_CLASS = "cooperation.qzone.push.MsgNotification"

    override fun resolveNotifier(classLoader: ClassLoader): NotifierHandle? {
        val clz = runCatching { DexKit.requireClassFromCache(CQzoneMsgNotify) }.getOrNull()
            ?: runCatching { classLoader.loadClass(HOST_CLASS) }.getOrNull()
            ?: return null
        val method = runCatching { widestVoidMethod(clz) }.getOrNull() ?: return null
        val descIndex = runCatching { secondStringParameterIndex(method) }.getOrNull() ?: return null
        return NotifierHandle(method, descIndex)
    }

    override fun installMute(
        handle: NotifierHandle,
        isEnabled: () -> Boolean,
        shouldMute: (String?) -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean = runCatching {
        val method: Method = handle.method
        val descIndex = handle.descArgIndex
        XposedBridge.hookMethod(method, object : XC_MethodHook(50) {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    if (isEnabled() && shouldMute(param.args[descIndex] as String?)) {
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

    /** legacy heuristic: the void method with the most parameters. */
    private fun widestVoidMethod(clz: Class<*>): Method? =
        clz.declaredMethods.filter { it.returnType == Void.TYPE }.maxByOrNull { it.parameterTypes.size }

    /** legacy heuristic: index of the second String parameter, null if absent. */
    private fun secondStringParameterIndex(method: Method): Int? {
        var hit = 0
        method.parameterTypes.forEachIndexed { i, t ->
            if (t == String::class.java) {
                if (hit == 1) {
                    return i
                }
                hit++
            }
        }
        return null
    }
}
