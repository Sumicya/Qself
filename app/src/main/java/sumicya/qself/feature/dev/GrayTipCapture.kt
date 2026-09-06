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
package sumicya.qself.feature.dev

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.hostInfo
import io.github.qauxv.util.isTim
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge

/**
 * 灰字参数捕获（开发工具）— extension program, the runtime capture path
 * (policy §8): FunBox is a packed binary, so the gray-tip knowledge we need
 * is taken from the HOST instead.
 *
 * Hooks every constructor of the concrete NT data class JsonGrayElement -
 * the one object every locally inserted json gray tip must build - and logs
 * the constructor arguments (busiId, jsonStr, recentAbstract). Trigger real
 * interactions in QQ (dice, random emoticon hints, mutes, joins...) with
 * this enabled, then read `logcat -s GrayTipCapture` to harvest the exact
 * busiId + json grammar of each tip type.
 */
@FunctionHookEntry
@UiItemAgentEntry
object GrayTipCapture : CommonSwitchFunctionHook(
    hookKey = "GrayTipCapture",
    targetProc = SyncUtils.PROC_MAIN,
) {

    private const val TAG = "GrayTipCapture"

    private val elementClasses = arrayOf(
        "com.tencent.qqnt.kernel.nativeinterface.JsonGrayElement",
        "com.tencent.qqnt.kernelpublic.nativeinterface.JsonGrayElement",
    )

    override val name = "灰字参数捕获（开发工具）"

    override val description = "记录 QQ 本地灰字提示的构造参数（busiId/JSON/摘要）到日志。" +
        "开启后正常使用 QQ（掷骰子/禁言/进群等），logcat -s GrayTipCapture 取证。仅调试用"

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY

    // dev tool: any NT host is a valid capture target
    override val isAvailable = !isTim()

    override fun initOnce(): Boolean {
        var hooked = 0
        for (name in elementClasses) {
            val clz = Initiator.load(name) ?: continue
            for (ctor in clz.declaredConstructors) {
                XposedBridge.hookMethod(ctor, object : XC_MethodHook(50) {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        if (!isEnabled) {
                            return
                        }
                        logArgs(name, param.args)
                    }
                })
                hooked++
            }
        }
        if (hooked == 0) {
            Log.w("$TAG: JsonGrayElement not found on this host (${hostInfo.versionName})")
        } else {
            Log.i("$TAG: hooked $hooked JsonGrayElement constructors")
        }
        return hooked > 0
    }

    private fun logArgs(className: String, args: Array<Any?>) {
        val sb = StringBuilder(className.substringAfterLast('.'))
        sb.append('(')
        for ((i, arg) in args.withIndex()) {
            if (i > 0) {
                sb.append(", ")
            }
            when (arg) {
                null -> sb.append("null")
                is Long, is Int, is Boolean -> sb.append(arg.toString())
                is String -> sb.append('"').append(arg).append('"')
                else -> sb.append(arg.javaClass.simpleName)
            }
        }
        sb.append(')')
        Log.i("$TAG: $sb")
    }
}
