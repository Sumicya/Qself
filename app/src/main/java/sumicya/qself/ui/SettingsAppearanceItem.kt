/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.BasePlainUiAgentItem
import kotlinx.coroutines.flow.MutableStateFlow
import sumicya.qself.profile.ProfileMigration

@UiItemAgentEntry
object SettingsAppearanceItem : BasePlainUiAgentItem("小窗外观",
    "Material 3 Expressive 小窗，跟随系统取色；半透明背景不影响文字和开关。QQ 底栏单独配置。") {
    const val mode: Int = 2
    const val WINDOW_TRANSPARENCY = "qself.window.transparency"
    // Keep the old glass setting readable for migration/legacy previews; it no longer chooses this window's style.
    val overlayMode: Int get() = runCatching { ConfigManager.getDefaultConfig().getIntOrDefault(ProfileMigration.OVERLAY_GLASS, 1) }.getOrDefault(1).coerceIn(0, 2)
    val windowTransparency: Int get() = runCatching { ConfigManager.getDefaultConfig().getIntOrDefault(WINDOW_TRANSPARENCY, 12) }.getOrDefault(12).coerceIn(0, 35)
    override val uiItemLocation = FunctionEntryRouter.Locations.ConfigCategory.THEME_CATEGORY
    override val valueState by lazy { MutableStateFlow<String?>("MD3 · 透明度 $windowTransparency%") }
    fun refreshLabel() { valueState.value = "MD3 · 透明度 $windowTransparency%" }
    fun overlayPalette(context: Context) = SettingsVisuals.palette(context, mode)
    fun material(context: Context, owner: View): Drawable = windowMaterial(context, windowTransparency)
    fun windowMaterial(context: Context, transparency: Int): Drawable =
        SettingsVisuals.surface(context, overlayPalette(context), 28).apply {
            alpha = (255 * (100 - transparency.coerceIn(0, 35)) / 100f).toInt()
        }
    override val onClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ -> GlassAppearanceEditor.show(activity, false) }
}
