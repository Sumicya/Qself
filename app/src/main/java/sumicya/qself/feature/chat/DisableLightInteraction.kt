/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.chat

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.dexkit.DisableLightInteractionMethod
import io.github.qauxv.util.requireMinQQVersion
import sumicya.qself.adapter.chat.LightInteractionAdapter
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState
import sumicya.qself.hostapi.chat.LightInteractionApi

/**
 * 禁用轻互动 — RFC-03 §7 batch-1.
 *
 * Feature holds naming/description/search and degradation orchestration;
 * which kernel's config source to blank (and with which blank value) is
 * adapter knowledge handed over as a sealed handle.
 */
@FunctionHookEntry
@UiItemAgentEntry
object DisableLightInteraction : CommonSwitchFunctionHook(
    hookKey = "DisableLightInteraction",
    targets = arrayOf(DisableLightInteractionMethod),
) {

    private const val CAPABILITY_KEY = "chat.light_interaction"

    private val api: LightInteractionApi = LightInteractionAdapter

    override val name: String = "禁用轻互动"

    override val description: String =
        "隐藏聊天列表有时出现的表情 (早上好, 戳一戳, 晚安) 点一下发一条消息然后消失"

    override val extraSearchKeywords: Array<String> =
        arrayOf("开始全新的一天，早上好啊", "戳一戳，看看他在干嘛", "夜深了，和他道一声晚安吧")

    override val uiItemLocation: Array<String> =
        FunctionEntryRouter.Locations.Simplify.MAIN_UI_MSG_LIST

    override val isAvailable: Boolean
        get() = requireMinQQVersion(QQVersion.QQ_8_9_78)

    override fun initOnce(): Boolean {
        val handle = api.resolveConfigSource(Initiator.getHostClassLoader())
        if (handle == null) {
            CapabilityRegistry.report(
                CAPABILITY_KEY, CapabilityState.ABSENT,
                ClassNotFoundException("light interaction config source not resolvable"),
            )
            Log.e("$CAPABILITY_KEY: host entry not found, feature self-disabled")
            return false
        }
        val installed = api.installBlank(
            handle,
            isEnabled = { isEnabled },
            onError = { traceError(it) },
        )
        CapabilityRegistry.report(
            CAPABILITY_KEY,
            if (installed) CapabilityState.AVAILABLE else CapabilityState.DEGRADED,
        )
        return installed
    }
}
