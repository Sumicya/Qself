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
package sumicya.qself.feature.device

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.TIMVersion
import io.github.qauxv.util.hostInfo
import io.github.qauxv.util.isTim
import io.github.qauxv.util.requireMinQQVersion
import io.github.qauxv.util.requireMinTimVersion
import sumicya.qself.adapter.device.PadModeAdapter
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState
import sumicya.qself.hostapi.device.PadModeApi

/**
 * 强制平板模式 — RFC-03 §12, the version-table form in the device domain.
 *
 * Faithful shape notes:
 *  - the original initOnce was `throwOrTrue { ... }`: the availability gate
 *    lived INSIDE initOnce (return false when unavailable), and any install
 *    failure propagated as an exception — both kept here;
 *  - the original ezx `hookAfter` had no is-enabled guard inside the hook
 *    body; the CommonSwitchFunctionHook enabled-gate at init time is the
 *    only switch, as before;
 *  - PROC_ANY and isApplicationRestartRequired are preserved.
 */
@FunctionHookEntry
@UiItemAgentEntry
object ForcePadMode : CommonSwitchFunctionHook(targetProc = SyncUtils.PROC_ANY) {

    private const val CAPABILITY_KEY = "device.force_pad_app_id"

    private val api: PadModeApi = PadModeAdapter

    override val name = "强制平板模式"

    override val description = "支持 QQ8.9.15 及以上，未经测试，谨慎使用"

    override val extraSearchKeywords: Array<String> = arrayOf("pad")

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY

    override val isApplicationRestartRequired = true

    override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_9_15)
        || requireMinTimVersion(TIMVersion.TIM_4_0_95_BETA)

    override fun initOnce(): Boolean {
        // throwOrTrue: unavailable -> false; install failure -> exception (both traced upstream)
        if (!isAvailable) {
            return false
        }
        val installed = api.installForcePadAppId(
            Initiator.getHostClassLoader(),
            hostIsTim = isTim(),
            hostVersionCode = hostInfo.versionCode,
        )
        CapabilityRegistry.report(
            CAPABILITY_KEY,
            if (installed) CapabilityState.AVAILABLE else CapabilityState.ABSENT,
        )
        return installed
    }
}
