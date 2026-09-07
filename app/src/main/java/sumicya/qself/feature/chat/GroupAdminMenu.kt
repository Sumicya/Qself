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
package sumicya.qself.feature.chat

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.bridge.ntapi.ChatTypeConstants
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.ui.CommonContextWrapper
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.Toasts
import io.github.qauxv.util.requireMinQQVersion
import io.github.qauxv.util.xpcompat.XC_MethodHook
import me.ketal.dispacher.BaseBubbleBuilderHook
import me.ketal.dispacher.OnBubbleBuilder
import me.singleneuron.data.MsgRecordData
import sumicya.qself.feature.ui.AvatarGeom
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState

/**
 * 群管理菜单（FunBox 复刻 v1a）— my-feature-set 06 B 类。
 *
 * Entry: long-press on a member's avatar in a group chat (the stock @-on-
 * long-press is displaced, per the user's spec). v1a ships the shell plus
 * the zero-API items: 标记 (local, persisted) and 查询共同群 (opens the same
 * ti.qq.com/friends/recall page the in-tree CheckCommonGroup uses, minus
 * its manual QQ-number input since the uin is known from the message).
 * The four kernel actions (群名片/撤回/禁言/踢出) are v1b behind a
 * GroupService bridge.
 */
@FunctionHookEntry
@UiItemAgentEntry
object GroupAdminMenu : CommonSwitchFunctionHook(
    hookKey = "rq_group_admin_menu",
), OnBubbleBuilder {

    private const val CAPABILITY_KEY = "chat.group_admin_menu"

    private const val CFG_MARKS = "rq_group_admin_marks"

    override val name: String = "群管理菜单"

    override val description: String =
        "群聊中长按成员头像弹出管理菜单：标记此人 / 查询共同群（v1a）。" +
            "长按头像的原生@行为将被本菜单替换。修改群名片、撤回消息、禁言和踢出为 v1b。重启生效"

    override val uiItemLocation: Array<String> =
        FunctionEntryRouter.Locations.Auxiliary.GROUP_CATEGORY

    override val isAvailable: Boolean
        get() = requireMinQQVersion(QQVersion.QQ_9_1_50)

    /** Pure: parse the persisted mark CSV into a set. */
    @JvmStatic
    fun parseMarks(raw: String?): Set<Long> =
        raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet() ?: emptySet()

    override fun initOnce(): Boolean {
        val ok = isAvailable && BaseBubbleBuilderHook.initialize()
        if (ok) {
            CapabilityRegistry.report(CAPABILITY_KEY, CapabilityState.AVAILABLE)
        }
        return ok
    }

    override fun onGetView(
        rootView: ViewGroup,
        chatMessage: MsgRecordData,
        param: XC_MethodHook.MethodHookParam,
    ) {
        // legacy (pre-NT) path not covered in v1a
    }

    override fun onGetViewNt(
        rootView: ViewGroup,
        chatMessage: MsgRecord,
        param: XC_MethodHook.MethodHookParam,
    ) {
        if (!isEnabled) return
        if (chatMessage.chatType != ChatTypeConstants.GROUP) return
        val avatar = AvatarGeom.findAvatar(rootView, 0) ?: return
        val uin = chatMessage.senderUin
        if (uin <= 0L) return
        avatar.setOnLongClickListener { v ->
            showMenu(v.context, uin)
            true
        }
    }

    private fun showMenu(context: Context, uin: Long) {
        val ctx = CommonContextWrapper.createAppCompatContext(context)
        val marked = parseMarks(ConfigManager.getDefaultConfig().getStringOrDefault(CFG_MARKS, "")).contains(uin)
        val items = arrayOf(
            if (marked) "取消标记此人" else "标记此人",
            "查询共同群",
        )
        AlertDialog.Builder(ctx)
            .setTitle("群管理菜单 · $uin")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleMark(ctx, uin)
                    1 -> openCommonGroups(ctx, uin)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun toggleMark(ctx: Context, uin: Long) {
        val cfg = ConfigManager.getDefaultConfig()
        val current = parseMarks(cfg.getStringOrDefault(CFG_MARKS, "")).toMutableSet()
        if (!current.remove(uin)) {
            current.add(uin)
        }
        cfg.putString(CFG_MARKS, current.joinToString(","))
        Toasts.info(ctx, if (uin in current) "已标记 $uin" else "已取消标记")
    }

    private fun openCommonGroups(ctx: Context, uin: Long) {
        try {
            val browser = Initiator.loadClass("com.tencent.mobileqq.activity.QQBrowserDelegationActivity")
            val intent = Intent(ctx, browser)
            intent.putExtra("fling_action_key", 2)
            intent.putExtra("fling_code_key", ctx.hashCode())
            intent.putExtra("useDefBackText", true)
            intent.putExtra("param_force_internal_browser", true)
            intent.putExtra("url", "https://ti.qq.com/friends/recall?uin=$uin")
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Log.e("GroupAdminMenu: openCommonGroups failed: $t")
            Toasts.error(ctx, "打开失败")
        }
    }
}
