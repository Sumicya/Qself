/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.ui

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireMinQQVersion
import sumicya.qself.adapter.ui.ConversationTitleBarAdapter
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState
import sumicya.qself.hostapi.ui.ConversationTitleBarApi

/**
 * 屏蔽消息界面标题栏超级QQ秀图标 — RFC-03 §8 batch-2.
 *
 * First feature migrated out of an author package (xyz.nextalone). The
 * four-generation badge layout knowledge lives in the adapter's version
 * tables; the feature keeps naming and availability policy.
 */
@FunctionHookEntry
@UiItemAgentEntry
object RemoveSuperQQShow : CommonSwitchFunctionHook(
    hookKey = "RemoveSuperQQShow",
) {

    private const val CAPABILITY_KEY = "ui.title_superqqshow"

    private val api: ConversationTitleBarApi = ConversationTitleBarAdapter

    override val name: String = "屏蔽消息界面标题栏超级QQ秀图标"

    override val uiItemLocation: Array<String> =
        FunctionEntryRouter.Locations.Simplify.MAIN_UI_TITLE

    override val isAvailable: Boolean
        get() = requireMinQQVersion(QQVersion.QQ_8_8_80)

    override fun initOnce(): Boolean {
        val handle = api.resolveSuperShowBadge(Initiator.getHostClassLoader())
        if (handle == null) {
            CapabilityRegistry.report(
                CAPABILITY_KEY, CapabilityState.ABSENT,
                ClassNotFoundException("super qqshow badge entry not resolvable"),
            )
            Log.e("$CAPABILITY_KEY: host entry not found, feature self-disabled")
            return false
        }
        val installed = api.installSuperShowRemove(
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
