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
package sumicya.qself.feature.screenshot

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter.Locations.Simplify
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.dexkit.CScreenShotHelper
import sumicya.qself.adapter.screenshot.ScreenshotHelperAdapter
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState
import sumicya.qself.hostapi.chat.ScreenshotHelperApi

/**
 * 屏蔽截屏分享 — RFC-03 pilot feature.
 *
 * Thin by design: lifecycle + degradation orchestration only. All volatile
 * host knowledge (DexKit/FQN resolution, hook installation) lives in the
 * adapter; the port in between stays framework-neutral.
 *
 * Config compatibility: passes the legacy hook key explicitly —
 * BaseFunctionHook derives the key from the class name, and a rename must
 * not reset the user's switch.
 */
@FunctionHookEntry
@UiItemAgentEntry
object DisableScreenshotShare : CommonSwitchFunctionHook(
    hookKey = "DisableScreenshotHelper",
    targets = arrayOf(CScreenShotHelper),
) {

    private const val CAPABILITY_KEY = "chat.screenshot_helper"

    private val api: ScreenshotHelperApi = ScreenshotHelperAdapter

    override val name: String = "屏蔽截屏分享"

    override val uiItemLocation: Array<String> = Simplify.UI_MISC

    override fun initOnce(): Boolean {
        val method = api.resolveShowMethod(Initiator.getHostClassLoader())
        if (method == null) {
            CapabilityRegistry.report(
                CAPABILITY_KEY, CapabilityState.ABSENT,
                ClassNotFoundException("screenshot helper entry not resolvable"),
            )
            Log.e("$CAPABILITY_KEY: host method not found, feature self-disabled")
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
