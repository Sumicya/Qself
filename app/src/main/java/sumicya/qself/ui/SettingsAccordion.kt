/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Lazy inline expansion. The header stays in place; no window or feature is opened by expanding. */
class SettingsAccordion(context: Context, title: String, summary: String,
                        private val contentFactory: () -> View) : LinearLayout(context) {
    private val body = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val arrow = TextView(context).apply { text = "⌄"; textSize = 22f; gravity = Gravity.CENTER }
    val header = LinearLayout(context)
    private var animation: ValueAnimator? = null
    var onExpandedChanged: ((Boolean) -> Unit)? = null
    var expanded = false
        private set
    init {
        orientation = VERTICAL
        val p = SettingsVisuals.palette(context, 2)
        header.apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = SettingsVisuals.dp(context, 64)
            setPadding(SettingsVisuals.dp(context, 16), SettingsVisuals.dp(context, 10), SettingsVisuals.dp(context, 12), SettingsVisuals.dp(context, 10))
            background = SettingsVisuals.surface(context, p, 12, true)
            isFocusable = true
            contentDescription = "$title，$summary"
            if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = "已收起"
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                    info.addAction(if (expanded) android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                        else android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
                }
                override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                    if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND || action == android.view.accessibility.AccessibilityNodeInfo.ACTION_COLLAPSE) {
                        setExpanded(action == android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND, true); return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
            val labels = LinearLayout(context).apply { orientation = VERTICAL }
            labels.addView(TextView(context).apply { text = title; textSize = 17f; setTextColor(p.text) })
            if (summary.isNotBlank()) labels.addView(TextView(context).apply { text = summary; textSize = 12f; setTextColor(p.secondary) })
            labels.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            arrow.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(labels, LayoutParams(0, -2, 1f))
            arrow.setTextColor(p.secondary)
            addView(arrow, LayoutParams(SettingsVisuals.dp(context, 32), SettingsVisuals.dp(context, 40)))
            setOnClickListener { setExpanded(!expanded, true) }
        }
        addView(header, LayoutParams(-1, -2))
        addView(body, LayoutParams(-1, -2))
    }
    fun setExpanded(value: Boolean, animate: Boolean = false) {
        if (value == expanded) return
        animation?.removeAllListeners(); animation?.removeAllUpdateListeners(); animation?.cancel()
        expanded = value
        if (value && body.childCount == 0) body.addView(contentFactory(), LayoutParams(-1, -2))
        if (android.os.Build.VERSION.SDK_INT >= 30) header.stateDescription = if (value) "已展开" else "已收起"
        arrow.animate().cancel()
        arrow.animate().rotation(if (value) 180f else 0f).setDuration(if (animate && SettingsMotion.enabled()) 200 else 0).start()
        if (!animate || !isLaidOut || !SettingsMotion.enabled()) {
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
