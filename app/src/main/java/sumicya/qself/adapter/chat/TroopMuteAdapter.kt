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
import io.github.qauxv.util.LicenseStatus
import io.github.qauxv.util.Reflex
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import io.github.qauxv.util.xpcompat.XposedHelpers
import sumicya.qself.hostapi.chat.TroopMuteApi
import java.lang.reflect.Method

/**
 * Batch-5 adapter: owns every volatile detail of the two interception sites.
 *
 *  - MessageInfo resolution (direct load, or the type of MessageRecord's
 *    `mMessageInfo` field as the legacy fallback);
 *  - the `int (QQAppInterface, boolean, String)` trait walk;
 *  - the at-all message type constant;
 *  - MessageForQQWalletMsg.doParse field names (istroop/frienduin/isread).
 */
object TroopMuteAdapter : TroopMuteApi {

    /** @author qiwu */
    private const val AT_ALL_TYPE = 13

    private const val AT_ALL_HOOK_PRIORITY = 60
    private const val RED_PACKET_HOOK_PRIORITY = 98

    /** The pure trait from the original loop: int (AppInterface, boolean, String). */
    fun matchesAtAllClassifierTrait(m: Method, appInterface: Class<*>): Boolean {
        if (m.returnType != Int::class.javaPrimitiveType) {
            return false
        }
        val argt = m.parameterTypes
        if (argt.size != 3) {
            return false
        }
        return argt[0] == appInterface && argt[1] == java.lang.Boolean.TYPE && argt[2] == String::class.java
    }

    /**
     * Comma-separated mute-list membership. The comma wrapping is the point:
     * a bare `contains` would false-match substrings (troop "123" inside
     * "1234"). A null/absent list renders as ",null," and matches nothing —
     * bug-for-bug identical to the original Java string concatenation.
     */
    fun isTroopInMutedList(rawList: String?, troopUin: String): Boolean {
        val muted = ",$rawList,"
        return muted.contains(",$troopUin,")
    }

    override fun installAtAllMute(
        classLoader: ClassLoader,
        isEnabled: () -> Boolean,
        isMuted: (troopUin: String) -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean {
        val appInterface = Initiator._QQAppInterface() ?: return false
        val clMessageInfo = Initiator.load("com.tencent.mobileqq.troop.data.MessageInfo")
            ?: runCatching {
                Initiator._MessageRecord().getDeclaredField("mMessageInfo").type
            }.getOrNull()
            ?: return false
        for (m in clMessageInfo.declaredMethods) {
            if (matchesAtAllClassifierTrait(m, appInterface)) {
                XposedBridge.hookMethod(m, object : XC_MethodHook(AT_ALL_HOOK_PRIORITY) {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            if (!isEnabled() || LicenseStatus.sDisableCommonHooks) {
                                return
                            }
                            val ret = param.result as Int
                            if (ret != AT_ALL_TYPE) {
                                return
                            }
                            val troopUin = param.args[2] as String
                            if (isMuted(troopUin)) {
                                param.result = 0
                            }
                        } catch (e: Throwable) {
                            onError(e)
                            throw e
                        }
                    }
                })
                return true
            }
        }
        return false
    }

    override fun installRedPacketMute(
        classLoader: ClassLoader,
        isMuted: (troopUin: String) -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean {
        val clWalletMsg = Initiator.load("com.tencent.mobileqq.data.MessageForQQWalletMsg")
            ?: return false
        XposedHelpers.findAndHookMethod(
            clWalletMsg, "doParse",
            object : XC_MethodHook(RED_PACKET_HOOK_PRIORITY) {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    if (LicenseStatus.sDisableCommonHooks) {
                        return
                    }
                    try {
                        // `as Int` on a null field reproduces the original unboxing NPE
                        val istroop = Reflex.getInstanceObjectOrNull(param.thisObject, "istroop") as Int
                        if (istroop != 1) {
                            return
                        }
                        val troopUin = Reflex.getInstanceObjectOrNull(param.thisObject, "frienduin") as String
                        if (isMuted(troopUin)) {
                            XposedHelpers.setObjectField(param.thisObject, "isread", true)
                        }
                    } catch (e: Throwable) {
                        onError(e)
                        throw e
                    }
                }
            },
        )
        return true
    }
}
