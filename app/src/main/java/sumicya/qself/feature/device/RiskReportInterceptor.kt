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
package sumicya.qself.feature.device

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge

/**
 * 拦截风控上报（O3）— merged from the user-provided QQHook 1.4
 * (io.github.jhl337.qqhook, 12 KB module; dex string-table analysis
 * 2026-09-07). Same family as the in-tree QSecO3 mitigations, but on the
 * send side: outgoing MSF/Channel commands whose service cmd starts with
 * the risk-control prefixes are swallowed before they leave the process.
 *
 * QQHook's four hook points, preserved: MsfCore.sendMessage (falling back
 * to sendMessageInner, its own fallback note kept) and ChannelManager's
 * sendMessage overloads for the channel proxy path; the cmd is read off
 * the ToServiceMsg-like argument via getServiceCmd().
 */
@FunctionHookEntry
@UiItemAgentEntry
object RiskReportInterceptor : CommonSwitchFunctionHook(
    hookKey = "rq_risk_report_interceptor",
    targetProc = SyncUtils.PROC_MAIN or SyncUtils.PROC_MSF,
) {

    private const val TAG = "RiskReportInterceptor"

    private val BLOCK_PREFIXES = arrayOf(
        "trpc.o3.mobile_security.",
        "trpc.o3.report.",
    )

    /** Pure: should this outgoing service cmd be swallowed? */
    @JvmStatic
    fun shouldBlock(cmd: String?): Boolean =
        cmd != null && BLOCK_PREFIXES.any { cmd.startsWith(it) }

    override val name = "拦截风控上报（O3）"

    override val description = "拦截风控/安全上报指令（trpc.o3.mobile_security.* 与 trpc.o3.report.*）的发出，" +
        "合并自 QQHook 1.4。作用为主进程与 MSF 进程的发送路径。重启生效"

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.MISC_CATEGORY

    override fun initOnce(): Boolean {
        var hooked = 0

        // Path 1: MsfCore.sendMessage -> fallback sendMessageInner (QQHook's
        // own fallback order, its log line kept in spirit).
        runCatching {
            val msf = Initiator.loadClass("com.tencent.mobileqq.msf.core.MsfCore")
            val send = msf.declaredMethods.firstOrNull { it.name == "sendMessage" }
                ?: msf.declaredMethods.firstOrNull { it.name == "sendMessageInner" }
            if (send != null) {
                XposedBridge.hookMethod(send, object : XC_MethodHook(50) {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        if (!isEnabled) return
                        val cmd = findServiceCmd(param.args) ?: return
                        if (shouldBlock(cmd)) {
                            Log.i("$TAG: msf report blocked, cmd: $cmd")
                            param.result = null
                        }
                    }
                })
                hooked++
            } else {
                Log.w("$TAG: MsfCore send method not found on this host")
            }
        }.onFailure { Log.e("$TAG: hookMsfCore failed: $it") }

        // Path 2: ChannelManager.sendMessage overloads (channel proxy path).
        runCatching {
            val cm = Initiator.loadClass("com.tencent.mobileqq.channel.ChannelManager")
            for (m in cm.declaredMethods.filter { it.name == "sendMessage" }) {
                XposedBridge.hookMethod(m, object : XC_MethodHook(50) {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        if (!isEnabled) return
                        val cmd = findServiceCmd(param.args) ?: return
                        if (shouldBlock(cmd)) {
                            Log.i("$TAG: channel report blocked, cmd: $cmd")
                            param.result = null
                        }
                    }
                })
                hooked++
            }
        }.onFailure { Log.e("$TAG: hookChannelProxyExt failed: $it") }

        return hooked > 0
    }

    /** Read getServiceCmd() off whichever argument carries it. */
    private fun findServiceCmd(args: Array<Any?>): String? {
        for (a in args) {
            if (a == null) continue
            val getter = runCatching { a.javaClass.getMethod("getServiceCmd") }.getOrNull()
                ?: continue
            val cmd = runCatching { getter.invoke(a) as? String }.getOrNull()
            if (cmd != null) return cmd
        }
        return null
    }
}
