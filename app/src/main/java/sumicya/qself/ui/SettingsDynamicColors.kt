/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.os.Build

/** Android's wallpaper palette. Do not gate ColorOS behind Material's OEM allow-list. */
object SettingsDynamicColors {
    const val KEY = "qself.settings.system_colors"
    val followsSystem: Boolean get() = runCatching {
        io.github.qauxv.config.ConfigManager.getDefaultConfig().getBooleanOrDefault(KEY, true)
    }.getOrDefault(true)
    fun signature(context: Context): Int = runCatching {
        if (Build.VERSION.SDK_INT < 31 || !followsSystem) 0 else
            31 * context.getColor(android.R.color.system_accent1_500) + context.getColor(android.R.color.system_neutral1_900) +
                context.resources.configuration.uiMode
    }.getOrDefault(0)
    @JvmStatic fun apply(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31 || !followsSystem) return false
        return runCatching {
            // Standard framework resources exist on API 31+, even if an OEM supplies a fixed palette.
            context.resources.getColor(android.R.color.system_accent1_500, context.theme)
            context.theme.applyStyle(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight, true)
            true
        }.getOrDefault(false)
    }
}
