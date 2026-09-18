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

/**
 * The settings-home dashboard: header (search + centered title), one accordion
 * card per feature section, one management card, one about row. All navigation
 * goes through the [dispatch] callback supplied by SettingsMainFragment.
 */
class SettingsHomeView(context: Context) : LinearLayout(context) {

    data class State(val hostLabel: String, val diagnosticEnabled: Boolean, val safeMode: Boolean = false)

    private lateinit var palette: SettingsVisuals.Palette
    private val expandedSections = linkedSetOf<String>()
    private var boundState: State? = null
    private var boundMode: Int = -1
    private var boundCompact: Boolean? = null
    private var availableWidth = 0
    private var rebindPosted = false
    private var dispatch: (String) -> Unit = { }

    init {
        orientation = VERTICAL
        setPadding(dp(SettingsVisuals.SCREEN_SIDE), dp(6), dp(SettingsVisuals.SCREEN_SIDE), dp(24))
    }

    private fun dp(value: Int) = SettingsVisuals.dp(context, value)

    private fun compact(): Boolean =
        (if (availableWidth > 0) availableWidth / resources.displayMetrics.density
        else resources.configuration.screenWidthDp.toFloat()) < 360 ||
            resources.configuration.fontScale >= 1.35f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        if (bounded) {
            availableWidth = MeasureSpec.getSize(widthMeasureSpec)
            postRebindIfBreakpointChanged()
        }
        super.onMeasure(
            if (bounded) MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY) else widthMeasureSpec,
            heightMeasureSpec)
    }

    /**
     * A breakpoint flip must not rebuild children inside a measure pass (that
     * re-enters layout and freezes the screen). Post once, coalesce repeats.
     */
    private fun postRebindIfBreakpointChanged() {
        val state = boundState ?: return
        if (boundCompact == compact() || rebindPosted) return
        rebindPosted = true
        post {
            rebindPosted = false
            if (isAttachedToWindow && boundState === state) {
                boundState = null
                bind(state, boundMode, dispatch)
            }
        }
    }

    fun bind(state: State, mode: Int, action: (String) -> Unit) {
        dispatch = action
        val compact = compact()
        if (boundState == state && boundMode == mode && boundCompact == compact) return
        boundState = state
        boundMode = mode
        boundCompact = compact
        palette = SettingsVisuals.palette(context, mode)
        removeAllViews()
        // Probe: a rebind tears down and rebuilds the whole dashboard; the
        // journal shows when and in which breakpoint it happened.
        sumicya.qself.diagnostics.FeatureJournal.record("UI", "home.bind",
            "compact=$compact mode=$mode sections=${HomeCatalog.sections.size}")

        addView(buildHeader(), LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        addView(hostLabel(state), lp(top = 2, bottom = 14))
        for (section in HomeCatalog.sections) {
            addView(buildSectionCard(section), lp(bottom = SettingsVisuals.CARD_GAP))
        }
        addView(buildManagementCard(state), lp(bottom = SettingsVisuals.CARD_GAP))
        addView(buildAboutRow(), lp(top = 4))
    }

    /* ------------------------------------------------------------- header */

    private fun buildHeader(): View = FrameLayout(context).apply {
        addView(text("Qself", 22, palette.text, medium = true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // Search opens the global search overlay (SettingsMainFragment). The
        // old trailing gear duplicated the theme row below and was removed.
        addView(actionIcon(io.github.qauxv.R.drawable.ic_search_baseline, HomeCatalog.SEARCH, "搜索功能"),
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
    }

    private fun hostLabel(state: State): TextView =
        text(state.hostLabel + if (state.safeMode) " · 安全模式" else "", 12, palette.secondary)

    /* ------------------------------------------------------------ sections */

    private fun buildSectionCard(section: HomeCatalog.Section): SettingsAccordion =
        SettingsAccordion(context, section.title, section.summary) {
            InlineFeatureList(context, home = section.id)
        }.apply {
            header.tag = section.id
            onExpandedChanged = { isOpen ->
                if (isOpen) expandedSections.add(section.id) else expandedSections.remove(section.id)
            }
            setExpanded(section.id in expandedSections)
        }

    /* ---------------------------------------------------------- management */

    private fun buildManagementCard(state: State): View = card {
        navRow("功能开关与错误记录",
            if (state.diagnosticEnabled) "本地功能记录 · 探针日志 · 可复制清空（记录已开）"
            else "本地功能记录 · 探针日志 · 可复制清空",
            HomeCatalog.DIAGNOSTICS)
        navRow("主题与显示", "Material 3 Expressive · 玻璃与外观", HomeCatalog.THEME)
        navRow("备份与恢复", "保留你的配置，放心调整", HomeCatalog.BACKUP)
        navRow("功能与设置", "按场景合并，子项独立选择", HomeCatalog.CATALOG)
    }

    private fun buildAboutRow(): TextView =
        text("QSELF · 关于与隐私", 11, palette.secondary).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
        }.also { makeButton(it, HomeCatalog.ABOUT, "关于与隐私", filled = false) }

    /* ---------------------------------------------------------- primitives */

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

    private fun actionIcon(res: Int, action: String, label: String): View =
        ImageView(context).apply {
            tag = action
            setImageResource(res)
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(palette.secondary)
            contentDescription = label
            isFocusable = true
            setOnClickListener { InlineSettings.anchor(this); dispatch(action) }
            exposeAsButton(this)
        }

    /** Uniform click target: named, focusable, >=48dp, announced as a button. */
    private fun makeButton(view: View, id: String, label: String, filled: Boolean = false) {
        view.tag = id
        view.contentDescription = label
        view.isFocusable = true
        view.isClickable = true
        view.minimumHeight = maxOf(view.minimumHeight, dp(48))
        if (filled) {
            view.background = SettingsVisuals.surface(context, palette, SettingsVisuals.CARD_RADIUS, true, view)
        }
        // Rows inside an already-rounded card use a bounded state layer.
        if (view is TitleValueCell) {
            view.foreground = SettingsVisuals.rowStateLayer(context, palette)
        }
        view.setOnClickListener { InlineSettings.anchor(view); dispatch(id) }
        exposeAsButton(view)
    }

    private fun exposeAsButton(view: View) {
        view.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        if (view is ViewGroup) hideDecorativeChildren(view)
    }

    private fun hideDecorativeChildren(parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            child.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            if (child is ViewGroup) hideDecorativeChildren(child)
        }
    }

    private fun text(value: String, size: Int, color: Int, medium: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = size.toFloat() // SP: follows the user's font scale.
            setTextColor(color)
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            includeFontPadding = false
        }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    /* ----------------------------------------------------------- lifecycle */

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        io.github.qauxv.dsl.item.UiAgentItem.findActivity(context)?.let { InlineSettings.register(it, this) }
    }

    override fun onDetachedFromWindow() {
        rebindPosted = false
        io.github.qauxv.dsl.item.UiAgentItem.findActivity(context)?.let { InlineSettings.unregister(it) }
        super.onDetachedFromWindow()
    }

    /* ---------------------------------------------------------- saved state */

    public override fun onSaveInstanceState(): android.os.Parcelable =
        SavedState(super.onSaveInstanceState()).apply { opened = expandedSections.toTypedArray() }

    public override fun onRestoreInstanceState(state: android.os.Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        expandedSections.clear()
        expandedSections.addAll(state.opened)
        val previous = boundState
        boundState = null // force rebind so the restored expansions apply
        previous?.let { bind(it, boundMode, dispatch) }
    }

    class SavedState : BaseSavedState {
        var opened = emptyArray<String>()

        constructor(state: android.os.Parcelable?) : super(state)
        constructor(parcel: android.os.Parcel) : super(parcel) {
            opened = parcel.createStringArray() ?: emptyArray()
        }

        override fun writeToParcel(out: android.os.Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeStringArray(opened)
        }

        companion object {
            @JvmField
            val CREATOR = object : android.os.Parcelable.Creator<SavedState> {
                override fun createFromParcel(parcel: android.os.Parcel) = SavedState(parcel)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }
}
