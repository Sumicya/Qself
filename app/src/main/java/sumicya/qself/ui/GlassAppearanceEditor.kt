/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import io.github.qauxv.config.ConfigManager
import sumicya.qself.glass.GlassConfig

/** Draft controls: Save commits; back/cancel has no configuration side effects. */
object GlassAppearanceEditor {
    const val OVERLAY = "qself.glass.overlay."
    fun read(prefix: String, key: String, default: Int, range: IntRange): Int = runCatching {
        ConfigManager.getDefaultConfig().getIntOrDefault(prefix + key, default)
    }.getOrDefault(default).coerceIn(range)

    fun palette(context: android.content.Context, tone: Int, mode: Int): SettingsVisuals.Palette {
        val p = SettingsVisuals.palette(context, mode)
        if (tone == 0 || (tone == 2) == p.dark) return p
        val config = android.content.res.Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (tone == 2) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
        val themed = io.github.qauxv.ui.CommonContextWrapper(context, io.github.qauxv.R.style.Theme_Qself_Expressive, config)
        SettingsDynamicColors.apply(themed)
        return SettingsVisuals.palette(themed, mode)
    }

    /** Retired settings-window editor: explain instead of returning silently (a dead tap reads as a crash). */
    private fun explainRetired(activity: Activity) {
        val message = "模块设置页已改为原地下展开的不透明 MD3 表面，不再有设置窗口透明度；QQ 底栏的液态玻璃是独立功能，可单独配置（透明度、背景、明暗、文字与未读数量）。"
        InlineAlertDialogBuilder(activity).setTitle("小窗外观已停用")
            .setMessage(message)
            .setPositiveButton("配置 QQ 底栏玻璃") { _, _ -> show(activity, true) }
            .setNegativeButton("关闭", null).show()
    }

    fun show(activity: Activity, bar: Boolean) {
        if (!bar) {
            explainRetired(activity)
            return
        }
        val prefix = if (bar) GlassConfig.PREFIX else OVERLAY
        val draft = linkedMapOf(
            "transparency" to read(prefix, "transparency", 0, 0..100),
            "background" to read(prefix, "background", 0, 0..2),
            "tone" to read(prefix, "tone", 0, 0..2),
            "labels" to read(prefix, "labels", 0, 0..1),
            "labelSize" to read(prefix, "labelSize", 12, 9..18),
            "badges" to read(prefix, "badges", 0, 0..3),
            "badgeSize" to read(prefix, "badgeSize", 10, 8..18),
            "material" to SettingsAppearanceItem.overlayMode)
        // The enable state belongs in the editor too, not only on the catalogue row's left switch.
        draft["enabled"] = if (sumicya.qself.feature.ui.LiquidGlassBottomBar.isEnabled) 1 else 0
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 8) }
        val preview = TextView(activity).apply {
            text = "背景色 / 透明度示意，非 QQ 截图\n实际折射效果返回 QQ 查看 · 无边缘高光"
            gravity = Gravity.CENTER; textSize = 14f
        }
        val sample = FrameLayout(activity).apply { addView(preview, FrameLayout.LayoutParams(-1, -1)) }
        content.addView(sample, LinearLayout.LayoutParams(-1, SettingsVisuals.dp(activity, 100)))
        fun refresh(onlyOpacity: Boolean = false) {
            if (onlyOpacity && preview.background != null) {
                preview.background.alpha = (255 * (100 - draft.getValue("transparency")) / 100f).toInt()
                return
            }
            val mode = if (draft.getValue("background") == 0) draft.getValue("material") else 2
            val p = palette(activity, draft.getValue("tone"), mode)
            val color = if (draft.getValue("background") == 2) { if (!bar) p.container else if (p.dark) 0xff18243f.toInt() else 0xffe3eaff.toInt() } else p.surface
            val flat = SettingsVisuals.surface(activity, p.copy(surface = color), 20)
            preview.background = flat.apply {
                alpha = (255 * (100 - draft.getValue("transparency")) / 100f).toInt()
            }
            sample.setBackgroundColor(p.background)
            preview.setTextColor(p.text)
        }
        fun label(text: String): TextView = TextView(activity).apply { this.text = text; textSize = 14f; setPadding(0, 12, 0, 0); content.addView(this) }
        // The enable state belongs in the editor too, not only on the catalogue row's left switch.
        // Declared after preview/refresh so the switch can drive the live sample.
        val enableSwitch = androidx.appcompat.widget.SwitchCompat(activity).apply {
            text = "启用底栏玻璃（与条目左侧开关同一设置）"
            textSize = 14f
            contentDescription = "启用底栏玻璃"
            minimumHeight = SettingsVisuals.dp(activity, 48)
            isChecked = draft.getValue("enabled") == 1
            setOnCheckedChangeListener { _, checked ->
                draft["enabled"] = if (checked) 1 else 0
                if (!checked) preview.alpha = 1f
                else refresh()
            }
        }
        content.addView(enableSwitch, 0)
        fun choice(key: String, title: String, labels: Array<String>) {
            label(title)
            content.addView(Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection(draft.getValue(key))
                minimumHeight = SettingsVisuals.dp(activity, 48)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (draft[key] != position) { draft[key] = position; refresh() } }
                }
            })
        }
        fun slider(key: String, title: String, range: IntRange, suffix: String) {
            val titleView = label("$title：${draft.getValue(key)}$suffix")
            content.addView(Slider(activity).apply {
                valueFrom = range.first.toFloat(); valueTo = range.last.toFloat(); stepSize = 1f; value = draft.getValue(key).toFloat()
                contentDescription = title
                addOnChangeListener { _, value, _ -> draft[key] = value.toInt(); titleView.text = "$title：${value.toInt()}$suffix"; if (key == "transparency") refresh(true) }
            })
        }
        if (draft.getValue("enabled") == 0) {
            label("当前未启用：保存后底栏保持 QQ 原生样式；下面的参数会保留，重新启用后继续生效。")
        }
        slider("transparency", "玻璃背景透明度", 0..100, "%")
        label("0% 保留完整材质，100% 隐去材质；不改变文字/图标。")
        choice("background", "背景", arrayOf("实际背景取景与折射", "纯色底", "蓝灰色底"))
        choice("tone", "明暗", arrayOf("跟随 QQ", "浅色", "深色"))
        run {
            choice("labels", "标签文字", arrayOf("显示 QQ 原标签", "隐藏文字，图标居中"))
            slider("labelSize", "标签字号", 9..18, "sp")
            choice("badges", "未读数量", arrayOf("顶部数字 · 优先精确数", "顶部数字 · 超过99显示99+", "QQ 原生徽标", "隐藏未读徽标"))
            slider("badgeSize", "顶部数字字号", 8..18, "sp")
            val count = GlassConfig.visibleTabCount.takeIf { it > 0 }?.toString() ?: "尚未检测"
            label("实际按钮数量：$count。宽度按当前可见页面分配；在下方选择隐藏页面，保留消息页。\n无法获取精确数或定位时保留 QQ 原徽标，不编造数字。")
            content.addView(com.google.android.material.button.MaterialButton(activity).apply {
                text = "选择底栏页面 / 调整按钮数量"
                setOnClickListener { xyz.nextalone.hook.SimplifyBottomTab.onUiItemClickListener.invoke(
                    xyz.nextalone.hook.SimplifyBottomTab.uiItemAgent, activity, this) }
            })
        }
        refresh()
        InlineAlertDialogBuilder(activity).setTitle("底栏文字、数量与玻璃")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("保存") { _, _ ->
                val config = ConfigManager.getDefaultConfig()
                // "material" belongs to the retired settings-window editor: it is read for
                // migration only and must not be written back under the bar prefix.
                draft.forEach { (key, value) ->
                    if (key != "enabled" && key != "material") config.putInt(prefix + key, value)
                }
                // The enable state is the feature's own switch, not a bar-only preference:
                // one source of truth for both the row switch and this editor.
                sumicya.qself.feature.ui.LiquidGlassBottomBar.isEnabled = draft.getValue("enabled") == 1
                GlassConfig.load(activity)
                SettingsAppearanceItem.refreshLabel()
                Toast.makeText(activity, "返回 QQ 首页后刷新；底栏总开关需重启", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("取消", null).show()
    }
}
