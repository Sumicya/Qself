/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.BasePlainUiAgentItem
import kotlinx.coroutines.flow.MutableStateFlow
import sumicya.qself.profile.ProfileMigration

@UiItemAgentEntry
object SettingsAppearanceItem : BasePlainUiAgentItem(title = "局部弹窗玻璃",
    description = "仅本材质弹窗使用；再次打开可预览。常规页面固定使用 MD3 Expressive，不影响 QQ 底栏。") {
    private val labels = arrayOf("通透玻璃", "柔和玻璃", "实色 · 更高可读性")
    // All ordinary settings pages are opaque MD3E, independent of the old preference.
    const val mode: Int = 2
    val overlayMode: Int get() = runCatching { ConfigManager.getDefaultConfig().getIntOrDefault(ProfileMigration.OVERLAY_GLASS, 1) }.getOrDefault(1).coerceIn(0, 2)
    override val uiItemLocation = FunctionEntryRouter.Locations.ConfigCategory.THEME_CATEGORY
    override val valueState by lazy { MutableStateFlow<String?>(labels[overlayMode]) }
    override val onClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ ->
        val p = SettingsVisuals.palette(activity, overlayMode)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("弹窗材质（本窗口预览）")
            .setSingleChoiceItems(labels, overlayMode) { dialog, which ->
                ConfigManager.getDefaultConfig().putInt(ProfileMigration.OVERLAY_GLASS, which)
                valueState.value = labels[which]
                dialog.dismiss()
            }
            .setNegativeButton("关闭", null)
            .setBackground(SettingsGlass.material(activity, p, 28, null, ColorDrawable(p.surface)))
            .create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        dialog.show()
    }
}
