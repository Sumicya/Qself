/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2026 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is an opensource software: you can redistribute it
 * and/or modify it under the terms of the General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the General Public License for more details.
 *
 * You should have received a copy of the General Public License
 * along with this software.
 * If not, see
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package io.github.qauxv.fragment

import android.app.Activity
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.jaredrummler.android.colorpicker.ColorShape
import com.jaredrummler.android.colorpicker.R
import io.github.qauxv.activity.SettingsUiFragmentHostActivity
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.BasePlainUiAgentItem
import io.github.qauxv.ui.ModuleThemeManager
import io.github.qauxv.util.SyncUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

@UiItemAgentEntry
object ThemeColorStyleDialog : BasePlainUiAgentItem(title = "主题颜色") {

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.ConfigCategory.THEME_CATEGORY

    override val valueState: MutableStateFlow<String?> by lazy {
        MutableStateFlow(if (sumicya.qself.ui.SettingsDynamicColors.followsSystem && android.os.Build.VERSION.SDK_INT >= 31) "跟随系统壁纸" else ModuleThemeManager.getCurrentThemeColorName())
    }

    override val onClickListener: ((IUiItemAgent, Activity, View) -> Unit) = { _, activity, _ ->
        sumicya.qself.ui.InlineAlertDialogBuilder(activity)
            .setTitle("设置页配色来源")
            .setItems(arrayOf("系统壁纸动态配色", "手动主题色")) { _, which ->
                if (which == 0 && android.os.Build.VERSION.SDK_INT >= 31) {
                    io.github.qauxv.config.ConfigManager.getDefaultConfig().putBoolean(sumicya.qself.ui.SettingsDynamicColors.KEY, true)
                    valueState.value = "跟随系统壁纸"
                    activity.recreate()
                } else if (which == 0) {
                    android.widget.Toast.makeText(activity, "系统动态配色需要 Android 12 或更高版本", android.widget.Toast.LENGTH_SHORT).show()
                } else showSelectDialog(activity)
            }.setNegativeButton("取消", null).show()
    }

    private fun showSelectDialog(activity: Activity) {
        val colors = ModuleThemeManager.getThemeColors(activity)
        sumicya.qself.ui.InlineAlertDialogBuilder(activity)
            .setTitle("手动主题色")
            .setItems(colors.map { String.format("#%06X", it and 0xFFFFFF) }.toTypedArray()) { _, which ->
                updateThemeColor(activity, colors[which])
            }.setNegativeButton("取消", null).show()
    }

    private fun updateThemeColor(activity: Activity, color: Int) {
        io.github.qauxv.config.ConfigManager.getDefaultConfig().putBoolean(sumicya.qself.ui.SettingsDynamicColors.KEY, false)
        ModuleThemeManager.setCurrentThemeColor(activity, color)
        valueState.update { ModuleThemeManager.getCurrentThemeColorName() }
        if (activity is SettingsUiFragmentHostActivity) {
            // refresh ui, wait we are finished
            SyncUtils.postDelayed(100) {
                activity.recreate()
            }
        }
    }
}
