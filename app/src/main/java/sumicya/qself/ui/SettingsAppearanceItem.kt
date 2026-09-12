/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Activity
import android.view.View
import androidx.appcompat.app.AlertDialog
import io.github.qauxv.activity.SettingsUiFragmentHostActivity
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.BasePlainUiAgentItem
import kotlinx.coroutines.flow.MutableStateFlow

@UiItemAgentEntry
object SettingsAppearanceItem : BasePlainUiAgentItem(title = "设置页玻璃") {
    private val labels = arrayOf("通透玻璃", "柔和玻璃", "实色 · 更高可读性")
    val mode: Int get() = ConfigManager.getDefaultConfig().getIntOrDefault("qself.settings.glass", 1).coerceIn(0, 2)
    override val uiItemLocation = FunctionEntryRouter.Locations.ConfigCategory.THEME_CATEGORY
    override val valueState by lazy { MutableStateFlow<String?>(labels[mode]) }
    override val onClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ ->
        AlertDialog.Builder(activity)
            .setTitle("设置页玻璃")
            .setSingleChoiceItems(labels, mode) { dialog, which ->
                ConfigManager.getDefaultConfig().putInt("qself.settings.glass", which)
                valueState.value = labels[which]
                dialog.dismiss()
                if (activity is SettingsUiFragmentHostActivity) activity.recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
