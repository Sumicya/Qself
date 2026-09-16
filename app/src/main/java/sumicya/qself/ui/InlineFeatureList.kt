/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.widget.LinearLayout
import io.github.qauxv.base.IUiItemAgentProvider
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.dsl.cell.HeaderCell
import io.github.qauxv.dsl.item.UiAgentItem
import sumicya.qself.feature.consolidation.FeatureCatalog
import sumicya.qself.feature.dev.DiagLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * One shared feature list in a downward-flowing layout.
 *
 * Group content lives in a [RailContainer] that paints the merged state
 * slots; it is normally hosted as the body of a [SettingsAccordion] card.
 */
class InlineFeatureList(context: Context, groupId: String? = null, home: String? = null, focus: String? = null) :
    LinearLayout(context) {

    private val bindings = mutableListOf<Pair<UiAgentItem, androidx.recyclerview.widget.RecyclerView.ViewHolder>>()
    private var scope: kotlinx.coroutines.CoroutineScope? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate).also { owner ->
            bindings.forEach { (item, holder) -> item.agentProvider.uiItemAgent.valueState?.let { state ->
                owner.launch { state.collect { item.bindView(holder, -1, context) } }
            } }
        }
    }

    override fun onDetachedFromWindow() { scope?.cancel(); scope = null; super.onDetachedFromWindow() }

    init {
        orientation = VERTICAL
        // Flat: a category opens straight into its rows. The previous shape was
        // section -> group card -> rows, where the middle card was a fake second
        // level (a heading, not a destination) and one extra tap.
        // The rows are the card's own content now, so the list keeps the small bottom
        // inset the panel used to get from its group card margin.
        if (groupId == null) setPadding(0, 0, 0, SettingsVisuals.dp(context, 6))
        val groups = if (groupId == null) {
            FeatureCatalog.groups.filter { it.id != "_core" && (home == null || it.home == home) }
        } else {
            FeatureCatalog.groups.filter { it.id == groupId }
        }
        val rail = RailContainer(context)
        val palette = SettingsVisuals.palette(context)
        val providers = featureProviders()
        var rowIndex = 0
        for (group in groups) {
            rail.addView(HeaderCell(context).apply {
                title = group.title
                SettingsVisuals.decorateCardChild(this, palette, false)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            for (section in group.sections) {
                for (id in section.features) rowIndex = addFeatureRow(rail, providers, id, focus, rowIndex)
            }
        }
        // A failed provider lookup must not look like an empty category: say so and record why.
        if (rowIndex == 0) rail.addView(explainEmptyList(palette), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(rail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun explainEmptyList(palette: SettingsVisuals.Palette): android.view.View =
        android.widget.TextView(context).apply {
            text = "功能列表暂不可用，已记录到诊断日志"
            textSize = 13f
            setTextColor(palette.secondary)
            setPadding(SettingsVisuals.dp(context, SettingsVisuals.TEXT_START),
                SettingsVisuals.dp(context, 12), SettingsVisuals.dp(context, SettingsVisuals.RAIL_WIDTH),
                SettingsVisuals.dp(context, 12))
        }

    private fun addFeatureRow(rail: RailContainer, providers: Map<String, IUiItemAgentProvider>,
                              id: String, focus: String?, index: Int): Int {
        val provider = providers[id] ?: return index
        val item = UiAgentItem(id, id, provider)
        val holder = item.createViewHolder(context, this)
        item.bindView(holder, -1, context)
        bindings.add(item to holder)
        SettingsVisuals.decorateCardChild(holder.itemView, SettingsVisuals.palette(context), item.isClickable)
        // Stable identity for tests, deep links and debugging; the agent keeps its own int-keyed tag.
        holder.itemView.tag = id
        rail.addView(holder.itemView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        animateRowIn(holder.itemView, index)
        if (id == focus) {
            holder.itemView.post {
                holder.itemView.requestFocus()
                holder.itemView.requestRectangleOnScreen(android.graphics.Rect(0, 0, holder.itemView.width, holder.itemView.height))
            }
        }
        return index + 1
    }

    /**
     * MD3 Expressive staggered settle of rows when a category expands.
     *
     * The stagger is capped: an uncapped per-row delay added up to a second of blank rows in
     * longer categories, which reads as "the animation is missing" rather than as motion. The
     * listener (not withEndAction) restores full alpha on cancel too, so a quick collapse can
     * never leave a recycled row invisible.
     */
    private fun animateRowIn(view: android.view.View, index: Int) {
        if (!SettingsMotion.enabled()) return
        view.alpha = 0f
        view.translationY = SettingsVisuals.dp(context, 10).toFloat()
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(minOf(index, MAX_STAGGERED_ROWS) * 28L)
            .setDuration(220L)
            .setInterpolator(SettingsMotion.easing(context))
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.alpha = 1f
                    view.translationY = 0f
                }
            })
            .start()
    }

    private companion object {
        /** Rows beyond this settle together; keeps the whole expansion under ~250 ms. */
        const val MAX_STAGGERED_ROWS = 8

        /** Resolved once per process; the registry is the same for every list. */
        private var resolvedProviders: Map<String, IUiItemAgentProvider>? = null

        /**
         * Feature rows come from the generated provider registry, which constructs every catalog
         * provider when it is first touched. That means one provider whose class cannot initialise
         * on this host takes the whole lookup down - and expanding a category is a click away from
         * the home, so the failure must be contained instead of crashing the settings screen.
         * A failed lookup is recorded through [DiagLog] and retried on the next expansion; a
         * successful one is memoised, which also stops every section from redoing the work.
         */
        fun featureProviders(): Map<String, IUiItemAgentProvider> {
            resolvedProviders?.let { return it }
            return runCatching {
                FunctionEntryRouter.queryAnnotatedUiItemAgentEntries()
                    .associateBy { it.itemAgentProviderUniqueIdentifier }
            }.onFailure {
                DiagLog.w("settings-providers unavailable: ${it.javaClass.name}: ${it.message}")
            }.getOrElse { emptyMap() }.also { if (it.isNotEmpty()) resolvedProviders = it }
        }
    }
}
