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
 * 群日志记录（灰字）— feature-ised from the v2 debug tool (my-feature-set 06).
 * Records to the rolling GroupLogStore; click the settings entry to view.
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
        "com.tencent.qqnt.kernel.nativeinterface.GrayTipElement",
        "com.tencent.qqnt.kernelpublic.nativeinterface.GrayTipElement",
    )

    /** Content filter for the JSONObject(String) hook: gray-tip grammar only. */
    private fun looksLikeGrayTipJson(s: String): Boolean {
        if (s.length > 2000 || s.length < 10) {
            return false
        }
        return s.contains("\"jp\"") || s.contains("busi_id") ||
            (s.contains("\"txt\"") && s.contains("\"type\""))
    }

    override val name = "群日志记录（灰字）"

    override val description = "记录灰字提示（元素构造器 + 灰字 JSON 过滤）到本地滚动日志；" +
        "点击本条目查看最近记录，对话框内可清空。v2：接收型灰字由 native 反序列化不走 Java 构造器，" +
        "故同时监听 JSON 解析。重启生效"

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.MESSAGE_CATEGORY

    private const val CAPABILITY_KEY = "chat.group_log"

    override val onUiItemClickListener: (io.github.qauxv.base.IUiItemAgent, android.app.Activity, android.view.View) -> Unit =
        { _, activity, _ ->
            val ctx = io.github.qauxv.ui.CommonContextWrapper.createAppCompatContext(activity)
            val entries = sumicya.qself.feature.chat.GroupLogStore.readTail(200)
            io.github.qauxv.ui.CustomDialog.createFailsafe(ctx)
                .setTitle("群日志（最近 200 条，新在上）")
                .setMessage(if (entries.isEmpty()) "暂无记录" else entries.joinToString("\n"))
                .setPositiveButton("清空") { _, _ ->
                    sumicya.qself.feature.chat.GroupLogStore.clear()
                    io.github.qauxv.util.Toasts.info(ctx, "已清空")
                }
                .setNegativeButton("关闭", null)
                .show()
                .apply {
                    findViewById<android.widget.TextView>(android.R.id.message)?.setTextIsSelectable(true)
                }
        }

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
            Log.w("$TAG: no element classes found on this host (${hostInfo.versionName})")
        } else {
            Log.i("$TAG: hooked $hooked element constructors")
        }
        // received tips are JNI-deserialized (no Java ctor fires), so also
        // watch the JSON parse itself, filtered to gray-tip grammar
        try {
            val jsonCtor = org.json.JSONObject::class.java
                .getConstructor(String::class.java)
            XposedBridge.hookMethod(jsonCtor, object : XC_MethodHook(50) {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    if (!isEnabled) {
                        return
                    }
                    val s = param.args[0] as? String ?: return
                    if (looksLikeGrayTipJson(s)) {
                        Log.i("$TAG: json: $s")
                        sumicya.qself.feature.chat.GroupLogStore.append("json", s)
                    }
                }
            })
            hooked++
        } catch (t: Throwable) {
            Log.w("$TAG: JSONObject hook failed: $t")
        }
        if (hooked > 0) {
            sumicya.qself.hostapi.CapabilityRegistry.report(
                CAPABILITY_KEY, sumicya.qself.hostapi.CapabilityState.AVAILABLE)
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
        sumicya.qself.feature.chat.GroupLogStore.append("ctor", sb.toString())
    }
}
