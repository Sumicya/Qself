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
import io.github.qauxv.dsl.FunctionEntryRouter.Locations.Simplify
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.dexkit.TroopEnterEffect_QQNT
import io.github.qauxv.util.isTim
import sumicya.qself.adapter.chat.TroopEnterEffectAdapter
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState
import sumicya.qself.hostapi.chat.EnterEffectApi

/**
 * 屏蔽所有进场特效 — RFC-03 §7 batch-1.
 *
 * Feature holds availability policy and degradation orchestration only;
 * the NT/legacy version branch is adapter knowledge.
 */
@FunctionHookEntry
@UiItemAgentEntry
object DisableEnterEffect : CommonSwitchFunctionHook(
    hookKey = "rq_disable_enter_effect",
    targets = arrayOf(TroopEnterEffect_QQNT),
) {

    private const val CAPABILITY_KEY = "chat.enter_effect"

    private val api: EnterEffectApi = TroopEnterEffectAdapter

    override val name: String = "屏蔽所有进场特效"

    override val uiItemLocation: Array<String> = Simplify.CHAT_DECORATION

    override val isAvailable: Boolean
        get() = !isTim()

    override fun initOnce(): Boolean {
        val method = api.resolveEffectEntry(Initiator.getHostClassLoader())
        if (method == null) {
            CapabilityRegistry.report(
                CAPABILITY_KEY, CapabilityState.ABSENT,
                ClassNotFoundException("troop enter effect entry not resolvable"),
            )
            Log.e("$CAPABILITY_KEY: host entry not found, feature self-disabled")
            return false
        }
        val installed = api.installSuppressor(
            method,
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
