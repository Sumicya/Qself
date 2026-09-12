/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import io.github.qauxv.config.ConfigManager
import sumicya.qself.glass.GlassConfig
import sumicya.qself.profile.ProfileMigration

/** Draft controls: Save commits; back/cancel has no configuration side effects. */
object GlassAppearanceEditor {
    const val OVERLAY = "qself.glass.overlay."
    fun read(prefix: String, key: String, default: Int, range: IntRange): Int = runCatching {
        ConfigManager.getDefaultConfig().getIntOrDefault(prefix + key, default)
    }.getOrDefault(default).coerceIn(range)

    fun palette(context: android.content.Context, tone: Int, mode: Int): SettingsVisuals.Palette {
        val p = SettingsVisuals.palette(context, mode)
        if (tone == 0 || (tone == 2) == p.dark) return p
        val dark = tone == 2
        return p.copy(dark = dark, background = if (dark) 0xff141218.toInt() else 0xfffef7ff.toInt(),
            surface = if (dark) 0xff211f26.toInt() else 0xfff3edf7.toInt(),
            text = if (dark) 0xffe6e0e9.toInt() else 0xff1d1b20.toInt(),
            secondary = if (dark) 0xffcac4d0.toInt() else 0xff49454f.toInt(),
            container = if (dark) 0xff4a4458.toInt() else 0xffe8def8.toInt(),
            onContainer = if (dark) 0xffe8def8.toInt() else 0xff1d192b.toInt(),
            accent = SettingsVisuals.readableAccent(p.accent, dark))
    }

    fun show(activity: Activity, bar: Boolean) {
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
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 0, 24, 8) }
        val preview = TextView(activity).apply {
            text = if (bar) "仅示意材质 · 非 QQ 截图\n文字和图标不随玻璃透明度变淡" else "浮层材质预览\n常规页面仍使用不透明 MD3"
            gravity = Gravity.CENTER; textSize = 14f
        }
        content.addView(preview, LinearLayout.LayoutParams(-1, SettingsVisuals.dp(activity, 100)))
        fun refresh() {
            val mode = if (draft.getValue("background") == 0) draft.getValue("material") else 2
            val p = palette(activity, draft.getValue("tone"), mode)
            val flat = ColorDrawable(if (draft.getValue("background") == 2) { if (p.dark) 0xff18243f.toInt() else 0xffe3eaff.toInt() } else p.surface)
            preview.background = SettingsGlass.material(activity, p, 20, preview, flat).apply {
                alpha = (255 * (100 - draft.getValue("transparency")) / 100f).toInt()
            }
            preview.setTextColor(SettingsVisuals.palette(activity).text)
        }
        fun label(text: String): TextView = TextView(activity).apply { this.text = text; textSize = 14f; setPadding(0, 12, 0, 0); content.addView(this) }
        fun choice(key: String, title: String, labels: Array<String>) {
            label(title)
            content.addView(Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
                setSelection(draft.getValue(key))
                minimumHeight = SettingsVisuals.dp(activity, 48)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { draft[key] = position; refresh() }
                }
            })
        }
        fun slider(key: String, title: String, range: IntRange, suffix: String) {
            val titleView = label("$title：${draft.getValue(key)}$suffix")
            content.addView(Slider(activity).apply {
                valueFrom = range.first.toFloat(); valueTo = range.last.toFloat(); stepSize = 1f; value = draft.getValue(key).toFloat()
                contentDescription = title
                addOnChangeListener { _, value, _ -> draft[key] = value.toInt(); titleView.text = "$title：${value.toInt()}$suffix"; refresh() }
            })
        }
        slider("transparency", "玻璃背景透明度", 0..100, "%")
        label("0% 保留完整材质，100% 隐去材质；不改变文字/图标。")
        choice("background", "背景", arrayOf(if (bar) "实时取景与折射" else "光学纹理（非实时取景）", "纯色底", "蓝灰色底"))
        choice("tone", "明暗", arrayOf(if (bar) "跟随 QQ" else "跟随设置主题", "浅色", "深色"))
        if (!bar) choice("material", "纹理材质", arrayOf("通透", "柔和", "实色"))
        else {
            choice("labels", "标签文字", arrayOf("显示 QQ 原标签", "隐藏文字，图标居中"))
            slider("labelSize", "标签字号", 9..18, "sp")
            choice("badges", "未读数量", arrayOf("顶部数字 · 优先精确数", "顶部数字 · 超过99显示99+", "QQ 原生徽标", "隐藏未读徽标"))
            slider("badgeSize", "顶部数字字号", 8..18, "sp")
            val count = GlassConfig.visibleTabCount.takeIf { it > 0 }?.toString() ?: "尚未检测"
            label("实际按钮数量：$count。按 QQ 当前可见页面自动均分，不创建/删除页面。\n无法获取精确数或定位时保留 QQ 原徽标，不编造数字。")
        }
        refresh()
        MaterialAlertDialogBuilder(activity).setTitle(if (bar) "底栏文字、数量与玻璃" else "浮层玻璃外观")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("保存") { _, _ ->
                val config = ConfigManager.getDefaultConfig()
                draft.forEach { (key, value) -> if (key != "material") config.putInt(prefix + key, value) }
                if (!bar) config.putInt(ProfileMigration.OVERLAY_GLASS, draft.getValue("material"))
                if (bar) GlassConfig.load(activity)
                SettingsAppearanceItem.refreshLabel()
                Toast.makeText(activity, if (bar) "返回 QQ 首页后刷新；底栏总开关需重启" else "下次打开浮层生效", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("取消", null).show()
    }
}
