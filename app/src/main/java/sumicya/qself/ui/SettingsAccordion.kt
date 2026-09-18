/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.qauxv.dsl.item.UiAgentItem

/**
 * One unified container card: header and expanded body share a single rounded
 * surface; the header never moves, expanding never opens a window.
 *
 * Motion contract (the whole class is built around it):
 *  - at most ONE height animator exists, tracked in [animation];
 *  - every state change cancels it IN PLACE - end callbacks always run;
 *  - end callbacks are guarded by [expanded], so a stale completion can never
 *    contradict the current state (the historic "never collapses" bug class).
 */
class SettingsAccordion(context: Context, title: String, summary: String,
                        private val contentFactory: () -> View) : LinearLayout(context) {

    private val body = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val arrow = ImageView(context).apply {
        setImageResource(io.github.qauxv.R.drawable.qself_expand_more)
        scaleType = ImageView.ScaleType.CENTER
    }
    val header = LinearLayout(context)

    private var animation: ValueAnimator? = null
    var onExpandedChanged: ((Boolean) -> Unit)? = null
    var expanded = false
        private set

    private val palette = SettingsVisuals.palette(context, 2)

    init {
        orientation = VERTICAL
        background = SettingsVisuals.surface(context, palette, SettingsVisuals.CARD_RADIUS, false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val r = SettingsVisuals.dp(context, SettingsVisuals.CARD_RADIUS).toFloat()
                outline.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
        clipToOutline = true
        buildHeader(title, summary)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildHeader(title: String, summary: String) {
        header.apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = SettingsVisuals.dp(context, SettingsVisuals.HEADER_HEIGHT) // 48dp target
            orientation = HORIZONTAL
            isClickable = true
            isFocusable = true
            foreground = SettingsVisuals.rowStateLayer(context, palette)
            contentDescription = if (summary.isBlank()) title else "$title，$summary"
            if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = "已收起"
            accessibilityDelegate = object : AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                    info.addAction(if (expanded)
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                    else android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
                }

                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                    if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND ||
                        action == android.view.accessibility.AccessibilityNodeInfo.ACTION_COLLAPSE) {
                        setExpanded(action == android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND, true)
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(palette.text)
                includeFontPadding = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SettingsVisuals.dp(context, SettingsVisuals.TEXT_START)
                marginEnd = SettingsVisuals.dp(context, 8)
            })
            arrow.setColorFilter(palette.secondary)
            arrow.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(arrow, LayoutParams(SettingsVisuals.dp(context, SettingsVisuals.RAIL_WIDTH),
                LayoutParams.MATCH_PARENT).apply { gravity = Gravity.CENTER_VERTICAL })
            setOnClickListener { setExpanded(!expanded, true) }
        }
    }

    private fun findActivity() = UiAgentItem.findActivity(context)

    /** The card is the first real level of the tree; back closes it. */
    private fun registerLevel(activity: android.app.Activity) {
        InlineSettings.openLevel(activity, this, null) { setExpanded(false, true); true }
    }

    fun setExpanded(value: Boolean, animate: Boolean = false) {
        if (value == expanded) return
        // Cancel in place. End callbacks must run: expand finalises the height
        // to WRAP_CONTENT, and the shrink callback is guarded by [expanded].
        // Probe: an interrupt (a toggle landing mid-motion) is exactly the race
        // the motion contract exists for; the journal makes it observable.
        val interrupted = animation?.isRunning == true
        animation?.cancel()
        animation = null
        expanded = value
        sumicya.qself.diagnostics.FeatureJournal.record("UI", "accordion." +
            (header.tag ?: "card"),
            "${if (value) "expand" else "collapse"} animated=$animate interrupted=$interrupted")

        if (value && body.childCount == 0) {
            body.addView(contentFactory(), LayoutParams(-1, -2))
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            header.stateDescription = if (value) "已展开" else "已收起"
        }
        rotateChevron(value, animate)

        val activity = findActivity()
        if (value) {
            activity?.let { registerLevel(it) }
        } else {
            // Panels opened inside this card close first, then the card itself.
            activity?.let { InlineSettings.collapseLevelsInside(it, this) }
            activity?.let { InlineSettings.closeLevel(it, this) }
        }

        animation = runHeightMotion(value, animate)
        if (animation == null) applyRestState(value)
        onExpandedChanged?.invoke(value)
    }

    private fun rotateChevron(value: Boolean, animate: Boolean) {
        arrow.animate().cancel()
        val duration = if (animate && SettingsMotion.enabled()) 200L else 0L
        arrow.animate().rotation(if (value) 180f else 0f).setDuration(duration).start()
    }

    /** The height animator for [value], or null when applied instantly. */
    private fun runHeightMotion(value: Boolean, animate: Boolean): ValueAnimator? {
        val animated = animate && isLaidOut && isAttachedToWindow && SettingsMotion.enabled()
        if (!animated) return null
        return if (value) SettingsMotion.expandBody(body)
        else SettingsMotion.shrinkBody(body) {
            // Guarded: a cancelled shrink must not hide a freshly expanded card.
            if (!expanded) applyRestState(false)
        }
    }

    /** Static end state: [visible] -> measured body, otherwise hidden. */
    private fun applyRestState(visible: Boolean) {
        body.visibility = if (visible) VISIBLE else GONE
        body.layoutParams = body.layoutParams.apply { height = LayoutParams.WRAP_CONTENT }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A rebound home reuses its cards: an expanded card is still a level.
        if (expanded) findActivity()?.let { registerLevel(it) }
    }

    override fun onDetachedFromWindow() {
        animation?.end()
        arrow.animate().cancel()
        // Leaving the tree must not strand a level: back would otherwise spend
        // a press on something the user cannot see.
        findActivity()?.let { InlineSettings.closeLevel(it, this) }
        super.onDetachedFromWindow()
    }
}
