/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2023 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xiaoniu.hook

import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.dexkit.DexKit
import io.github.qauxv.util.dexkit.QQSettingMeABTestHelper_isV9ExpGroup
import xyz.nextalone.util.throwOrTrue

@FunctionHookEntry
@UiItemAgentEntry
object DisableQQSettingMeV9 : CommonSwitchFunctionHook(targets = arrayOf(QQSettingMeABTestHelper_isV9ExpGroup)) {

    override val name = "禁止新样式侧滑栏"

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Simplify.SLIDING_UI

    //override val isAvailable: Boolean get() = requireMinQQVersion(QQVersion.QQ_8_9_23)
    //未知哪个版本开始添加的

    override fun initOnce() = throwOrTrue {
        DexKit.requireMethodFromCache(QQSettingMeABTestHelper_isV9ExpGroup).hookReturnConstant(false)
    }
}
