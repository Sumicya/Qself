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
import io.github.qauxv.util.Reflex
import io.github.qauxv.util.TIMVersion
import io.github.qauxv.util.dexkit.DexKit
import io.github.qauxv.util.dexkit.Hd_GagInfoDisclosure_Method
import io.github.qauxv.util.requireMinQQVersion
import io.github.qauxv.util.requireMinTimVersion
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import sumicya.qself.hostapi.chat.GagNoticeApi
import sumicya.qself.hostapi.chat.GagNoticeApi.GagEvent
import java.lang.reflect.Method

/**
 * Adapter for troop gag notices (RFC-03 §10, domain-event form).
 *
 * Every volatile detail lives here:
 * - generation branch (DexKit Hd_GagInfoDisclosure_Method vs legacy
 *   TroopGagMgr 5-param trait);
 * - raw vMsg byte parsing (gate vMsg[4]==12, big-endian uin/time at
 *   offsets 0/6/16/20, signed-int32 fixup via `and 0xFFFFFFFFL`);
 * - legacy push-parameter extraction (field names "uin"/"gagLength").
 *
 * The pure normalization/parse functions are public for JVM pinning.
 */
object GagNoticeAdapter : GagNoticeApi {

    override fun installGagNotice(
        classLoader: ClassLoader,
        onEvent: (GagEvent) -> Unit,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean = runCatching {
        if (requireMinQQVersion(QQVersion.QQ_9_0_73)
            || requireMinTimVersion(TIMVersion.TIM_4_0_95_BETA)) {
            val method = DexKit.requireMethodFromCache(Hd_GagInfoDisclosure_Method)
            XposedBridge.hookMethod(method, object : XC_MethodHook(50) {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    try {
                        if (!isEnabled()) {
                            return
                        }
                        val msgInfo = param.args[1]
                        val vMsg = Reflex.getInstanceObjectOrNull(msgInfo, "vMsg")
                            as? ByteArray? ?: return
                        parseModernGagEvent(vMsg)?.let(onEvent)
                    } catch (e: Throwable) {
                        onError(e)
                    }
                }
            })
        } else {
            val clz = classLoader.loadClass("com.tencent.mobileqq.troop.utils.TroopGagMgr")
            val method = clz.declaredMethods.single(::matchesLegacyTrait)
            XposedBridge.hookMethod(method, object : XC_MethodHook(50) {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    try {
                        if (!isEnabled()) {
                            return
                        }
                        val pushParams = param.args[4] as List<*>
                        val victim = pushParams.firstOrNull()
                        val victimUin = Reflex.getInstanceObjectOrNull(victim, "uin") as String?
                            ?: return
                        val gagLength = Reflex.getInstanceObjectOrNull(victim, "gagLength")
                            as? Long? ?: return
                        onEvent(normalize(
                            param.args[1].toString(), param.args[2].toString(),
                            victimUin, gagLength))
                    } catch (e: Throwable) {
                        onError(e)
                    }
                }
            })
        }
        true
    }.getOrElse {
        onError(it)
        false
    }

    /** legacy trait: void x(int, long, long, long, ArrayList). */
    fun matchesLegacyTrait(method: Method): Boolean {
        val params = method.parameterTypes
        return params.size == 5
            && params[0] == Integer.TYPE
            && params[1] == java.lang.Long.TYPE
            && params[2] == java.lang.Long.TYPE
            && params[3] == java.lang.Long.TYPE
            && params[4] == ArrayList::class.java
            && method.returnType == Void.TYPE
    }

    /** map raw (op, victim, seconds) onto the two event shapes. */
    fun normalize(troopUin: String, opUin: String, victimUin: String, seconds: Long): GagEvent =
        if (victimUin == "0") {
            GagNoticeApi.AllGag(troopUin, opUin, seconds != 0L)
        } else {
            GagNoticeApi.MemberGag(troopUin, opUin, victimUin, seconds)
        }

    /** parse a modern vMsg payload; null when it is not a gag notice. */
    fun parseModernGagEvent(vMsg: ByteArray): GagEvent? {
        if (vMsg.size < 24 || vMsg[4].toInt() != 12) {
            return null
        }
        val troopUin = getLongData(vMsg, 0).toString()
        val opUin = fixSigned(getLongData(vMsg, 6)).toString()
        val victimUin = fixSigned(getLongData(vMsg, 16)).toString()
        val victimTime = getLongData(vMsg, 20)
        return normalize(troopUin, opUin, victimUin, victimTime)
    }

    private fun fixSigned(value: Long): Long = value.takeIf { it > 0 } ?: (value and 0xFFFFFFFFL)

    private fun getLongData(bArr: ByteArray, offset: Int): Long {
        return (((bArr[offset].toInt() and 255) shl 24)
            + ((bArr[offset + 1].toInt() and 255) shl 16)
            + ((bArr[offset + 2].toInt() and 255) shl 8)
            + ((bArr[offset + 3].toInt() and 255) shl 0)).toLong()
    }
}
