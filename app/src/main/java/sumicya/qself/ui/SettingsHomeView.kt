/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.qauxv.dsl.cell.TitleValueCell

/** Real settings-home content. Its action dispatcher is supplied by SettingsMainFragment. */
class SettingsHomeView(context: Context) : LinearLayout(context) {
    data class State(val hostLabel: String, val diagnosticEnabled: Boolean, val safeMode: Boolean = false)
    private lateinit var palette: SettingsVisuals.Palette
    private val expandedSections = linkedSetOf<String>()
    private var boundState: State? = null
    private var boundMode: Int = -1
    private var availableWidth = 0
    private var boundCompact: Boolean? = null

    private fun compact(): Boolean =
        (if (availableWidth > 0) availableWidth / resources.displayMetrics.density else resources.configuration.screenWidthDp.toFloat()) < 360 ||
            resources.configuration.fontScale >= 1.35f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        if (bounded) {
            availableWidth = MeasureSpec.getSize(widthMeasureSpec)
            // The fragment host can first measure with AT_MOST. Letting LinearLayout
            // shrink-wrap that pass collapses weighted cards and freezes short heights.
            boundState?.let { if (boundCompact != compact()) bind(it, boundMode, dispatch) }
        }
        super.onMeasure(if (bounded) MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY) else widthMeasureSpec,
            heightMeasureSpec)
    }
    private var dispatch: (String) -> Unit = { }
    private fun dp(value: Int) = SettingsVisuals.dp(context, value)

    init {
        orientation = VERTICAL
        setPadding(dp(SettingsVisuals.SCREEN_SIDE), dp(6), dp(SettingsVisuals.SCREEN_SIDE), dp(24))
    }

    fun bind(state: State, mode: Int, action: (String) -> Unit) {
        dispatch = action
        val compact = compact()
        if (boundState == state && boundMode == mode && boundCompact == compact) return
        boundCompact = compact
        boundState = state
        boundMode = mode
        palette = SettingsVisuals.palette(context, mode)
        removeAllViews()

        // ---- header: search left, centered title, theme/gear right ----
        addView(headerBar(), LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        addView(text(state.hostLabel + if (state.safeMode) " · 安全模式" else "", 12, palette.secondary),
            lp(top = 2, bottom = 14))

        // ---- section cards ----
        for (section in HomeCatalog.sections) {
            val accordion = SettingsAccordion(context, section.title, section.summary) {
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    for (group in sumicya.qself.feature.consolidation.FeatureCatalog.groupsForHome(section.id)) {
                        val row = TitleValueCell(context).apply {
                            title = group.title
                            summary = "${group.sections.sumOf { it.features.size }} 项独立设置"
                            isChevron = true
                        }
                        SettingsVisuals.decorateCardChild(row, palette, true)
                        makeButton(row, "group:${group.id}", group.title)
                        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    }
                }
            }
            accordion.header.tag = section.id
            accordion.onExpandedChanged = { expanded ->
                if (expanded) expandedSections.add(section.id) else expandedSections.remove(section.id)
            }
            accordion.setExpanded(section.id in expandedSections)
            addView(accordion, lp(bottom = SettingsVisuals.CARD_GAP))
        }

        // ---- management card: every secondary destination in one container ----
        addView(card {
            navRow("诊断与导出",
                if (state.diagnosticEnabled) "兼容性探针 · 日志 · 统一导出（记录已开）"
                else "兼容性探针 · 日志 · 统一导出", HomeCatalog.DIAGNOSTICS)
            navRow("主题与显示", "Material 3 Expressive · 玻璃与外观", HomeCatalog.THEME)
            navRow("备份与恢复", "保留你的配置，放心调整", HomeCatalog.BACKUP)
            navRow("功能与设置", "按场景合并，子项独立选择", HomeCatalog.CATALOG)
        }, lp(bottom = SettingsVisuals.CARD_GAP))

        val about = text("QSELF · 关于与隐私", 11, palette.secondary).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
        }
        makeButton(about, HomeCatalog.ABOUT, "关于与隐私", filled = false)
        addView(about, lp(top = 4))
    }

    private fun headerBar(): View = FrameLayout(context).apply {
        val title = text("Qself", 22, palette.text, true).apply {
            gravity = Gravity.CENTER
        }
        addView(title, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(icon(io.github.qauxv.R.drawable.ic_search_baseline, HomeCatalog.SEARCH, "搜索功能"),
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(icon(io.github.qauxv.R.drawable.ic_settings, HomeCatalog.THEME, "主题与显示"),
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
    }

    private fun icon(res: Int, action: String, label: String): View = ImageView(context).apply {
        tag = action
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER
        imageTintList = android.content.res.ColorStateList.valueOf(palette.secondary)
        contentDescription = label
        isFocusable = true
        setOnClickListener { InlineSettings.anchor(this); dispatch(action) }
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
    }

    private fun card(content: LinearLayout.() -> Unit): View = LinearLayout(context).apply {
        orientation = VERTICAL
        background = SettingsVisuals.surface(context, palette, SettingsVisuals.CARD_RADIUS, false)
        SettingsVisuals.clipOutline(this, SettingsVisuals.CARD_RADIUS)
        content()
    }

    private fun LinearLayout.navRow(title: String, subtitle: String, id: String) {
        val row = TitleValueCell(context).apply {
            this.title = title
            summary = subtitle
            isChevron = true
        }
        SettingsVisuals.decorateCardChild(row, palette, true)
        makeButton(row, id, "$title，$subtitle")
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun text(value: String, size: Int, color: Int, medium: Boolean = false): TextView = TextView(context).apply {
        text = value
        textSize = size.toFloat() // SP: follows the user's font size.
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun makeButton(view: View, id: String, label: String, filled: Boolean = false) {
        view.tag = id
        view.contentDescription = label
        view.isFocusable = true
        view.isClickable = true
        view.minimumHeight = maxOf(view.minimumHeight, dp(48))
        if (filled) view.background = SettingsVisuals.surface(context, palette, SettingsVisuals.CARD_RADIUS, true, view)
        // Rows living inside an already-rounded card use a bounded state layer.
        if (view is io.github.qauxv.dsl.cell.TitleValueCell) {
            view.foreground = SettingsVisuals.rowStateLayer(context, palette)
        }
        view.setOnClickListener { InlineSettings.anchor(view); dispatch(id) }
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        io.github.qauxv.dsl.item.UiAgentItem.findActivity(context)?.let { InlineSettings.register(it, this) }
    }

    override fun onDetachedFromWindow() {
        io.github.qauxv.dsl.item.UiAgentItem.findActivity(context)?.let { InlineSettings.unregister(it) }
        super.onDetachedFromWindow()
    }

    public override fun onSaveInstanceState(): android.os.Parcelable = SavedState(super.onSaveInstanceState()).apply {
        opened = expandedSections.toTypedArray()
    }
    public override fun onRestoreInstanceState(state: android.os.Parcelable?) {
        if (state !is SavedState) { super.onRestoreInstanceState(state); return }
        super.onRestoreInstanceState(state.superState)
        expandedSections.clear(); expandedSections.addAll(state.opened)
        val previous = boundState
        boundState = null
        previous?.let { bind(it, boundMode, dispatch) }
    }
    class SavedState : BaseSavedState {
        var opened = emptyArray<String>()
        constructor(state: android.os.Parcelable?) : super(state)
        constructor(parcel: android.os.Parcel) : super(parcel) { opened = parcel.createStringArray() ?: emptyArray() }
        override fun writeToParcel(out: android.os.Parcel, flags: Int) { super.writeToParcel(out, flags); out.writeStringArray(opened) }
        companion object {
            @JvmField val CREATOR = object : android.os.Parcelable.Creator<SavedState> {
                override fun createFromParcel(parcel: android.os.Parcel) = SavedState(parcel)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }

    private fun lp(top: Int = 0, bottom: Int = 0) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }
}
