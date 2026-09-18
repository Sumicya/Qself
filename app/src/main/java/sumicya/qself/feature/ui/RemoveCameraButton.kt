/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.ui

import android.view.View
import sumicya.qself.ProcessKind
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.SwitchFeature
import sumicya.qself.log.QLog
import sumicya.qself.util.HostInfo
import sumicya.qself.util.QQVersion
import sumicya.qself.xp.Hooks

@QselfFeature(
    id = "ui.remove_camera_button",
    name = "屏蔽标题栏相机按钮",
    summary = "移除聊天标题栏的相机 / 小世界图标",
    category = "ui",
)
object RemoveCameraButton : SwitchFeature() {

    override val id: String = "ui.remove_camera_button"
    override val name: String = "屏蔽标题栏相机按钮"
    override val summary: String = "移除聊天标题栏的相机 / 小世界图标"
    override val category: FeatureCategory = FeatureCategory.UI
    override val experimental: Boolean = false
    override val targetProcesses: Set<ProcessKind> = setOf(ProcessKind.MAIN)
    override val defaultEnabled: Boolean = false

    override fun initOnce(ctx: FeatureContext): Boolean {
        val hostInfo: HostInfo = ctx.hostInfo
        if (hostInfo.isTim || hostInfo.isAtLeast(QQVersion.QQ_9_0_8)) {
            QLog.d("RemoveCameraButton", "not available for this host version")
            return false
        }
        val host = ctx.host
        val cls = host.resolveSynthetic(
            "com.tencent.mobileqq.activity.ConversationTitleBtnCtrl",
            1, 2, 4, 5, 6,
        ) ?: return false

        // camera button entry: void f(View)
        val withView = when {
            hostInfo.isAtLeast(QQVersion.QQ_8_9_63_BETA_11345) -> "D"
            hostInfo.isAtLeast(QQVersion.QQ_8_9_10) -> "C"
            hostInfo.isAtLeast(QQVersion.QQ_8_8_93) -> "G"
            else -> "a"
        }
        val viewMethods = host.methods(cls, withView).filter {
            it.returnType == Void.TYPE &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == View::class.java
        }
        if (viewMethods.isEmpty()) {
            throw NoSuchMethodException("$withView(View) on ConversationTitleBtnCtrl")
        }
        viewMethods.forEach { m ->
            m.isAccessible = true
            Hooks.beforeIfEnabled(this, m) { it.skip() }
        }

        // no-arg sibling (older layout path)
        val noArg = when {
            hostInfo.isAtLeast(QQVersion.QQ_8_9_63_BETA_11345) -> "C"
            hostInfo.isAtLeast(QQVersion.QQ_8_9_10) -> "B"
            hostInfo.isAtLeast(QQVersion.QQ_8_9_5) -> "E"
            hostInfo.isAtLeast(QQVersion.QQ_8_8_93) -> "F"
            else -> "a"
        }
        host.methods(cls, noArg).filter {
            it.returnType == Void.TYPE && it.parameterTypes.isEmpty()
        }.forEach { m ->
            m.isAccessible = true
            Hooks.beforeIfEnabled(this, m) { it.skip() }
        }
        return true
    }
}
