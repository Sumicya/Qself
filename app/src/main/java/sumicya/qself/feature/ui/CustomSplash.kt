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
import android.view.View
import cc.ioctl.fragment.CustomSplashConfigFragment
import io.github.qauxv.activity.SettingsUiFragmentHostActivity
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.dsl.FunctionEntryRouter.Locations.Simplify
import io.github.qauxv.hook.CommonConfigFunctionHook
import io.github.qauxv.util.hostInfo
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.IoUtils
import kotlinx.coroutines.flow.MutableStateFlow
import sumicya.qself.adapter.ui.SplashOverrideAdapter
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState
import sumicya.qself.hostapi.ui.SplashOverrideApi
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 自定义启动图 — RFC-03 §13, the config + file-supply form.
 *
 * The feature owns config keys, the qa_misc file layout and the day/night
 * resolution policy; every hook-site detail lives in SplashOverrideAdapter.
 * The config fragment (CustomSplashConfigFragment) drives this object's
 * public surface: openSplashInputStream / lightSplashFile / darkSplashFile /
 * isUseCustomLightSplash / isUseDifferentDarkSplash.
 *
 * Faithful shape notes:
 *  - DIR_NANE_CONFIG_MISC keeps its historical typo: it names an existing
 *    on-disk directory (files/qa_misc);
 *  - the dark fallback chain returns the dark image when enabled, else the
 *    light image "for those who use a same splash for both";
 *  - initOnce returns true unconditionally, as the original did.
 */
@FunctionHookEntry
@UiItemAgentEntry
object CustomSplash : CommonConfigFunctionHook() {

    private const val CAPABILITY_KEY = "ui.custom_splash"

    const val DIR_NANE_CONFIG_MISC = "qa_misc"
    const val FILE_NAME_SPLASH_LIGHT = "splash_light.png"
    const val FILE_NAME_SPLASH_DARK = "splash_dark.png"

    private const val CFG_KEY_CUSTOM_LIGHT_SPLASH = "custom_light_splash"
    private const val CFG_KEY_CUSTOM_DIFFERENT_DARK_SPLASH = "custom_different_dark_splash"

    private val api: SplashOverrideApi = SplashOverrideAdapter

    private var mStateFlowStatus: MutableStateFlow<String?>? = null

    override val name: String = "自定义启动图"

    override val uiItemLocation: Array<String> = Simplify.MAIN_UI_MISC

    override val valueState: MutableStateFlow<String?>?
        get() {
            if (mStateFlowStatus == null) {
                updateStateFlow()
            }
            return mStateFlowStatus
        }

    override val onUiItemClickListener: (IUiItemAgent, Activity, View) -> Unit =
        { _, activity, _ ->
            SettingsUiFragmentHostActivity.startFragmentWithContext(
                activity, CustomSplashConfigFragment::class.java,
            )
        }

    override var isEnabled: Boolean
        get() = super.isEnabled
        set(value) {
            super.isEnabled = value
            updateStateFlow()
        }

    override fun initOnce(): Boolean {
        val installed = api.installSplashOverride(
            Initiator.getHostClassLoader(),
            resolveOverride = { _, isDark ->
                if (isDark) openSplashDarkIfOverride() else openSplashLightIfOverride()
            },
            isEnabled = { isEnabled },
            onError = { traceError(it) },
        )
        CapabilityRegistry.report(
            CAPABILITY_KEY,
            if (installed) CapabilityState.AVAILABLE else CapabilityState.ABSENT,
        )
        return true
    }

    private fun updateStateFlow() {
        val state = if (isEnabled) "已开启" else "禁用"
        if (mStateFlowStatus == null) {
            mStateFlowStatus = MutableStateFlow(state)
        } else {
            mStateFlowStatus!!.value = state
        }
    }

    fun openSplashInputStream(which: String): InputStream? {
        val f = File(
            hostInfo.application.filesDir,
            DIR_NANE_CONFIG_MISC + File.separator + which,
        )
        return if (f.exists() && f.isFile) FileInputStream(f) else null
    }

    fun openSplashLightIfOverride(): InputStream? {
        val cfg = ConfigManager.getDefaultConfig()
        return if (isEnabled && cfg.getBoolean(CFG_KEY_CUSTOM_LIGHT_SPLASH, false)) {
            openSplashInputStream(FILE_NAME_SPLASH_LIGHT)
        } else {
            null
        }
    }

    fun openSplashDarkIfOverride(): InputStream? {
        val cfg = ConfigManager.getDefaultConfig()
        if (isEnabled && cfg.getBoolean(CFG_KEY_CUSTOM_DIFFERENT_DARK_SPLASH, false)) {
            return openSplashInputStream(FILE_NAME_SPLASH_DARK)
        }
        // for those who use a same splash for both light and dark
        if (isEnabled && cfg.getBoolean(CFG_KEY_CUSTOM_LIGHT_SPLASH, false)) {
            return openSplashInputStream(FILE_NAME_SPLASH_LIGHT)
        }
        return null
    }

    val lightSplashFile: File
        get() = File(
            IoUtils.mkdirsOrThrow(File(hostInfo.application.filesDir, DIR_NANE_CONFIG_MISC)),
            FILE_NAME_SPLASH_LIGHT,
        )

    val darkSplashFile: File
        get() = File(
            IoUtils.mkdirsOrThrow(File(hostInfo.application.filesDir, DIR_NANE_CONFIG_MISC)),
            FILE_NAME_SPLASH_DARK,
        )

    var isUseCustomLightSplash: Boolean
        get() = ConfigManager.getDefaultConfig().getBoolean(CFG_KEY_CUSTOM_LIGHT_SPLASH, false)
        set(use) {
            ConfigManager.getDefaultConfig().putBoolean(CFG_KEY_CUSTOM_LIGHT_SPLASH, use)
        }

    var isUseDifferentDarkSplash: Boolean
        get() = ConfigManager.getDefaultConfig().getBoolean(CFG_KEY_CUSTOM_DIFFERENT_DARK_SPLASH, false)
        set(use) {
            ConfigManager.getDefaultConfig().putBoolean(CFG_KEY_CUSTOM_DIFFERENT_DARK_SPLASH, use)
        }
}
