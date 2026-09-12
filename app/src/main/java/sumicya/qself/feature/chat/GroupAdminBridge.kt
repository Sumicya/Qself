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

import android.content.DialogInterface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import io.github.qauxv.bridge.AppRuntimeHelper
import io.github.qauxv.util.Log
import io.github.qauxv.util.Toasts
import io.github.qauxv.util.hostInfo
import java.lang.reflect.Method

/** Group-management inventory only. Unverified write operations are deliberately disabled. */
object GroupAdminBridge {

    private const val TAG = "GroupAdminBridge"

    private var sInventoryDumped = false

    // Long::class.javaPrimitiveType is Class<Long>? - unusable in Class<*> varargs
    private val PRIM_LONG: Class<*> = java.lang.Long.TYPE
    private val PRIM_INT: Class<*> = java.lang.Integer.TYPE

    /** Pure: does this method look like an implementation of the action? */
    @JvmStatic
    fun matchesAction(m: Method, keyword: String, paramCount: Int, vararg paramTypes: Class<*>): Boolean {
        if (!m.name.lowercase().contains(keyword)) return false
        val ps = m.parameterTypes
        if (ps.size != paramCount) return false
        paramTypes.forEachIndexed { i, c ->
            val p = ps[i]
            if (c == PRIM_LONG && p != PRIM_LONG && p != java.lang.Long::class.java) return false
            if (c == String::class.java && p != String::class.java) return false
            if (c == PRIM_INT && p != PRIM_INT && p != java.lang.Integer::class.java) return false
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun kernelService(): Any? = runCatching {
        val app = AppRuntimeHelper.getAppRuntime() ?: error("AppRuntime unavailable")
        val kIKernelService = io.github.qauxv.util.Initiator
            .loadClass("com.tencent.qqnt.kernel.api.IKernelService") as Class<mqq.app.api.IRuntimeService>
        app.getRuntimeService(kIKernelService, "")
    }.onFailure { Log.e("$TAG: getKernelService failed: $it") }.getOrNull()

    private fun groupService(): Any? = runCatching {
        val kernelService = kernelService() ?: error("IKernelService unavailable")
        kernelService.javaClass.getMethod("getGroupService").invoke(kernelService)
    }.onFailure { Log.e("$TAG: getGroupService failed: $it") }.getOrNull()

    private fun msgService(): Any? = runCatching {
        val kernelService = kernelService() ?: error("IKernelService unavailable")
        kernelService.javaClass.getMethod("getMsgService").invoke(kernelService)
    }.onFailure { Log.e("$TAG: getMsgService failed: $it") }.getOrNull()

    /**
     * One-shot candidate inventory to DiagLog - the on-device answer to
     * "what are the real method names this build".
     */
    @JvmStatic
    fun dumpInventoryOnce() {
        if (sInventoryDumped) return
        sInventoryDumped = true
        val kw = listOf("shutup", "mute", "kick", "remove", "card", "revoke", "recall", "member")
        for (svc in listOfNotNull(groupService(), msgService())) {
            val svcName = svc.javaClass.interfaces.firstOrNull()?.simpleName ?: svc.javaClass.simpleName
            for (m in svc.javaClass.methods) {
                val n = m.name.lowercase()
                if (kw.any { n.contains(it) }) {
                    sumicya.qself.feature.dev.DiagLog.w(
                        "$TAG inventory $svcName: ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})")
                }
            }
        }
    }

    // Exact descriptors/permissions/callback semantics are not verified for this host.
    // Diagnostic name/shape matching MUST NOT authorize a group-management write.
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun muteMember(peerUid: String, memberUid: String, durationSec: Long): String? = null

    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun kickMember(peerUid: String, memberUid: String): String? = null

    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun setMemberCard(peerUid: String, memberUid: String, card: String): String? = null

    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun revokeMessage(msgId: Long, msgSeq: Long): String? = null

    // ---- UI: confirm dialogs wired into the v1a menu ----

    fun muteDialog(activity: android.content.Context, peerUid: String, memberUid: String, uin: Long) {
        val durations = arrayOf("10 分钟", "1 小时", "12 小时", "1 天", "解除禁言")
        val seconds = longArrayOf(600, 3600, 43200, 86400, 0)
        AlertDialog.Builder(activity)
            .setTitle("禁言 $uin")
            .setItems(durations) { _: DialogInterface, which: Int ->
                val r = muteMember(peerUid, memberUid, seconds[which])

                if (r != null) Toasts.info(activity, "已发出禁言指令") else Toasts.error(activity, "管理写操作尚未验证，当前版本已停用")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun kickDialog(activity: android.content.Context, peerUid: String, memberUid: String, uin: Long) {
        AlertDialog.Builder(activity)
            .setTitle("移出群聊")
            .setMessage("确定将 $uin 移出本群？")
            .setPositiveButton("移出") { _: DialogInterface, _: Int ->
                val r = kickMember(peerUid, memberUid)

                if (r != null) Toasts.info(activity, "已发出移出指令") else Toasts.error(activity, "管理写操作尚未验证，当前版本已停用")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun cardDialog(activity: android.content.Context, peerUid: String, memberUid: String, uin: Long) {
        val edit = EditText(activity).apply { inputType = InputType.TYPE_CLASS_TEXT }
        val pad = Math.round(16f * activity.resources.displayMetrics.density)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(activity)
            .setTitle("设置 $uin 的群名片")
            .setView(box)
            .setPositiveButton("保存") { _: DialogInterface, _: Int ->
                val r = setMemberCard(peerUid, memberUid, edit.text.toString())

                if (r != null) Toasts.info(activity, "已发出名片修改") else Toasts.error(activity, "管理写操作尚未验证，当前版本已停用")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun revokeDialog(activity: android.content.Context, msgId: Long, msgSeq: Long, uin: Long) {
        AlertDialog.Builder(activity)
            .setTitle("撤回本条消息")
            .setMessage("确定撤回 $uin 的这条消息？")
            .setPositiveButton("撤回") { _: DialogInterface, _: Int ->
                val r = revokeMessage(msgId, msgSeq)

                if (r != null) Toasts.info(activity, "已发出撤回指令") else Toasts.error(activity, "管理写操作尚未验证，当前版本已停用")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
