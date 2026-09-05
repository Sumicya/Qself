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
package io.github.qauxv.feature.friend

import android.app.Activity
import android.content.Context
import android.view.View
import io.github.qauxv.activity.SettingsUiFragmentHostActivity.Companion.startFragmentWithContext
import io.github.qauxv.base.ISwitchCellAgent
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.bridge.AppRuntimeHelper
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.feature.DeletionObserver
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.ExfriendManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 被删好友检测通知 Hook
 * 
 * 重构说明：
 * - 迁移至新包结构 io.github.qauxv.feature.friend
 * - 继承 CommonSwitchFunctionHook 替代 BaseFunctionHook
 * - 使用 object 单例模式
 * - 保持原有功能逻辑不变
 */
@FunctionHookEntry
@UiItemAgentEntry
object FriendDeletionNotification : CommonSwitchFunctionHook(
    defaultEnabled = true
), IUiItemAgent {

    override val uiItemAgent: IUiItemAgent = this
    
    override val runtimeErrors: List<Throwable> get() = DeletionObserver.INSTANCE.runtimeErrors
    
    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Auxiliary.FRIEND_CATEGORY
    
    override val valueState: MutableStateFlow<String?>? = null
    
    override val validator: ((IUiItemAgent) -> Boolean)? = null
    
    override val onClickListener: ((IUiItemAgent, Activity, View) -> Unit)? = null
    
    override val extraSearchKeywordProvider: ((IUiItemAgent, Context) -> Array<String>?)? = null

    override val switchProvider: ISwitchCellAgent by lazy {
        object : ISwitchCellAgent {
            override var isChecked: Boolean
                get() {
                    val uin = AppRuntimeHelper.getLongAccountUin()
                    if (uin < 10000) return false
                    val exf = ExfriendManager.get(uin)
                    return exf.isNotifyWhenDeleted
                }
                set(value) {
                    val uin = AppRuntimeHelper.getLongAccountUin()
                    if (uin < 10000) return
                    val exf = ExfriendManager.get(uin)
                    exf.isNotifyWhenDeleted = value
                }

            override val isCheckable: Boolean get() = AppRuntimeHelper.getLongAccountUin() >= 10000L
        }
    }

    @Throws(Exception::class)
    override fun initOnce(): Boolean {
        return DeletionObserver.INSTANCE.initialize()
    }

    @UiItemAgentEntry
    object ExFriendListEntry : io.github.qauxv.hook.BasePlainUiAgentItem(
        title = "历史好友",
        description = "得不到的永远在骚动，被偏爱的都有恃无恐."
    ) {
        override val onClickListener: ((IUiItemAgent, Activity, View) -> Unit) = { _, activity, _ ->
            startFragmentWithContext(
                activity,
                io.github.qauxv.fragment.ExfriendListFragment::class.java, null
            )
        }

        override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Auxiliary.FRIEND_CATEGORY
    }
}
