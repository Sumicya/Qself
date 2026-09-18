/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import sumicya.qself.diagnostics.FeatureJournal

/**
 * One clickable-control contract for the whole settings UI.
 *
 * Every control that can be tapped goes through [attach]: it becomes a named,
 * focusable, 48dp target that announces itself as a button, and its tap is
 * journaled before the action runs. That journal line is the difference
 * between "the row looks dead" and "the row was tapped and the dispatcher did
 * not open anything" — the two failure modes are told apart in one probe.
 */
object SettingsTouchTarget {

    /** Journal marker used by dashboard rows; viewers key off this string. */
    const val HOME_MARKER = "home.click"

    /**
     * Makes [view] a settings control. [label] is what a screen reader reads;
     * [id] is the stable identity recorded on every tap.
     */
    fun attach(
        view: View,
        id: String,
        label: String,
        marker: String = HOME_MARKER,
        onClick: () -> Unit,
    ) {
        view.tag = id
        prepare(view, label)
        view.setOnClickListener {
            // Proves the touch reached this target; a tap that lands here but
            // opens nothing means the dispatcher is the problem, not the view.
            FeatureJournal.record("UI", marker, "id=$id")
            onClick()
        }
        announceAsButton(view)
    }

    /**
     * Makes [view] a named, focusable 48dp target without wiring a click, for
     * controls that carry their own richer click path (an accordion header
     * announces expand/collapse rather than click).
     */
    fun prepare(view: View, label: String) {
        view.contentDescription = label
        view.isFocusable = true
        view.isClickable = true
        view.minimumHeight = maxOf(view.minimumHeight, dp(view, 48))
    }

    /** Screen readers hear a button, and its decorative children stay silent. */
    fun announceAsButton(view: View) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        if (view is ViewGroup) hideDecorativeChildren(view)
    }

    /** Text and icons inside a labelled control are decoration, not targets. */
    fun hideDecorativeChildren(parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            child.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (child is ViewGroup) hideDecorativeChildren(child)
        }
    }

    private fun dp(view: View, value: Int): Int =
        SettingsVisuals.dp(view.context, value)
}
