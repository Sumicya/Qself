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
