/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.os.Build

/** Android's wallpaper palette. Do not gate ColorOS behind Material's OEM allow-list. */
object SettingsDynamicColors {
    @JvmStatic fun apply(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return runCatching {
            // Standard framework resources exist on API 31+, even if an OEM supplies a fixed palette.
            context.resources.getColor(android.R.color.system_accent1_500, context.theme)
            context.theme.applyStyle(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight, true)
            true
        }.getOrDefault(false)
    }
}
