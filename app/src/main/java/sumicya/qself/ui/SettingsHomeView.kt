/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Real settings-home content. Its action dispatcher is supplied by SettingsMainFragment. */
class SettingsHomeView(context: Context) : LinearLayout(context) {
    data class State(val hostLabel: String, val diagnosticEnabled: Boolean, val safeMode: Boolean = false)
    private lateinit var palette: SettingsVisuals.Palette
    private var boundState: State? = null
    private var boundMode: Int = -1
    private var dispatch: (String) -> Unit = { }
    private fun dp(value: Int) = SettingsVisuals.dp(context, value)

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(22), dp(20), dp(28))
    }

    fun bind(state: State, mode: Int, action: (String) -> Unit) {
        dispatch = action
        if (boundState == state && boundMode == mode) return
        boundState = state
        boundMode = mode
        removeAllViews()
        palette = SettingsVisuals.palette(context, mode)
        addView(text("为你而设", 11, palette.secondary, true).apply { letterSpacing = .16f })
        addView(text("Qself", 38, palette.text, true), lp(top = 7))
        addView(text("少一点干扰，多一点自由。", 15, palette.secondary), lp(top = 6, bottom = 16))
        addView(text(state.hostLabel + if (state.safeMode) "  ·  安全模式" else "  ·  个人设置", 12, palette.accent)
            .apply { setPadding(dp(12), dp(7), dp(12), dp(7)); background = SettingsVisuals.surface(context, palette, 12) },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val search = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(text("搜索功能与设置", 15, palette.secondary), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(text("⌕", 26, palette.accent).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO })
        }
        button(search, HomeCatalog.SEARCH, "搜索功能与设置")
        addView(search, lp(top = 22, bottom = 25))

        addView(text("按你的习惯", 19, palette.text, true), lp(bottom = 12))
        val compact = resources.configuration.screenWidthDp < 360 || resources.configuration.fontScale >= 1.35f
        for (pair in HomeCatalog.sections.chunked(if (compact) 1 else 2)) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            for ((index, section) in pair.withIndex()) {
                val card = LinearLayout(context).apply {
                    orientation = VERTICAL
                    minimumHeight = dp(116)
                    setPadding(dp(18), dp(19), dp(18), dp(17))
                    addView(text(section.title, 19, palette.text, true))
                    addView(text(section.summary.replace(" · ", if (compact) " · " else "\n"), 12, palette.secondary), lp(top = 9))
                }
                button(card, section.id, "${section.title}，${section.summary}")
                row.addView(card, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(12)
                })
            }
            addView(row, lp(bottom = 12))
        }

        addView(text("观察，而不是猜测", 19, palette.text, true), lp(top = 16, bottom = 12))
        val diagnostics = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(text("上报诊断", 17, palette.text, true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            heading.addView(text(if (state.diagnosticEnabled) "记录开关已开" else "记录开关已关", 11, palette.accent)
                .apply { setPadding(dp(9), dp(6), dp(9), dp(6)); background = SettingsVisuals.surface(context, palette, 9) })
            addView(heading)
            addView(text("只读 · 本地保存 · 按需开启", 13, palette.secondary), lp(top = 8))
            addView(text("观察到调用，不等于服务器已接收。", 12, palette.secondary), lp(top = 5))
        }
        button(diagnostics, HomeCatalog.DIAGNOSTICS, "上报诊断，${if (state.diagnosticEnabled) "记录开关已开" else "记录开关已关"}，仅本地观察")
        addView(diagnostics)

        addView(text("管理你的设置", 19, palette.text, true), lp(top = 28, bottom = 12))
        utility("主题与玻璃", "深浅色、主题色与通透程度", HomeCatalog.THEME)
        utility("备份与恢复", "保留你的配置，放心调整", HomeCatalog.BACKUP)
        utility("更多设置", "原有分类、故障排查与其他项目", HomeCatalog.CATALOG)
        val about = text("QSELF  ·  关于与隐私", 11, palette.secondary).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
        }
        button(about, HomeCatalog.ABOUT, "关于与隐私", glass = false)
        addView(about, lp(top = 16))
    }

    private fun utility(title: String, subtitle: String, id: String) {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(text(title, 16, palette.text, true))
                addView(text(subtitle, 12, palette.secondary), lp(top = 5))
            }
            addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(text(if (layoutDirection == LAYOUT_DIRECTION_RTL) "‹" else "›", 24, palette.secondary)
                .apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO })
        }
        button(row, id, "$title，$subtitle")
        addView(row, lp(bottom = 9))
    }

    private fun text(value: String, size: Int, color: Int, medium: Boolean = false): TextView = TextView(context).apply {
        text = value
        textSize = size.toFloat() // SP: follows the user's font size.
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun button(view: View, id: String, label: String, glass: Boolean = true) {
        view.tag = id
        view.contentDescription = label
        view.isFocusable = true
        view.isClickable = true
        view.minimumHeight = maxOf(view.minimumHeight, dp(48))
        if (glass) view.background = SettingsVisuals.surface(context, palette, 22, true)
        view.setOnClickListener { dispatch(id) }
        view.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        fun hideDecorativeChildren(parent: ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                child.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                if (child is ViewGroup) hideDecorativeChildren(child)
            }
        }
        if (view is ViewGroup) hideDecorativeChildren(view)
    }

    private fun lp(top: Int = 0, bottom: Int = 0) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }
}
