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

/**
 * 群管理动作桥 — group-admin menu v1b (my-feature-set 06)。
 *
 * Diagnostics-first design: the exact IKernelGroupService/IMsgService method
 * names for mute/kick/card/revoke are not pinned for 9.2.10 (the repo had
 * zero troop-management surface before v1a), so every call path is
 * keyword-candidate reflection over the real service objects, and the first
 * menu open dumps the full candidate inventory to DiagLog
 * (files/qself_diag.log). The next device sweep turns those lines into
 * pinned signatures; the dispatch below already works when a candidate
 * matches by name AND parameter shape.
 */
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

    /** Invoke the first method matching keyword+shape; null if none matched or the call threw. */
    private fun dispatch(
        service: Any?,
        keyword: String,
        paramCount: Int,
        vararg shape: Class<*>,
        args: Array<Any?>,
    ): String? {
        if (service == null) return null
        val method = service.javaClass.methods.firstOrNull {
            matchesAction(it, keyword, paramCount, *shape)
        } ?: return null
        return runCatching {
            method.invoke(service, *args)
            "${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"
        }.onFailure {
            Log.e("$TAG: ${method.name} invoke failed: $it")
        }.getOrNull()
    }

    /** 禁言（秒数，0=解除）: 期望 (String peerUid, String memberUid, long duration)。 */
    @JvmStatic
    fun muteMember(peerUid: String, memberUid: String, durationSec: Long): String? =
        dispatch(
            groupService(), "shutup", 3, String::class.java, String::class.java, PRIM_LONG,
            args = arrayOf(peerUid, memberUid, durationSec),
        ) ?: dispatch(
            groupService(), "mute", 3, String::class.java, String::class.java, PRIM_LONG,
            args = arrayOf(peerUid, memberUid, durationSec),
        )

    /** 踢出: 期望 (String peerUid, String memberUid[, long/int reason])。 */
    @JvmStatic
    fun kickMember(peerUid: String, memberUid: String): String? =
        dispatch(
            groupService(), "kick", 2, String::class.java, String::class.java,
            args = arrayOf(peerUid, memberUid),
        ) ?: dispatch(
            groupService(), "removemember", 2, String::class.java, String::class.java,
            args = arrayOf(peerUid, memberUid),
        )

    /** 群名片: 期望 (String peerUid, String memberUid, String card)。 */
    @JvmStatic
    fun setMemberCard(peerUid: String, memberUid: String, card: String): String? =
        dispatch(
            groupService(), "card", 3, String::class.java, String::class.java, String::class.java,
            args = arrayOf(peerUid, memberUid, card),
        ) ?: dispatch(
            groupService(), "remark", 3, String::class.java, String::class.java, String::class.java,
            args = arrayOf(peerUid, memberUid, card),
        )

    /** 撤回: 期望 msgService.revokeMsg(…msgIds/msgSeqs…) - 尽力匹配双列表形态。 */
    @JvmStatic
    fun revokeMessage(msgId: Long, msgSeq: Long): String? {
        val svc = msgService() ?: return null
        val method = svc.javaClass.methods.firstOrNull {
            it.name.lowercase().contains("revoke") && it.parameterTypes.size >= 2
        } ?: return null
        // 只敢传原生量形态；列表形态留给装机采名后精修
        return runCatching {
            val ps = method.parameterTypes
            val args = arrayOfNulls<Any?>(ps.size)
            var idSet = false
            var seqSet = false
            for (i in ps.indices) {
                when (ps[i]) {
                    java.lang.Long.TYPE, java.lang.Long::class.java -> if (!idSet) { args[i] = msgId; idSet = true } else if (!seqSet) { args[i] = msgSeq; seqSet = true }
                    Integer.TYPE, Integer::class.java -> if (!idSet) { args[i] = msgId.toInt(); idSet = true } else if (!seqSet) { args[i] = msgSeq.toInt(); seqSet = true }
                    String::class.java -> args[i] = ""
                }
            }
            method.invoke(svc, *args)
            "${method.name}(${ps.joinToString(",") { it.simpleName }})"
        }.onFailure {
            Log.e("$TAG: revoke invoke failed: $it")
        }.getOrNull()
    }

    // ---- UI: confirm dialogs wired into the v1a menu ----

    fun muteDialog(activity: android.content.Context, peerUid: String, memberUid: String, uin: Long) {
        val durations = arrayOf("10 分钟", "1 小时", "12 小时", "1 天", "解除禁言")
        val seconds = longArrayOf(600, 3600, 43200, 86400, 0)
        AlertDialog.Builder(activity)
            .setTitle("禁言 $uin")
            .setItems(durations) { _: DialogInterface, which: Int ->
                val r = muteMember(peerUid, memberUid, seconds[which])
                sumicya.qself.feature.dev.DiagLog.w("$TAG mute uin=$uin dur=${seconds[which]} -> ${r ?: "no-match"}")
                if (r != null) Toasts.info(activity, "已发出禁言指令") else Toasts.error(activity, "未找到可用方法（已记录）")
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
                sumicya.qself.feature.dev.DiagLog.w("$TAG kick uin=$uin -> ${r ?: "no-match"}")
                if (r != null) Toasts.info(activity, "已发出移出指令") else Toasts.error(activity, "未找到可用方法（已记录）")
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
                sumicya.qself.feature.dev.DiagLog.w("$TAG card uin=$uin -> ${r ?: "no-match"}")
                if (r != null) Toasts.info(activity, "已发出名片修改") else Toasts.error(activity, "未找到可用方法（已记录）")
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
                sumicya.qself.feature.dev.DiagLog.w("$TAG revoke uin=$uin seq=$msgSeq -> ${r ?: "no-match"}")
                if (r != null) Toasts.info(activity, "已发出撤回指令") else Toasts.error(activity, "未找到可用方法（已记录）")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
