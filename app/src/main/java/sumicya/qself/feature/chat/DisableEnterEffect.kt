/* SPDX-License-Identifier: GPL-3.0-or-later */
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
