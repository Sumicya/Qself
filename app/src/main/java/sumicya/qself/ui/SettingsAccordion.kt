/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.qauxv.R
import io.github.qauxv.dsl.item.UiAgentItem
import sumicya.qself.diagnostics.FeatureJournal

/**
 * One container card: header and expanded body share a single rounded surface;
 * the header never moves and expanding never opens a window.
 *
 * The class exists for one invariant — **at most one height animator, cancelled
 * in place**. A state change cancels the running animator without removing its
 * end callback, and every callback is guarded by [expanded], so a stale
 * completion can never contradict the current state. That is the historic
 * "never collapses" / "collapses on the second tap" bug class, and the journal
 * line carries `interrupted=` so a mid-motion toggle is visible on device.
 *
 * Levels: an expanded card is one level on the host's stack, and a panel
 * opened inside it collapses before the card does.
 */
class SettingsAccordion(
    context: Context,
    title: String,
    summary: String,
    private val contentFactory: () -> View,
) : LinearLayout(context) {

    /** The tappable header; callers tag it with the card's identity. */
    val header = LinearLayout(context)

    private val body = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
    }
    private val arrow = ImageView(context).apply {
        setImageResource(R.drawable.qself_expand_more)
        scaleType = ImageView.ScaleType.CENTER
    }
    private val palette = SettingsVisuals.palette(context, 2)

    private var animation: ValueAnimator? = null

    var onExpandedChanged: ((Boolean) -> Unit)? = null
    var expanded = false
        private set

    init {
        orientation = VERTICAL
        background = SettingsVisuals.surface(context, palette, SettingsVisuals.CARD_RADIUS, false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height,
                    SettingsVisuals.dp(context, SettingsVisuals.CARD_RADIUS).toFloat())
            }
        }
        clipToOutline = true
        buildHeader(title, summary)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /* ------------------------------------------------------------- header */

    private fun buildHeader(title: String, summary: String) {
        header.apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = SettingsVisuals.dp(context, SettingsVisuals.HEADER_HEIGHT)
            orientation = HORIZONTAL
            val label = if (summary.isBlank()) title else "$title，$summary"
            SettingsTouchTarget.prepare(this, label)
            setOnClickListener {
                FeatureJournal.record("UI", MARKER, "tag=${header.tag} expanded=$expanded")
                setExpanded(!expanded, true)
            }
            // Announcement is expand/collapse, not click: the state is the point.
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                    info.addAction(if (expanded) ACTION_COLLAPSE else ACTION_EXPAND)
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == AccessibilityNodeInfo.ACTION_EXPAND || action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
                        setExpanded(action == AccessibilityNodeInfo.ACTION_EXPAND, true)
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
            if (Build.VERSION.SDK_INT >= 30) stateDescription = "已收起"
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
        }
    }

    /* -------------------------------------------------------------- state */

    fun setExpanded(value: Boolean, animate: Boolean = false) {
        if (value == expanded) return
        // Cancel in place: end callbacks must still run (expand finalises to
        // WRAP_CONTENT, and the shrink callback is guarded by [expanded]).
        val interrupted = animation?.isRunning == true
        animation?.cancel()
        animation = null
        expanded = value
        FeatureJournal.record("UI", "accordion.${header.tag ?: "card"}",
            "${if (value) "expand" else "collapse"} animated=$animate interrupted=$interrupted")

        if (value && body.childCount == 0) {
            body.addView(contentFactory(), LayoutParams(-1, -2))
        }
        if (Build.VERSION.SDK_INT >= 30) {
            header.stateDescription = if (value) "已展开" else "已收起"
        }
        rotateChevron(value, animate)

        val activity = UiAgentItem.findActivity(context)
        if (value) {
            activity?.let { InlineSettings.openLevel(it, this, null) { setExpanded(false, true); true } }
        } else {
            // Panels opened inside this card close first, then the card itself.
            activity?.let { InlineSettings.collapseLevelsInside(it, this) }
            activity?.let { InlineSettings.closeLevel(it, this) }
        }

        animation = runHeightMotion(value, animate)
        if (animation == null) settle(value)
        onExpandedChanged?.invoke(value)
    }

    private fun rotateChevron(value: Boolean, animate: Boolean) {
        arrow.animate().cancel()
        arrow.animate().rotation(if (value) 180f else 0f)
            .setDuration(if (animate && SettingsMotion.enabled()) 200L else 0L)
            .start()
    }

    /** The height animator for [value], or null when the change is instant. */
    private fun runHeightMotion(value: Boolean, animate: Boolean): ValueAnimator? {
        val animated = animate && isLaidOut && isAttachedToWindow && SettingsMotion.enabled()
        if (!animated) return null
        return if (value) SettingsMotion.expandBody(body)
        else SettingsMotion.shrinkBody(body) {
            // Guarded: a cancelled shrink must not hide a freshly expanded card.
            if (!expanded) settle(false)
        }
    }

    /** Static end state: [visible] -> measured body, otherwise hidden. */
    private fun settle(visible: Boolean) {
        body.visibility = if (visible) VISIBLE else GONE
        body.layoutParams = body.layoutParams.apply { height = LayoutParams.WRAP_CONTENT }
    }

    /* ---------------------------------------------------------- lifecycle */

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // A rebound home reuses its cards: an expanded card is still a level.
        if (expanded) UiAgentItem.findActivity(context)?.let {
            InlineSettings.openLevel(it, this, null) { setExpanded(false, true); true }
        }
    }

    override fun onDetachedFromWindow() {
        animation?.end()
        arrow.animate().cancel()
        // Leaving the tree must not strand a level: back would otherwise spend
        // a press on something the user cannot see.
        UiAgentItem.findActivity(context)?.let { InlineSettings.closeLevel(it, this) }
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Journal marker: the card's own click line, keyed by its tag. */
        const val MARKER = "accordion.click"
        val ACTION_EXPAND = AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND
        val ACTION_COLLAPSE = AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
    }
}
