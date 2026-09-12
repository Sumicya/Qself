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
package sumicya.qself.feature.consolidation

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook

/**
 * 广告净化（总开关）— dedup/consolidation pass (my-feature-set 06)。
 *
 * Orchestrator entry for the five ad-blocking features scattered across
 * four settings categories. The switch is computed from the children
 * (on = all on) and setting it writes through to every child; each child
 * stays individually toggleable in its own category. Hooks nothing itself.
 */
@FunctionHookEntry
@UiItemAgentEntry
object AdPurifySuite : CommonSwitchFunctionHook(hookKey = "rq_ad_purify_suite") {

    private val children: List<CommonSwitchFunctionHook> = listOf(
        me.hd.hook.DisableGrowHalfLayer,                                  // 屏蔽广告弹窗(测试版)
        me.hd.hook.simplify.main.ui.titile.DisableThirdContainer,          // 屏蔽悬浮广告
        me.hd.hook.simplify.main.ui.misc.RemoveCommentAd,                   // 移除评论广告
        io.github.relimus.hook.HideQZoneAD,                                  // 隐藏QQ空间广告
        cc.ioctl.hook.mini.HideMiniAppLoadingAd,                             // 隐藏小程序开屏广告
    )

    override val name: String = "广告净化（总开关）"

    override val description: String =
        "一键管理五个广告净化功能（弹窗/悬浮/评论/空间/小程序开屏）。开=全部开启，关=全部关闭；" +
            "子功能仍可在各自分类单独微调。本条目自身不挂钩"

    override val uiItemLocation: Array<String> =
        FunctionEntryRouter.Locations.Auxiliary.FAVORITE_AND_TOOLS_CATEGORY

    override var isEnabled: Boolean
        get() = children.all { it.isEnabled }
        set(value) {
            children.forEach { if (it.isEnabled != value) it.isEnabled = value }
        }

    override fun initOnce(): Boolean = true
}
