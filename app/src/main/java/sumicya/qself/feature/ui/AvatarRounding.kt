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

import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.QQVersion
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
 * whatever the obfuscated avatar view is called this build. Radius is a
 * knob (config key rq_avatar_rounding_dp, default 8, clamped 0..36).
 * v1 scope: chat bubbles; contact list / profile in v2 after census.
 */
@FunctionHookEntry
@UiItemAgentEntry
object AvatarRounding : CommonSwitchFunctionHook(
    hookKey = "rq_avatar_rounding",
), OnBubbleBuilder {

    private const val CFG_RADIUS = "rq_avatar_rounding_dp"

    private const val DEFAULT_RADIUS_DP = 8

    private const val TAG_ROUNDED = "rq_avatar_rounding_applied"

    private const val CAPABILITY_KEY = "ui.avatar_rounding"

    override val name: String = "头像圆角（聊天）"

    override val description: String =
        "聊天气泡头像裁剪为圆角矩形。圆角半径可调（配置键 $CFG_RADIUS，默认 ${DEFAULT_RADIUS_DP}dp，0~36）。重启生效"

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Simplify.UI_CHAT_MSG

    override val isAvailable: Boolean
        get() = requireMinQQVersion(QQVersion.QQ_9_1_50)

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
        if (avatar.getTag(avatar.id) == TAG_ROUNDED && avatar.clipToOutline) return
        val density = avatar.resources.displayMetrics.density
        val dp = radiusDp()
        if (dp <= 0) return
        val radiusPx = dp * density
        avatar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        avatar.clipToOutline = true
        avatar.setTag(avatar.id, TAG_ROUNDED)
    }

    private fun radiusDp(): Int {
        val v = ConfigManager.getDefaultConfig().getIntOrDefault(CFG_RADIUS, DEFAULT_RADIUS_DP)
        return v.coerceIn(0, 36)
    }
}
