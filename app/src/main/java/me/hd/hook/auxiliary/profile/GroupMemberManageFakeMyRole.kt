/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2026 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is an opensource software: you can redistribute it
 * and/or modify it under the terms of the General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the General Public License for more details.
 *
 * You should have received a copy of the General Public License
 * along with this software.
 * If not, see
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package me.hd.hook.auxiliary.profile

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook

@FunctionHookEntry
@UiItemAgentEntry
object GroupMemberManageFakeMyRole : CommonSwitchFunctionHook() {
    override val name = "群成员管理页伪装身份为群主"
    override val description = "已停用：群管理写操作尚未完成精确签名审计"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.DISGUISE_AND_DEVICE_CATEGORY

    // This hook fakes an elevated role and thereby exposes host-side group
    // management write actions. Until every write operation has a verified
    // exact signature, keep the capability disabled rather than relying on
    // name/parameter-shape guesses.
    override val isAvailable = false

    override fun initOnce(): Boolean {
        // Deliberately disabled until the host write methods are mapped by
        // exact, device-confirmed descriptors.
        return false
    }
}
