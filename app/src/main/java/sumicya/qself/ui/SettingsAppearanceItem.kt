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
object SettingsAppearanceItem : BasePlainUiAgentItem("浮层玻璃外观",
    "真实背景取景、折射、透明度与明暗。用于分类浮层；常规页面和选项保持 MD3，QQ 底栏单独配置。") {
    private val labels = arrayOf("清透折射", "柔和折射", "实色（不取景）")
    const val mode: Int = 2
    val overlayMode: Int get() = runCatching { ConfigManager.getDefaultConfig().getIntOrDefault(ProfileMigration.OVERLAY_GLASS, 1) }.getOrDefault(1).coerceIn(0, 2)
    override val uiItemLocation = FunctionEntryRouter.Locations.ConfigCategory.THEME_CATEGORY
    override val valueState by lazy { MutableStateFlow<String?>(labels[overlayMode]) }
    fun refreshLabel() { valueState.value = labels[overlayMode] }
    fun overlayPalette(context: Context) = GlassAppearanceEditor.palette(context,
        GlassAppearanceEditor.read(GlassAppearanceEditor.OVERLAY, "tone", 0, 0..2), overlayMode)
    fun material(context: Context, owner: View): Drawable {
        val background = GlassAppearanceEditor.read(GlassAppearanceEditor.OVERLAY, "background", 0, 0..2)
        val p = overlayPalette(context).let { if (background == 0) it else it.copy(mode = 2) }
        val color = if (background == 2) p.container else p.surface
        val fallback = SettingsVisuals.surface(context, p.copy(surface = color), 28)
        return SettingsGlass.material(context, p, 28, owner, fallback).apply {
            alpha = (255 * (100 - GlassAppearanceEditor.read(GlassAppearanceEditor.OVERLAY, "transparency", 0, 0..100)) / 100f).toInt()
        }
    }
    override val onClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ -> GlassAppearanceEditor.show(activity, false) }
}
