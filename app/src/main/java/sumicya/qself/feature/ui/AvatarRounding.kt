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
package sumicya.qself.feature.ui

import android.app.Activity
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.ui.CommonContextWrapper
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.Toasts
import io.github.qauxv.util.requireMinQQVersion
import io.github.qauxv.util.xpcompat.XC_MethodHook
import me.ketal.dispacher.BaseBubbleBuilderHook
import me.ketal.dispacher.OnBubbleBuilder
import me.singleneuron.data.MsgRecordData

/**
 * 头像圆角（聊天）— my-feature-set 06 B 类（自由化旋钮型）。
 *
 * Clips chat avatars to a rounded rectangle via outline clipping
 * (ViewOutlineProvider + clipToOutline) - class-agnostic, so it works
 * whatever the obfuscated avatar view is called this build. Tap the
 * settings entry for the radius picker (0..36dp, default 8); the value
 * is read per bubble bind, so a saved radius applies to the next drawn
 * bubble without a restart. v1 scope: chat bubbles.
 */
@FunctionHookEntry
@UiItemAgentEntry
object AvatarRounding : CommonSwitchFunctionHook(
    hookKey = "rq_avatar_rounding",
), OnBubbleBuilder {

    private const val CFG_RADIUS = "rq_avatar_rounding_dp"

    private const val DEFAULT_RADIUS_DP = 8

    private const val CAPABILITY_KEY = "ui.avatar_rounding"

    override val name: String = "头像圆角（聊天）"

    override val description: String =
        "聊天气泡头像裁剪为圆角矩形。点击条目可调半径（0~36dp，默认 ${DEFAULT_RADIUS_DP}dp），" +
            "保存后对下一次绘制的气泡生效"

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Simplify.UI_CHAT_MSG

    override val isAvailable: Boolean
        get() = requireMinQQVersion(QQVersion.QQ_9_1_50)

    // CommonSwitchFunctionHook hard-codes onClickListener=null in its agent,
    // so the agent is overridden to offer the switch AND the radius picker.
    override val uiItemAgent: IUiItemAgent by lazy {
        object : IUiItemAgent {
            override val titleProvider: (io.github.qauxv.base.IEntityAgent) -> String = { _ -> name }
            override val summaryProvider: (io.github.qauxv.base.IEntityAgent, android.content.Context) -> CharSequence? =
                { _, _ -> description }
            override val valueState: kotlinx.coroutines.flow.StateFlow<String?>? = null
            override val validator: ((IUiItemAgent) -> Boolean)? = { _ -> true }
            override val switchProvider: io.github.qauxv.base.ISwitchCellAgent? =
                object : io.github.qauxv.base.ISwitchCellAgent {
                    override val isCheckable = true
                    override var isChecked: Boolean
                        get() = isEnabled
                        set(value) {
                            if (value != isEnabled) isEnabled = value
                        }
                }
            override val onClickListener: ((IUiItemAgent, Activity, View) -> Unit)? =
                { _, activity, _ -> showRadiusDialog(activity) }
            override val extraSearchKeywordProvider: ((IUiItemAgent, android.content.Context) -> Array<String>?)? = null
        }
    }

    private fun showRadiusDialog(activity: Activity) {
        val ctx = CommonContextWrapper.createAppCompatContext(activity)
        val current = radiusDp()
        val label = TextView(ctx).apply { text = "圆角半径：${current}dp" }
        val seek = SeekBar(ctx).apply { max = 36; progress = current }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = "圆角半径：${p}dp（0=关闭裁剪）"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val pad = Math.round(16f * ctx.resources.displayMetrics.density)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(seek)
        }
        fun save(dp: Int) {
            ConfigManager.getDefaultConfig().putString(CFG_RADIUS, dp.toString())
        }
        AlertDialog.Builder(ctx)
            .setTitle("头像圆角设置")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                save(seek.progress)
                Toasts.info(ctx, "已保存 ${seek.progress}dp，下个气泡生效")
            }
            .setNeutralButton("恢复默认") { _, _ ->
                save(DEFAULT_RADIUS_DP)
                Toasts.info(ctx, "已恢复 ${DEFAULT_RADIUS_DP}dp")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun initOnce(): Boolean {
        val ok = isAvailable && BaseBubbleBuilderHook.initialize()
        if (ok) {
            sumicya.qself.hostapi.CapabilityRegistry.report(
                CAPABILITY_KEY, sumicya.qself.hostapi.CapabilityState.AVAILABLE)
        }
        return ok
    }

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
        val avatar = AvatarGeom.findAvatar(rootView, 0) ?: return
        val dp = radiusDp()
        if (dp <= 0) return
        // tag carries the radius so a saved change re-clips on the next bind
        val tag = "rq_avr_$dp"
        if (avatar.getTag(avatar.id) == tag && avatar.clipToOutline) return
        val density = avatar.resources.displayMetrics.density
        val radiusPx = dp * density
        avatar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        avatar.clipToOutline = true
        avatar.setTag(avatar.id, tag)
    }

    private fun radiusDp(): Int {
        val raw = ConfigManager.getDefaultConfig()
            .getStringOrDefault(CFG_RADIUS, DEFAULT_RADIUS_DP.toString())
        return raw.trim().toIntOrNull()?.coerceIn(0, 36) ?: DEFAULT_RADIUS_DP
    }
}
