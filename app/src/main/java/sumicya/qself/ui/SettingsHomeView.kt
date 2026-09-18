/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.qauxv.R
import io.github.qauxv.dsl.cell.TitleValueCell
import sumicya.qself.diagnostics.FeatureJournal

/**
 * The settings home dashboard: a header with the centred title and the leading
 * search icon, one accordion card per catalog section, one management card and
 * one about row.
 *
 * Three contracts hold this view together:
 *  - **one bind path** — every state change goes through [bind], which either
 *    accepts the new state or rejects it as identical, so a rebind is never
 *    done twice for the same state;
 *  - **no rebuild while measuring** — a breakpoint flip posts a rebind instead
 *    of re-entering layout from `onMeasure`, which used to freeze the screen;
 *  - **one click path** — controls are built by [SettingsTouchTarget], so every
 *    tap is journaled before it dispatches.
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

    /* ---------------------------------------------------------- breakpoints */

    private fun dp(value: Int) = SettingsVisuals.dp(context, value)

    /** Narrow screens and large fonts get the roomier layout. */
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

    /* ---------------------------------------------------------------- bind */

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
        FeatureJournal.record("UI", "home.bind",
            "compact=$compact mode=$mode sections=${HomeCatalog.sections.size}")

        addView(buildHeader(), LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        addView(buildHostLabel(state), lp(bottom = 14))
        for (section in HomeCatalog.sections) {
            addView(buildSectionCard(section), lp(bottom = SettingsVisuals.CARD_GAP))
        }
        addView(buildManagementCard(state), lp(bottom = SettingsVisuals.CARD_GAP))
        addView(buildAboutRow(), lp(top = 4))
    }

    /* -------------------------------------------------------------- pieces */

    private fun buildHeader(): View = FrameLayout(context).apply {
        addView(title(), FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(searchIcon(), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
    }

    private fun title(): TextView =
        text("Qself", 22, palette.text, medium = true).apply { gravity = Gravity.CENTER }

    /** Search opens the global overlay; the old trailing gear duplicated the theme row. */
    private fun searchIcon(): View = ImageView(context).apply {
        setImageResource(R.drawable.ic_search_baseline)
        scaleType = ImageView.ScaleType.CENTER
        imageTintList = ColorStateList.valueOf(palette.secondary)
        SettingsTouchTarget.attach(this, HomeCatalog.SEARCH, "搜索功能") {
            InlineSettings.anchor(this)
            dispatch(HomeCatalog.SEARCH)
        }
    }

    private fun buildHostLabel(state: State): TextView =
        text(state.hostLabel + if (state.safeMode) " · 安全模式" else "", 12, palette.secondary)

    private fun buildSectionCard(section: HomeCatalog.Section): SettingsAccordion =
        SettingsAccordion(context, section.title, section.summary) {
            InlineFeatureList(context, home = section.id)
        }.apply {
            header.tag = section.id
            onExpandedChanged = { open ->
                if (open) expandedSections.add(section.id) else expandedSections.remove(section.id)
            }
            setExpanded(section.id in expandedSections)
        }

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
        }.also { row ->
            SettingsTouchTarget.attach(row, HomeCatalog.ABOUT, "关于与隐私") {
                InlineSettings.anchor(row)
                dispatch(HomeCatalog.ABOUT)
            }
        }

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
        SettingsTouchTarget.attach(row, id, "$title，$subtitle") {
            InlineSettings.anchor(row)
            dispatch(id)
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
