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

package me.hd.hook.simplify.main.ui.titile

import android.content.Context
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireMinQQVersion
import me.hd.util.hookBeforeIfEnabled
import me.hd.util.parameters
import me.hd.util.singleMethod
import me.hd.util.toHostClass

@FunctionHookEntry
@UiItemAgentEntry
object DisableChatsCardContainer : CommonSwitchFunctionHook() {
    override val name = "屏蔽聊天列表顶部卡片推荐"
    override val description = "屏蔽QQ9.0.75新增的短视频/推荐好友（9.2.x 起该组件已不存在，功能自动无操作）"
    override val uiItemLocation = FunctionEntryRouter.Locations.Simplify.MAIN_UI_TITLE
    override val isAvailable = requireMinQQVersion(QQVersion.QQ_9_0_75)

    override fun initOnce(): Boolean {
        // QQ 9.2.x removed this part class; toHostClass() then returns null and
        // the old code called getDeclaredMethods() on it - the recurring
        // CNFE+NPE pair in every boot's device log (sweep 2026-09-07). Nothing
        // to disable on those versions: succeed as a no-op instead of failing.
        val cls = "com.tencent.mobileqq.chatlist.MainChatsCardContainerPartImpl".toHostClass()
            ?: return true
        cls.singleMethod {
            parameters(Context::class.java, Boolean::class.java)
        }.hookBeforeIfEnabled(this) { param ->
            param.result = null
        }
        return true
    }
}
