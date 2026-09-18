/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import sumicya.qself.diagnostics.FeatureJournal

/**
 * The settings home dashboard, built entirely from Material 3 Expressive
 * components:
 *
 * - a hero header — large title, host label, and a pill search field;
 * - one [SettingsAccordion] card per catalog section (tonal container, large
 *   corner, leading icon badge);
 * - one management card of [SettingsActionRow]s;
 * - one about row.
 *
 * Three contracts hold this view together:
 *  - **one bind path** — every state change goes through [bind], which either
 *    accepts the new state or rejects it as identical;
 *  - **no rebuild while measuring** — a breakpoint flip posts a rebind instead
 *    of re-entering layout from `onMeasure`, which used to freeze the screen;
 *  - **one click path** — controls are built through [SettingsTouchTarget], so
 *    every tap is journaled before it dispatches.
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
        setPadding(dp(SettingsVisuals.SCREEN_SIDE), dp(4), dp(SettingsVisuals.SCREEN_SIDE), dp(24))
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

        addView(buildHeader(state), lp(bottom = 16))
        for (section in HomeCatalog.sections) {
            addView(buildSectionCard(section), lp(bottom = SettingsVisuals.CARD_GAP))
        }
        addView(buildManagementCard(state), lp(bottom = SettingsVisuals.CARD_GAP))
        addView(buildAboutRow(), lp(top = 8))
    }

    /* -------------------------------------------------------------- header */

    private fun buildHeader(state: State): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(SettingsVisuals.text(context, SettingsVisuals.TYPE_HEADLINE_SMALL, "Qself", palette.text))
        val label = state.hostLabel + if (state.safeMode) " · 安全模式" else ""
        addView(SettingsVisuals.text(context, SettingsVisuals.TYPE_LABEL_MEDIUM, label, palette.secondary).apply {
            setPadding(0, dp(2), 0, dp(16))
        })
        addView(buildSearchField())
    }

    /**
     * The search affordance is a Material 3 search bar: a pill-shaped tonal
     * surface with a leading icon and a hint. The old bare icon gave no hint
     * that it was tappable at all.
     */
    private fun buildSearchField(): View {
        val field = SettingsVisuals.card(context, palette,
            radius = SettingsVisuals.SHAPE_XL, container = palette.surfaceHigh, clickable = true)
        field.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(android.widget.ImageView(context).apply {
                setImageResource(io.github.qauxv.R.drawable.ic_search_baseline)
                setColorFilter(palette.secondary)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(24), dp(24)))
            addView(SettingsVisuals.text(context, SettingsVisuals.TYPE_BODY_LARGE, "搜索功能与设置", palette.secondary).apply {
                setPadding(dp(16), 0, 0, 0)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        SettingsTouchTarget.attach(field, HomeCatalog.SEARCH, "搜索功能") {
            InlineSettings.anchor(field)
            dispatch(HomeCatalog.SEARCH)
        }
        return field
    }

    /* ------------------------------------------------------------ sections */

    private fun buildSectionCard(section: HomeCatalog.Section): SettingsAccordion =
        SettingsAccordion(context, palette, section.title, section.summary, section.icon) {
            InlineFeatureList(context, home = section.id)
        }.apply {
            header.tag = section.id
            onExpandedChanged = { open ->
                if (open) expandedSections.add(section.id) else expandedSections.remove(section.id)
            }
            setExpanded(section.id in expandedSections)
        }

    /* ---------------------------------------------------------- management */

    private fun buildManagementCard(state: State): View {
        val card = SettingsVisuals.card(context, palette, container = palette.surfaceLow)
        card.addView(LinearLayout(context).apply {
            orientation = VERTICAL
            actionRow(io.github.qauxv.R.drawable.ic_warn, "功能开关与错误记录",
                if (state.diagnosticEnabled) "本地功能记录 · 探针日志 · 可复制清空（记录已开）"
                else "本地功能记录 · 探针日志 · 可复制清空",
                HomeCatalog.DIAGNOSTICS)
            actionRow(io.github.qauxv.R.drawable.ic_settings, "主题与显示", "Material 3 Expressive · 玻璃与外观", HomeCatalog.THEME)
            actionRow(io.github.qauxv.R.drawable.ic_item_save_72dp, "备份与恢复", "保留你的配置，放心调整", HomeCatalog.BACKUP)
            actionRow(io.github.qauxv.R.drawable.ic_filter_list, "功能与设置", "按场景合并，子项独立选择", HomeCatalog.CATALOG)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return card
    }

    private fun LinearLayout.actionRow(iconRes: Int, title: String, summary: String, id: String) {
        addView(SettingsActionRow(context, palette).apply {
            bind(iconRes, title, summary, id) {
                InlineSettings.anchor(this)
                dispatch(id)
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildAboutRow(): View =
        SettingsVisuals.text(context, SettingsVisuals.TYPE_LABEL_LARGE, "QSELF · 关于与隐私", palette.secondary)
            .apply { gravity = Gravity.CENTER }
            .also { row ->
                SettingsTouchTarget.attach(row, HomeCatalog.ABOUT, "关于与隐私") {
                    InlineSettings.anchor(row)
                    dispatch(HomeCatalog.ABOUT)
                }
            }

    /* ---------------------------------------------------------- primitives */

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
