/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.chat

import sumicya.qself.ProcessKind
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.SwitchFeature
import sumicya.qself.xp.Hooks

@QselfFeature(
    id = "chat.show_self_msg_left",
    name = "自己的消息居左显示",
    summary = "聊天窗口中自己发的消息也靠左排列",
    category = "message",
)
object ShowSelfMsgByLeft : SwitchFeature() {

    override val id: String = "chat.show_self_msg_left"
    override val name: String = "自己的消息居左显示"
    override val summary: String = "聊天窗口中自己发的消息也靠左排列"
    override val category: FeatureCategory = FeatureCategory.MESSAGE
    override val experimental: Boolean = false
    override val targetProcesses: Set<ProcessKind> = setOf(ProcessKind.MAIN)
    override val defaultEnabled: Boolean = false

    override fun initOnce(ctx: FeatureContext): Boolean {
        val host = ctx.host
        val cls = host.require("com.tencent.mobileqq.activity.aio.BaseChatItemLayout")
        val method = host.requireMethod(cls, "setHearIconPosition", Int::class.javaPrimitiveType)
        Hooks.beforeIfEnabled(this, method) { it.skip() }
        return true
    }
}
