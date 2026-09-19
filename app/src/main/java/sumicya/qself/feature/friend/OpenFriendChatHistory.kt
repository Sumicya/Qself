/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.friend

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import sumicya.qself.ProcessKind
import sumicya.qself.R
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.ActionFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.util.HostInfoProvider

@QselfFeature(
    id = "friend.open_chat_history",
    name = "打开好友聊天记录",
    summary = "输入 QQ 号打开本地聊天记录（仅旧版 QQ）",
    category = "friend",
)
object OpenFriendChatHistory : ActionFeature {

    override val id: String = "friend.open_chat_history"
    override val name: String = "打开好友聊天记录"
    override val summary: String = "输入 QQ 号打开本地聊天记录（仅旧版 QQ）"
    override val category: FeatureCategory = FeatureCategory.FRIEND
    override val experimental: Boolean = true
    override val targetProcesses: Set<ProcessKind> = setOf(ProcessKind.MAIN)
    override val defaultEnabled: Boolean = false

    override fun onClick(context: Context) {
        val density = context.resources.displayMetrics.density
        val input = EditText(context).apply {
            textSize = 16f
            hint = "QQ 号或 uid"
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.input_uin_title)
            .setView(container)
            .setCancelable(true)
            .setPositiveButton(R.string.confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        // Rebind the positive button so a bad input keeps the dialog open.
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                ?.setOnClickListener { submit(context, input, dialog) }
        }
        dialog.show()
    }

    private fun submit(context: Context, input: EditText, dialog: AlertDialog) {
        val text = input.text.toString().trim()
        when {
            text.isEmpty() -> toast(context, R.string.input_required)
            text.toLongOrNull() != null -> {
                val uin = text.toLong()
                if (uin < 10000) {
                    toast(context, R.string.invalid_uin)
                } else {
                    startChatHistory(context, uin.toString())
                    dialog.dismiss()
                }
            }
            text.startsWith("u_") && text.length == 24 -> {
                startUidHistory(context, text)
                dialog.dismiss()
            }
            else -> toast(context, R.string.invalid_uid)
        }
    }

    private fun startChatHistory(ctx: Context, uin: String) {
        try {
            val intent = Intent()
            intent.setClassName(
                HostInfoProvider.PACKAGE_NAME_QQ,
                "com.tencent.mobileqq.activity.history.ChatHistoryActivity",
            )
            intent.putExtra("uin", uin)
            intent.putExtra("SissionUin", uin)
            intent.putExtra("uintype", 0)
            intent.putExtra("TargetTabPos", 0)
            intent.putExtra("FromType", 3011)
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            toast(ctx, R.string.start_failed)
        }
    }

    private fun startUidHistory(ctx: Context, uid: String) {
        // The NT peer-id conversion needs host APIs; v1 supports neither
        // path yet — the feature keeps the classic uin flow only.
        toast(ctx, R.string.nt_not_supported)
    }

    private fun toast(ctx: Context, resId: Int) {
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }
}
