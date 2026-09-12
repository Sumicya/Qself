/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.ui

import android.app.Activity
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.requireMinQQVersion
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import sumicya.qself.glass.GlassConfig
import sumicya.qself.glass.HostApp
import sumicya.qself.glass.LiquidGlassInstaller
import sumicya.qself.glass.LiquidGlassModule
import sumicya.qself.glass.TabBarBridge
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState

/**
 * 底部导航栏液态玻璃功能入口。
 *
 * 渲染实现位于 sumicya.qself.glass；这里负责接入本仓库的 hook 框架：
 * 在宿主主进程加载 tab 切换 hook，并在启动 Activity resume 时调度安装。
 */
@FunctionHookEntry
@UiItemAgentEntry
object LiquidGlassBottomBar : CommonSwitchFunctionHook(
    hookKey = "LiquidGlassBottomBar",
    targetProc = SyncUtils.PROC_MAIN,
) {

    private const val CAPABILITY_KEY = "ui.liquid_glass_bottom_bar"

    override val name = "底部导航栏液态玻璃"

    override val description = "左侧开关启用底栏，点击说明配置文字、未读数量、透明度、背景和明暗。按钮数量跟随 QQ 实际页面。"

    override val uiItemAgent by lazy {
        val base = super.uiItemAgent
        object : io.github.qauxv.base.IUiItemAgent by base {
            override val onClickListener: (io.github.qauxv.base.IUiItemAgent, Activity, android.view.View) -> Unit =
                { _, activity, _ -> sumicya.qself.ui.GlassAppearanceEditor.show(activity, true) }
        }
    }

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY

    override val isApplicationRestartRequired = true

    override val isAvailable = requireMinQQVersion(QQVersion.QQ_9_1_50)

    override fun initOnce(): Boolean {
        LiquidGlassModule.attach(HostApp.QQ)
        // the tab-switch trigger hooks live on host classes; the resume
        // trigger is a framework method
        TabBarBridge.install(HostApp.QQ, Initiator.getHostClassLoader())
        val resume = LiquidGlassModule.resumeHookTarget()
        XposedBridge.hookMethod(resume, object : XC_MethodHook(50) {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                if (!isEnabled) {
                    return
                }
                val activity = param.args[0] as? Activity ?: return
                if (HostApp.QQ.launcherActivity == activity.javaClass.name) {
                    GlassConfig.load(activity)
                    LiquidGlassInstaller.scheduleInstall(activity)
                }
            }
        })
        CapabilityRegistry.report(CAPABILITY_KEY, CapabilityState.AVAILABLE)
        return true
    }
}
