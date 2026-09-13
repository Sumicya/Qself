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

/**
 * One unified container card: the header (title left, chevron in the shared
 * 52dp trailing slot) and the expanded body share the same rounded surface.
 * The header stays in place; expanding never opens a window or navigates.
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

        header.apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = SettingsVisuals.dp(context, SettingsVisuals.HEADER_HEIGHT) // 48dp touch target
            orientation = HORIZONTAL
            isClickable = true
            isFocusable = true
            foreground = SettingsVisuals.rowStateLayer(context, palette)
            contentDescription = if (summary.isBlank()) title else "$title，$summary"
            if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = "已收起"
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                    info.addAction(if (expanded) android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                        else android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
                }
                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                    if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND ||
                        action == android.view.accessibility.AccessibilityNodeInfo.ACTION_COLLAPSE) {
                        setExpanded(action == android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND, true); return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
            val label = TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(palette.text)
                includeFontPadding = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = SettingsVisuals.dp(context, SettingsVisuals.TEXT_START)
            })
            arrow.setColorFilter(palette.secondary)
            arrow.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            val slot = SettingsVisuals.dp(context, SettingsVisuals.RAIL_WIDTH)
            addView(arrow, LayoutParams(slot, LayoutParams.MATCH_PARENT).apply { gravity = Gravity.CENTER_VERTICAL })
            setOnClickListener { setExpanded(!expanded, true) }
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setExpanded(value: Boolean, animate: Boolean = false) {
        if (value == expanded) return
        animation?.removeAllListeners(); animation?.removeAllUpdateListeners(); animation?.cancel()
        expanded = value
        if (value && body.childCount == 0) body.addView(contentFactory(), LayoutParams(-1, -2))
        if (android.os.Build.VERSION.SDK_INT >= 30) header.stateDescription = if (value) "已展开" else "已收起"
        arrow.animate().cancel()
        arrow.animate().rotation(if (value) 180f else 0f).setDuration(if (animate && SettingsMotion.enabled()) 200 else 0).start()
        if (!animate || !isLaidOut || !isAttachedToWindow || !SettingsMotion.enabled()) {
            body.visibility = if (value) VISIBLE else GONE
            body.layoutParams.height = LayoutParams.WRAP_CONTENT
        } else {
            val start = if (body.visibility == VISIBLE) body.height else 0
            body.visibility = VISIBLE
            body.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val end = if (value) body.measuredHeight else 0
            animation = ValueAnimator.ofInt(start, end).apply {
                duration = SettingsMotion.duration(context)
                interpolator = SettingsMotion.easing(context)
                addUpdateListener { body.layoutParams = body.layoutParams.apply { height = it.animatedValue as Int } }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animator: android.animation.Animator) {
                        body.visibility = if (expanded) VISIBLE else GONE
                        body.layoutParams = body.layoutParams.apply { height = LayoutParams.WRAP_CONTENT }
                    }
                })
                start()
            }
        }
        onExpandedChanged?.invoke(value)
    }

    override fun onDetachedFromWindow() {
        animation?.end(); arrow.animate().cancel()
        super.onDetachedFromWindow()
    }
}
