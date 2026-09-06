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
