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
object SettingsAppearanceItem : BasePlainUiAgentItem("小窗外观（已停用）",
    "模块设置页现在是原地下展开的不透明 MD3 表面，不再有窗口透明度；QQ 底栏玻璃是独立功能，点击可跳转配置。") {
    const val mode: Int = 2
    const val WINDOW_TRANSPARENCY = "qself.window.transparency"
    // Keep the old glass setting readable for migration/legacy previews; it no longer chooses this window's style.
    val overlayMode: Int get() = runCatching { ConfigManager.getDefaultConfig().getIntOrDefault(ProfileMigration.OVERLAY_GLASS, 1) }.getOrDefault(1).coerceIn(0, 2)
    val windowTransparency: Int get() = 0 // retired; settings are inline and opaque
    override val uiItemLocation = FunctionEntryRouter.Locations.ConfigCategory.THEME_CATEGORY
    override val valueState by lazy { MutableStateFlow<String?>(label) }
    /** The only state this row still owns is whether the QQ bottom-bar glass is on. */
    private val label: String
        get() = if (sumicya.qself.feature.ui.LiquidGlassBottomBar.isEnabled) "底栏玻璃已启用"
        else "底栏玻璃未启用"
    fun refreshLabel() { valueState.value = label }
    fun overlayPalette(context: Context) = SettingsVisuals.palette(context, mode)
    fun material(context: Context, owner: View): Drawable = windowMaterial(context, windowTransparency)
    fun windowMaterial(context: Context, transparency: Int): Drawable =
        SettingsVisuals.surface(context, overlayPalette(context), 28).apply {
            alpha = 255
        }
    override val onClickListener: (IUiItemAgent, Activity, View) -> Unit = { _, activity, _ -> GlassAppearanceEditor.show(activity, false) }
}
