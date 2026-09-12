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
package xyz.nextalone.hook

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireMinQQVersion
import xyz.nextalone.base.MultiItemDelayableHook
import xyz.nextalone.util.clazz
import xyz.nextalone.util.hookBefore
import xyz.nextalone.util.method
import xyz.nextalone.util.throwOrTrue

@FunctionHookEntry
@UiItemAgentEntry
object SimplifyBottomTab : MultiItemDelayableHook("na_simplify_bottom_tab_kt") {

    override val preferenceTitle = "底栏页面与按钮数量"
    override val preferenceSummary = "勾选要隐藏的 QQ 页面，消息页始终保留。实际数量由 QQ 可用页面决定，完整重启生效。"
    override val isApplicationRestartRequired = true

    private val clzNames = mapOf(
        //"消息" to "com.tencent.mobileqq.activity.home.Conversation", //保留一个
        "联系人" to "com.tencent.mobileqq.activity.contacts.base.Contacts",
        "快闪" to "com.tencent.mobileqq.activity.flashshow.FlashShowFrame",
        "动态" to "com.tencent.mobileqq.leba.Leba",
        "空间" to "com.tencent.mobileqq.activity.leba.QzoneFrame",
        //"看点" to "com.tencent.mobileqq.kandian.biz.tab.ReadinjoyTabFrame",
        "看点" to "com.tencent.mobileqq.kandian.biz.xtab.RIJXTabFrame",
        "小世界" to "com.tencent.mobileqq.activity.qcircle.QCircleFrame",
        "频道" to "com.tencent.mobileqq.guild.mainframe.GuildMainFrame"
    )
    override val allItems = setOf<String>()
    override val defaultItems = setOf<String>()
    override var items = clzNames.keys.toMutableList()
    override val enableCustom = false

    override fun initOnce(): Boolean {
        val type = io.github.qauxv.util.Initiator.load("com.tencent.mobileqq.activity.home.impl.TabFrameControllerImpl")
            ?: error("TabFrameControllerImpl is absent")
        val methods = type.declaredMethods.filter {
            it.name == "addFrame" && it.returnType == Void.TYPE && it.parameterCount >= 2 &&
                it.parameterTypes[it.parameterCount - 2] == Class::class.java &&
                !java.lang.reflect.Modifier.isAbstract(it.modifiers)
        }
        check(methods.isNotEmpty()) { "No supported addFrame signature" }
        methods.forEach { method -> method.hookBefore(this) { param ->
            val name = (param.args[param.args.size - 2] as? Class<*>)?.name
            val item = clzNames.entries.firstOrNull { it.value == name }?.key
            if (item != null && item in activeItems) param.result = null
        } }
        return true
    }

    override val isAvailable: Boolean
        get() = requireMinQQVersion(QQVersion.QQ_8_5_0)

    override val uiItemLocation = FunctionEntryRouter.Locations.Simplify.UI_MISC
}
