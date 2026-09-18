/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.qauxv.dsl.cell.TitleValueCell
import io.github.qauxv.dsl.item.UiAgentItem
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Module-owned settings only: no global dialog hooks and no hidden windows.
 *
 * One in-place panel per host activity. A panel is a [Panel] object that owns
 * its box view, its level-stack entry and its close path - one lifecycle, one
 * owner, no scattered closures. Levels (open cards and panels) share a
 * per-activity stack so back always unwinds the newest thing the user opened.
 */
object InlineSettings {

    /* ------------------------------------------------------------ panels */

    /** One open settings panel and everything needed to close it exactly once. */
    private class Panel(
        val activity: Activity,
        val box: LinearLayout,
        val cancelable: Boolean,
        private val onClose: Runnable?,
        private val onCancel: Runnable?,
    ) {
        /** Closing started (animation running); further requests are no-ops. */
        var closing = false
            private set

        /** Detach listener removes this panel's registration, never its content. */
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                unregisterFrom(activity)
                closeNow()
            }
        }

        /** Animated close. The shrink animation may be cancelled by a host
         * rebuild; the guard in the end callback keeps [closing] honest either
         * way and [onClose] runs exactly once. */
        fun requestClose() {
            if (closing) return
            closing = true
            sumicya.qself.diagnostics.FeatureJournal.record("UI", "panel.close", "animated=true")
            unregisterFrom(activity)
            SettingsMotion.collapse(box) { onClose?.run() }
        }

        /** Synchronous close for host teardown: no animation on a leaving tree. */
        fun closeNow() {
            if (closing) return
            closing = true
            sumicya.qself.diagnostics.FeatureJournal.record("UI", "panel.close", "animated=false")
            (box.parent as? ViewGroup)?.removeView(box)
            onClose?.run()
        }

        /** Back on this panel: protected panels refuse and consume the press. */
        fun back(): Boolean {
            if (!cancelable) {
                sumicya.qself.diagnostics.FeatureJournal.record("UI", "panel.back", "refused=protected")
                return false
            }
            if (onCancel != null) onCancel.run() else requestClose()
            return true
        }

        fun unregisterFrom(activity: Activity) {
            panels[activity]?.remove(this)
            closeLevel(activity, box)
            box.removeOnAttachStateChangeListener(detachListener)
        }
    }

    private val panels = WeakHashMap<Activity, MutableList<Panel>>()
    private val anchors = WeakHashMap<Activity, WeakReference<View>>()
    private val fallbacks = WeakHashMap<Activity, WeakReference<View>>()

    /* ------------------------------------------------------------ levels */

    private val levels = WeakHashMap<Activity, SettingsLevelStack>()

    /** Level closer that always succeeds: the contract for plain panels/cards. */
    private val levelClosers = WeakHashMap<Activity, MutableMap<Any, () -> Boolean>>()

    private fun stack(activity: Activity) = levels.getOrPut(activity) { SettingsLevelStack() }

    /** Registers an opened level; [collapse] performs its animated close. */
    @JvmStatic
    fun openLevel(activity: Activity, token: Any, container: Any?, collapse: () -> Boolean) {
        stack(activity).push(token, container)
        levelClosers.getOrPut(activity) { WeakHashMap() }[token] = collapse
    }

    /** Removes a level that closed by any route. */
    @JvmStatic
    fun closeLevel(activity: Activity, token: Any) {
        levels[activity]?.remove(token)
        levelClosers[activity]?.remove(token)
    }

    @JvmStatic
    fun hasLevels(activity: Activity): Boolean = levels[activity]?.isEmpty == false

    /**
     * Back: close the most recently opened level, whatever kind it is.
     * Returns false only when nothing is open, so the caller can leave the
     * screen. A protected level refuses to close and consumes the press.
     */
    @JvmStatic
    fun back(activity: Activity): Boolean {
        val token = levels[activity]?.top() ?: return false
        val closer = levelClosers[activity]?.get(token)
        if (closer == null) {
            closeLevel(activity, token) // stale entry: drop it, make progress
            return true
        }
        val closed = closer.invoke()
        // A protected level swallows the press; everything else must make
        // progress so a forgotten closer can never strand the back key.
        if (closed && levels[activity]?.top() === token) closeLevel(activity, token)
        return true
    }

    /** A container that collapses must take its open levels with it, innermost first. */
    @JvmStatic
    fun collapseLevelsInside(activity: Activity, container: View): Boolean {
        val tokens = levels[activity]?.inside(container) ?: return false
        var any = false
        for (token in tokens) {
            val collapse = levelClosers[activity]?.get(token) ?: continue
            if (collapse()) any = true
        }
        return any
    }

    /* ------------------------------------------------------ registration */

    @JvmStatic
    fun register(activity: Activity, fallback: View) {
        fallbacks[activity] = WeakReference(fallback)
    }

    /** Host teardown: close every panel synchronously and drop all state. */
    @JvmStatic
    fun unregister(activity: Activity) {
        panels[activity]?.toList()?.asReversed()?.forEach {
            it.unregisterFrom(activity)
            it.closeNow()
        }
        panels.remove(activity)
        anchors.remove(activity)
        fallbacks.remove(activity)
        levels[activity]?.clear()
        levels.remove(activity)
        levelClosers.remove(activity)
    }

    @JvmStatic
    fun anchor(view: View) {
        UiAgentItem.findActivity(view.context)?.let {
            anchors[it] = WeakReference(view)
            if (it is io.github.qauxv.activity.SettingsUiFragmentHostActivity && !available(it)) {
                fallbacks[it] = WeakReference(view)
            }
        }
    }

    @JvmStatic
    fun resetAnchor(activity: Activity) {
        anchors.remove(activity)
    }

    @JvmStatic
    fun available(context: Context): Boolean =
        UiAgentItem.findActivity(context)?.let { fallbacks[it]?.get()?.isAttachedToWindow == true } == true

    /* ------------------------------------------------------------- close */

    /**
     * Closes the panel hosted by [row] exactly like back does. Returns true
     * when the tap was consumed here (a panel closed, or a protected panel
     * swallowed the tap).
     */
    @JvmStatic
    fun collapseRow(view: View): Boolean {
        val row = view as? TitleValueCell ?: return false
        if (row.inlineContent.childCount == 0) return false
        if (hasProtectedContent(row.inlineContent)) return true // swallow
        val activity = UiAgentItem.findActivity(row.context)
        val open = activity?.let { panels[it]?.filter { p -> p.box.parent === row.inlineContent }?.toList() }
        if (!open.isNullOrEmpty()) {
            for (panel in open) panel.requestClose()
        } else {
            // Foreign children (not ours): shrink them out without a panel object.
            val children = (0 until row.inlineContent.childCount).map { row.inlineContent.getChildAt(it) }
            for (child in children) SettingsMotion.collapse(child) { }
        }
        return true
    }

    fun collapseAnchor(activity: Activity): Boolean =
        anchors[activity]?.get()?.let { collapseRow(it) } ?: false

    /** True when the subtree holds a non-cancelable panel box. */
    private fun hasProtectedContent(root: View): Boolean {
        if (root.getTag(io.github.qauxv.R.id.qself_inline_cancelable) == false) return true
        if (root !is ViewGroup) return false
        return (0 until root.childCount).any { hasProtectedContent(root.getChildAt(it)) }
    }

    /* ----------------------------------------------------------- present */

    private val fragmentClosers = WeakHashMap<androidx.fragment.app.Fragment, () -> Unit>()

    @JvmStatic
    fun finishFragment(fragment: androidx.fragment.app.Fragment): Boolean {
        val close = fragmentClosers.remove(fragment) ?: return false
        close()
        return true
    }

    @JvmStatic
    fun presentFragment(activity: androidx.fragment.app.FragmentActivity, fragment: androidx.fragment.app.Fragment): Boolean {
        if (!available(activity) || activity.supportFragmentManager.isStateSaved) return false
        val frame = FrameLayout(activity).apply {
            id = View.generateViewId()
            minimumHeight = SettingsVisuals.dp(activity, 240)
        }
        // Legacy pages own a RecyclerView/ScrollView: bound them inside the
        // expansion instead of measuring a whole database/log into the list.
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(frame, LinearLayout.LayoutParams(-1,
                minOf(SettingsVisuals.dp(activity, 600),
                    (resources.displayMetrics.heightPixels * 0.7f).toInt())))
        }
        fragment.arguments = android.os.Bundle(fragment.arguments ?: android.os.Bundle()).apply {
            putBoolean("qself.inline", true)
        }
        val close = show(activity, wrapper, Runnable {
            fragmentClosers.remove(fragment)
            if (fragment.isAdded && !activity.supportFragmentManager.isDestroyed) {
                activity.supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
            }
        }) ?: return false
        fragmentClosers[fragment] = close
        activity.supportFragmentManager.beginTransaction().add(frame.id, fragment).commit()
        return true
    }

    /* -------------------------------------------------------------- show */

    /** The expanded card a view lives in, or null at the top level. */
    private fun expandedContainer(view: View): Any? {
        var current: View? = view
        while (current != null) {
            if (current is SettingsAccordion) return current
            current = current.parent as? View
        }
        return null
    }

    @JvmStatic
    @JvmOverloads
    fun show(context: Context, content: View, onClose: Runnable? = null,
             cancelable: Boolean = true, onCancel: Runnable? = null): (() -> Unit)? {
        val activity = UiAgentItem.findActivity(context) ?: return null
        val fallback = fallbacks[activity]?.get()?.takeIf { it.isAttachedToWindow } ?: return null

        // Single-panel contract: a new panel closes every existing one, so fast
        // taps across rows can never stack or drill through panels.
        panels[activity]?.toList()?.asReversed()?.forEach { runCatching { it.requestClose() } }

        var target = anchors[activity]?.get()?.takeIf { it.isAttachedToWindow } ?: fallback
        // A control inside a row anchors to the row, not its label column.
        var ancestor: View? = target
        while (ancestor != null) {
            if (ancestor is TitleValueCell) {
                target = ancestor
                break
            }
            ancestor = ancestor.parent as? View
        }

        val box = LinearLayout(content.context).apply {
            orientation = LinearLayout.VERTICAL
            setTag(io.github.qauxv.R.id.qself_inline_cancelable, cancelable)
            setPadding(0, 0, 0, SettingsVisuals.dp(context, 6))
            isClickable = true
        }
        (content.parent as? ViewGroup)?.removeView(content)
        box.addView(content, LinearLayout.LayoutParams(-1, -2))
        if (cancelable) box.addView(buildCloseStrip(content, box, activity, onCancel),
            LinearLayout.LayoutParams(-1, -2))

        when {
            target is TitleValueCell ->
                target.inlineContent.addView(box, LinearLayout.LayoutParams(-1, -2))
            target.parent is LinearLayout ->
                (target.parent as LinearLayout).let { parent ->
                    parent.addView(box, parent.indexOfChild(target) + 1, LinearLayout.LayoutParams(-1, -2))
                }
            fallback is LinearLayout ->
                fallback.addView(box, LinearLayout.LayoutParams(-1, -2))
            else -> return null
        }

        val panel = Panel(activity, box, cancelable, onClose, onCancel)
        panels.getOrPut(activity) { mutableListOf() }.add(panel)
        box.addOnAttachStateChangeListener(panel.detachListener)
        // Probe: which anchor shape the panel attached to, so a wrong host
        // (row vs card vs fallback) is visible in the journal.
        sumicya.qself.diagnostics.FeatureJournal.record("UI", "panel.open",
            "anchor=${target.javaClass.simpleName} cancelable=$cancelable")
        // The panel is a level inside the card it expanded, if any.
        openLevel(activity, box, expandedContainer(box)) { panel.back() }

        // Card body and panel grow through the same motion.
        SettingsMotion.expandBody(box)
        return { panel.requestClose() }
    }

    /**
     * One affordance for "close": a full-bleed strip with an up chevron. It
     * reads as "fold upward" instead of a third labelled button, and the whole
     * strip is the touch target.
     */
    private fun buildCloseStrip(content: View, box: LinearLayout, activity: Activity,
                                onCancel: Runnable?): FrameLayout {
        val palette = SettingsVisuals.palette(content.context, 2)
        return FrameLayout(content.context).apply {
            contentDescription = "收起当前展开内容"
            isClickable = true
            isFocusable = true
            minimumHeight = SettingsVisuals.dp(content.context, 44)
            foreground = SettingsVisuals.rowStateLayer(content.context, palette, 0)
            setOnClickListener {
                if (onCancel != null) onCancel.run()
                else panels[activity]?.firstOrNull { it.box === box }?.requestClose()
            }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
            addView(ImageView(content.context).apply {
                setImageResource(io.github.qauxv.R.drawable.qself_expand_more)
                rotation = 180f // down chevron -> up chevron
                setColorFilter(palette.secondary)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(SettingsVisuals.dp(content.context, 24),
                SettingsVisuals.dp(content.context, 24), Gravity.CENTER))
        }
    }
}
