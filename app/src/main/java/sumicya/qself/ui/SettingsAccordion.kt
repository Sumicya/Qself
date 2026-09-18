/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import sumicya.qself.diagnostics.FeatureJournal

/**
 * One Material 3 Expressive container card: a header row with a leading icon
 * and title, and an expandable body inside the same card. The header never
 * moves and expanding never opens a window.
 *
 * The class exists for one invariant — **at most one height animator, cancelled
 * in place**. A state change cancels the running animator without removing its
 * end callback, and every callback is guarded by [expanded], so a stale
 * completion can never contradict the current state (the historic "never
 * collapses" bug class). The journal line carries `interrupted=` so a
 * mid-motion toggle is visible on device.
 *
 * Levels: an expanded card is one level on the host's stack, and a panel opened
 * inside it collapses before the card does.
 */
class SettingsAccordion(
    context: Context,
    private val palette: SettingsVisuals.Palette,
    title: String,
    summary: String,
    iconRes: Int,
    private val contentFactory: () -> View,
) : MaterialCardView(context) {

    /** The tappable header; callers tag it with the card's identity. */
    val header = LinearLayout(context)

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = GONE
    }
    private val arrow = ImageView(context).apply {
        setImageResource(io.github.qauxv.R.drawable.qself_expand_more)
        scaleType = ImageView.ScaleType.CENTER
        rotation = 0f
    }
    private val titleView = TextView(context)
    private val summaryView = TextView(context)

    private var animation: ValueAnimator? = null

    var onExpandedChanged: ((Boolean) -> Unit)? = null
    var expanded = false
        private set

    init {
        radius = SettingsVisuals.dp(context, SettingsVisuals.CARD_RADIUS).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(palette.surfaceLow)
        setRippleColor(null)
        buildHeader(title, summary, iconRes)
        addView(header, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(body, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    /* ------------------------------------------------------------- header */

    private fun buildHeader(title: String, summary: String, iconRes: Int) {
        header.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = SettingsVisuals.dp(context, SettingsVisuals.HEADER_HEIGHT + 16)
            setPadding(SettingsVisuals.dp(context, 16), SettingsVisuals.dp(context, 12),
                SettingsVisuals.dp(context, 12), SettingsVisuals.dp(context, 12))
            val label = if (summary.isBlank()) title else "$title，$summary"
            SettingsTouchTarget.prepare(this, label)
            setOnClickListener {
                FeatureJournal.record("UI", MARKER, "tag=${header.tag} expanded=$expanded")
                setExpanded(!expanded, true)
            }
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

            // Leading icon in a tonal circle: the Material 3 expressive card header.
            addView(iconBadge(iconRes), LinearLayout.LayoutParams(
                SettingsVisuals.dp(context, 40), SettingsVisuals.dp(context, 40)))
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView.apply {
                    text = title
                    SettingsVisuals.applyType(this, context, SettingsVisuals.TYPE_TITLE_LARGE)
                    setTextColor(palette.text)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                })
                if (summary.isNotBlank()) {
                    addView(summaryView.apply {
                        text = summary
                        SettingsVisuals.applyType(this, context, SettingsVisuals.TYPE_BODY_MEDIUM)
                        setTextColor(palette.secondary)
                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    })
                }
            }
            addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SettingsVisuals.dp(context, 16)
            })
            addView(arrow.apply {
                setColorFilter(palette.secondary)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(SettingsVisuals.dp(context, 24), SettingsVisuals.dp(context, 24)))
        }
    }

    private fun iconBadge(iconRes: Int): FrameLayout = FrameLayout(context).apply {
        background = SettingsVisuals.roundedFill(context, palette.selectedContainer, SettingsVisuals.SHAPE_M)
        addView(ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER
            setColorFilter(palette.onSelectedContainer)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(SettingsVisuals.dp(context, 24), SettingsVisuals.dp(context, 24), Gravity.CENTER))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
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
            body.addView(contentFactory(), LinearLayout.LayoutParams(-1, -2))
        }
        // An open card steps up the surface tier, the Material 3 container idiom.
        setCardBackgroundColor(if (value) palette.surfaceHigh else palette.surfaceLow)
        if (Build.VERSION.SDK_INT >= 30) {
            header.stateDescription = if (value) "已展开" else "已收起"
        }
        rotateChevron(value, animate)

        val activity = io.github.qauxv.dsl.item.UiAgentItem.findActivity(context)
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
            .setDuration(if (animate && SettingsMotion.enabled()) SettingsMotion.duration(context) else 0L)
            .setInterpolator(SettingsMotion.easing(context))
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
        if (expanded) io.github.qauxv.dsl.item.UiAgentItem.findActivity(context)?.let {
            InlineSettings.openLevel(it, this, null) { setExpanded(false, true); true }
        }
    }

    override fun onDetachedFromWindow() {
        animation?.end()
        arrow.animate().cancel()
        // Leaving the tree must not strand a level: back would otherwise spend
        // a press on something the user cannot see.
        io.github.qauxv.dsl.item.UiAgentItem.findActivity(context)?.let { InlineSettings.closeLevel(it, this) }
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Journal marker: the card's own click line, keyed by its tag. */
        const val MARKER = "accordion.click"
        val ACTION_EXPAND = AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND
        val ACTION_COLLAPSE = AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
    }
}
