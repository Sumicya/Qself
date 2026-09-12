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

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireMinQQVersion
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.dsl.FunctionEntryRouter
import me.ketal.dispacher.BaseBubbleBuilderHook
import me.ketal.dispacher.OnBubbleBuilder
import me.singleneuron.data.MsgRecordData
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState

/**
 * 防撤回扩展（FunBox 风格 v1）— my-feature-set 06 B 类。
 *
 * Works on top of the stock 防撤回 (RevokeMsgHook): the revoked message is
 * retained (the recall push is swallowed), and this decorator marks the
 * retained bubble with a hint strip hugging the message - "已撤回 · 已保留" -
 * instead of relying only on the gray-tip line. The revoked-message registry
 * is populated by RevokeMsgHook at recall time; matching is by
 * (peerUid, msgSeq), the same key the gray tip's MsgRefItem uses.
 *
 * v1 is a top strip; the literal wrap-around border is phase 2 once the
 * bubble layout families on 9.2.10 are mapped.
 */
@FunctionHookEntry
@UiItemAgentEntry
object RevokeWrapHint : CommonSwitchFunctionHook(
    hookKey = "rq_revoke_wrap_hint",
), OnBubbleBuilder {

    private const val CAPABILITY_KEY = "chat.revoke_wrap_hint"

    private const val ID_MARK_LAYOUT = 0x114519
    private const val ID_MARK_TEXT = 0x11451A

    override val name: String = "防撤回消息标记"

    override val description: String =
        "配合「防撤回」使用：被撤回但保留的消息在气泡顶部显示「已撤回 · 已保留」提示条（FunBox 风格 v1）。重启生效"

    override val uiItemLocation: Array<String> =
        FunctionEntryRouter.Locations.Auxiliary.MESSAGE_CATEGORY

    override val isAvailable: Boolean
        get() = requireMinQQVersion(QQVersion.QQ_9_1_50)

    override fun initOnce(): Boolean {
        val ok = isAvailable && BaseBubbleBuilderHook.initialize()
        if (ok) {
            CapabilityRegistry.report(CAPABILITY_KEY, CapabilityState.AVAILABLE)
        }
        return ok
    }

    /** Registry key: same (peerUid, msgSeq) pair the recall path knows. */
    @JvmStatic
    fun markKey(peerUid: String?, msgSeq: Long): String = "$peerUid#$msgSeq"

    /** Pure gate for the decorator (and for tests). */
    @JvmStatic
    fun shouldMark(marked: Set<String>, peerUid: String?, msgSeq: Long): Boolean =
        marked.contains(markKey(peerUid, msgSeq))

    override fun onGetView(
        rootView: ViewGroup,
        chatMessage: MsgRecordData,
        param: XC_MethodHook.MethodHookParam,
    ) {
        // legacy (pre-NT) path not covered in v1
    }

    override fun onGetViewNt(
        rootView: ViewGroup,
        chatMessage: MsgRecord,
        param: XC_MethodHook.MethodHookParam,
    ) {
        if (!isEnabled) return
        if (!shouldMark(cc.ioctl.hook.msg.RevokeMsgHook.sRevokedMsgKeys,
                chatMessage.peerUid, chatMessage.msgSeq)) return
        if (rootView.findViewById<View>(ID_MARK_LAYOUT) != null) return
        val context = rootView.context
        val dp = context.resources.displayMetrics.density
        val strip = TextView(context).apply {
            id = ID_MARK_TEXT
            text = "已撤回 · 已保留"
            textSize = 11f
            setTextColor(Color.parseColor("#FFB366"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * dp
                setColor(0x33808080)
            }
            val hPad = Math.round(6f * dp)
            val vPad = Math.round(2f * dp)
            setPadding(hPad, vPad, hPad, vPad)
        }
        val wrapper = FrameLayout(context).apply {
            id = ID_MARK_LAYOUT
            addView(strip, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL,
            ))
        }
        val lp: ViewGroup.LayoutParams = when (rootView) {
            is ConstraintLayout -> ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
                topMargin = Math.round(2f * dp)
            }
            is RelativeLayout -> RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                addRule(RelativeLayout.CENTER_HORIZONTAL)
                topMargin = Math.round(2f * dp)
            }
            else -> FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL,
            )
        }
        rootView.addView(wrapper, 0, lp)
    }
}
