/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.widget.LinearLayout
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.dsl.cell.HeaderCell
import io.github.qauxv.dsl.item.UiAgentItem
import sumicya.qself.feature.consolidation.FeatureCatalog
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
        if (groupId == null) {
            FeatureCatalog.groups.filter { it.id != "_core" && (home == null || it.home == home) }.forEach { group ->
                addView(SettingsAccordion(context, group.title, "${group.sections.sumOf { it.features.size }} 项独立设置") {
                    InlineFeatureList(context, group.id, focus = focus)
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = SettingsVisuals.dp(context, SettingsVisuals.CARD_GAP)
                })
            }
        } else {
            // Group body: a rail-aware container hosted by the accordion card.
            val rail = RailContainer(context)
            val palette = SettingsVisuals.palette(context)
            val providers = FunctionEntryRouter.queryAnnotatedUiItemAgentEntries().associateBy { it.itemAgentProviderUniqueIdentifier }
            var rowIndex = 0
            FeatureCatalog.groups.firstOrNull { it.id == groupId }?.sections?.forEach { section ->
                rail.addView(HeaderCell(context).also { SettingsVisuals.decorateCardChild(it, palette, false) },
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                section.features.forEach { id -> providers[id]?.let { provider ->
                    val item = UiAgentItem(id, id, provider)
                    val holder = item.createViewHolder(context, this)
                    item.bindView(holder, -1, context)
                    bindings.add(item to holder)
                    SettingsVisuals.decorateCardChild(holder.itemView, palette, item.isClickable)
                    rail.addView(holder.itemView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    animateRowIn(holder.itemView, rowIndex++)
                    if (id == focus) holder.itemView.post { holder.itemView.requestFocus(); holder.itemView.requestRectangleOnScreen(android.graphics.Rect(0, 0, holder.itemView.width, holder.itemView.height)) }
                } }
            }
            addView(rail, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    /** MD3 Expressive staggered settle of rows when a category expands. */
    private fun animateRowIn(view: android.view.View, index: Int) {
        if (!SettingsMotion.enabled()) return
        val dy = SettingsVisuals.dp(context, 10).toFloat()
        view.alpha = 0f
        view.translationY = dy
        view.animate().alpha(1f).translationY(0f)
            .setStartDelay(40L + index * 35L)
            .setDuration(260L)
            .setInterpolator(SettingsMotion.easing(context))
            .withEndAction { view.alpha = 1f; view.translationY = 0f }
            .start()
    }
}
