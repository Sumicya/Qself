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
 * 底部导航栏液态玻璃 — extension program batch A1.
 *
 * The rendering/internals subsystem is vendored under sumicya.qself.glass
 * (from liuran001/WeChat-LiquidGlass, MIT); this feature is the entry that
 * adapts it to this repository's hook framework: the libxposed module shell
 * became a static hub on XposedBridge, and the package-loaded hook wiring
 * happens here, inside the host main process.
 *
 * Faithful notes:
 *  - upstream hooks Instrumentation.callActivityOnResume and reacts only to
 *    the launcher activity; preserved;
 *  - upstream gates by process name; here the feature system already runs
 *    per-process (PROC_MAIN) so the gate is the process mask;
 *  - the WeChat host table stays in HostApp but is dormant: this module is
 *    not scoped to com.tencent.mm.
 */
@FunctionHookEntry
@UiItemAgentEntry
object LiquidGlassBottomBar : CommonSwitchFunctionHook(
    hookKey = "LiquidGlassBottomBar",
    targetProc = SyncUtils.PROC_MAIN,
) {

    private const val CAPABILITY_KEY = "ui.liquid_glass_bottom_bar"

    override val name = "底部导航栏液态玻璃"

    override val description = "iOS 26 风格液态玻璃底栏（WeChat-LiquidGlass 移植，MIT）。" +
        "玻璃亮边按实测图标边界直贴最外侧按钮（无外圈薄带）；" +
        "按钮恢复正常宽度；未读数为白色数字显示于按钮顶部居中。重启生效"

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
