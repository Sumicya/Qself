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
import io.github.qauxv.util.isTim
import io.github.qauxv.util.requireMinQQVersion
import sumicya.qself.adapter.ui.ConversationTitleBarAdapter
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState
import sumicya.qself.hostapi.ui.ConversationTitleBarApi

/**
 * 屏蔽消息界面标题栏相机/小世界图标 — RFC-03 §8 batch-2.
 *
 * Feature holds availability policy and degradation only; the per-version
 * obfuscated method-name tables and the PlayQQ crop special case are
 * adapter knowledge.
 */
@FunctionHookEntry
@UiItemAgentEntry
object RemoveCameraButton : CommonSwitchFunctionHook(
    hookKey = "kr_disable_camera_button",
) {

    private const val CAPABILITY_KEY = "ui.title_camera_button"

    private val api: ConversationTitleBarApi = ConversationTitleBarAdapter

    override val name: String = "屏蔽消息界面标题栏相机/小世界图标"

    override val uiItemLocation: Array<String> =
        FunctionEntryRouter.Locations.Simplify.MAIN_UI_TITLE

    override val isAvailable: Boolean
        get() = !isTim() && !requireMinQQVersion(QQVersion.QQ_9_0_8)

    override fun initOnce(): Boolean {
        val handle = api.resolveCameraButton(Initiator.getHostClassLoader())
        if (handle == null) {
            CapabilityRegistry.report(
                CAPABILITY_KEY, CapabilityState.ABSENT,
                ClassNotFoundException("title bar camera button not resolvable"),
            )
            Log.e("$CAPABILITY_KEY: host entry not found, feature self-disabled")
            return false
        }
        val installed = api.installCameraRemove(
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
