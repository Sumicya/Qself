/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import io.github.qauxv.R
import io.github.qauxv.dsl.cell.TitleValueCell
import io.github.qauxv.dsl.item.UiAgentItem
import sumicya.qself.diagnostics.FeatureJournal
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The module's in-place settings host: no dialogs, no extra windows.
 *
 * One [HostState] per activity holds everything the host owns — open panels,
 * the anchor view, the fallback view, the level stack and the fragment closers.
 * A single state object means a panel can never be mounted while its level
 * entry lives in a different registry, which is what used to strand the back
 * key.
 *
 * A [Panel] is the only object allowed to close itself, and it can do so
 * exactly once from three routes: its close strip, back, or host teardown.
 */
object InlineSettings {

    /* ------------------------------------------------------------- state */

    /** Everything the module hosts inside one activity. */
    private class HostState {
        val panels = mutableListOf<Panel>()
        var anchor: WeakReference<View>? = null
        var fallback: WeakReference<View>? = null
        val levels = SettingsLevelStack()
        val closers = mutableMapOf<Any, () -> Boolean>()

        fun clear() {
            panels.clear()
            anchor = null
            fallback = null
            levels.clear()
            closers.clear()
        }
    }

    private val hosts = WeakHashMap<Activity, HostState>()

    /** Pending closers for inline fragments, keyed by the fragment itself. */
    private val fragmentClosers = WeakHashMap<Fragment, () -> Unit>()

    private fun state(activity: Activity) = hosts.getOrPut(activity) { HostState() }

    /* ------------------------------------------------------------- panels */

    /**
     * One open panel. It owns its box view, its level entry and its close path:
     * one lifecycle, one owner, no scattered closures.
     */
    private class Panel(
        private val activity: Activity,
        val box: LinearLayout,
        private val cancelable: Boolean,
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

        /**
         * Animated close. The shrink animation may be cancelled by a host
         * rebuild; the guard in the end callback keeps [closing] honest either
         * way and [onClose] runs exactly once.
         */
        fun requestClose() {
            if (closing) return
            closing = true
            FeatureJournal.record("UI", "panel.close", "animated=true")
            unregisterFrom(activity)
            SettingsMotion.collapse(box) { onClose?.run() }
        }

        /** Synchronous close for host teardown: no animation on a leaving tree. */
        fun closeNow() {
            if (closing) return
            closing = true
            FeatureJournal.record("UI", "panel.close", "animated=false")
            (box.parent as? ViewGroup)?.removeView(box)
            onClose?.run()
        }

        /** Back on this panel: protected panels refuse and consume the press. */
        fun back(): Boolean {
            if (!cancelable) {
                FeatureJournal.record("UI", "panel.back", "refused=protected")
                return false
            }
            if (onCancel != null) onCancel.run() else requestClose()
            return true
        }

        fun unregisterFrom(activity: Activity) {
            val host = hosts[activity] ?: return
            host.panels.remove(this)
            host.closers.remove(box)
            host.levels.remove(box)
            box.removeOnAttachStateChangeListener(detachListener)
        }
    }

    /* ------------------------------------------------------------- levels */

    /** Registers an opened level; [collapse] performs its animated close. */
    @JvmStatic
    fun openLevel(activity: Activity, token: Any, container: Any?, collapse: () -> Boolean) {
        val host = state(activity)
        host.levels.push(token, container)
        host.closers[token] = collapse
    }

    /** Removes a level that closed by any route. */
    @JvmStatic
    fun closeLevel(activity: Activity, token: Any) {
        hosts[activity]?.let {
            it.levels.remove(token)
            it.closers.remove(token)
        }
    }

    @JvmStatic
    fun hasLevels(activity: Activity): Boolean = hosts[activity]?.levels?.isEmpty == false

    /**
     * Back: close the most recently opened level, whatever kind it is. Returns
     * false only when nothing is open, so the caller can leave the screen. A
     * protected level refuses to close and consumes the press.
     */
    @JvmStatic
    fun back(activity: Activity): Boolean {
        val host = hosts[activity] ?: return false
        val token = host.levels.top() ?: return false
        val closer = host.closers[token]
        if (closer == null) {
            closeLevel(activity, token) // stale entry: drop it, make progress
            return true
        }
        val closed = closer.invoke()
        // A protected level swallows the press; everything else must make
        // progress so a forgotten closer can never strand the back key.
        if (closed && host.levels.top() === token) closeLevel(activity, token)
        return true
    }

    /** A container that collapses must take its open levels with it, innermost first. */
    @JvmStatic
    fun collapseLevelsInside(activity: Activity, container: View): Boolean {
        val host = hosts[activity] ?: return false
        val tokens = host.levels.inside(container)
        var any = false
        for (token in tokens) {
            val collapse = host.closers[token] ?: continue
            if (collapse()) any = true
        }
        return any
    }

    /* ------------------------------------------------------- registration */

    @JvmStatic
    fun register(activity: Activity, fallback: View) {
        state(activity).fallback = WeakReference(fallback)
    }

    /** Host teardown: close every panel synchronously and drop all state. */
    @JvmStatic
    fun unregister(activity: Activity) {
        hosts.remove(activity)?.let { host ->
            host.panels.toList().asReversed().forEach {
                it.unregisterFrom(activity)
                it.closeNow()
            }
            host.clear()
        }
    }

    @JvmStatic
    fun anchor(view: View) {
        UiAgentItem.findActivity(view.context)?.let { activity ->
            val host = state(activity)
            host.anchor = WeakReference(view)
            if (activity is io.github.qauxv.activity.SettingsUiFragmentHostActivity && !available(activity)) {
                host.fallback = WeakReference(view)
            }
        }
    }

    @JvmStatic
    fun resetAnchor(activity: Activity) {
        hosts[activity]?.anchor = null
    }

    @JvmStatic
    fun available(context: Context): Boolean =
        UiAgentItem.findActivity(context)?.let { hosts[it]?.fallback?.get()?.isAttachedToWindow == true } == true

    /* -------------------------------------------------------------- close */

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
        val open = activity?.let { hosts[it]?.panels?.filter { p -> p.box.parent === row.inlineContent }?.toList() }
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
        hosts[activity]?.anchor?.get()?.let { collapseRow(it) } ?: false

    /** True when the subtree holds a non-cancelable panel box. */
    private fun hasProtectedContent(root: View): Boolean {
        if (root.getTag(R.id.qself_inline_cancelable) == false) return true
        if (root !is ViewGroup) return false
        return (0 until root.childCount).any { hasProtectedContent(root.getChildAt(it)) }
    }

    /* ------------------------------------------------------------ present */

    @JvmStatic
    fun finishFragment(fragment: Fragment): Boolean {
        val close = fragmentClosers.remove(fragment) ?: return false
        close.invoke()
        return true
    }

    /**
     * Shows a legacy settings page inside an expansion instead of a window.
     * The page is added to a scoped frame, so a heavy RecyclerView/ScrollView
     * is bounded by the expansion rather than measured into the list.
     */
    @JvmStatic
    fun presentFragment(activity: FragmentActivity, fragment: Fragment): Boolean {
        if (!available(activity) || activity.supportFragmentManager.isStateSaved) return false
        val frame = FrameLayout(activity).apply {
            id = View.generateViewId()
            minimumHeight = SettingsVisuals.dp(activity, 240)
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(frame, LinearLayout.LayoutParams(-1,
                minOf(SettingsVisuals.dp(activity, 600),
                    (resources.displayMetrics.heightPixels * 0.7f).toInt())))
        }
        fragment.arguments = Bundle(fragment.arguments ?: Bundle()).apply {
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

    /* --------------------------------------------------------------- show */

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
        val activity = UiAgentItem.findActivity(context) ?: run {
            FeatureJournal.record("UI", "panel.open", "dead=no-activity")
            return null
        }
        val host = state(activity)
        val fallback = host.fallback?.get()?.takeIf { it.isAttachedToWindow } ?: run {
            FeatureJournal.record("UI", "panel.open", "dead=no-fallback")
            return null
        }

        // Single-panel contract: a new panel closes every existing one, so fast
        // taps across rows can never stack or drill through panels.
        host.panels.toList().asReversed().forEach { runCatching { it.requestClose() } }

        var target = host.anchor?.get()?.takeIf { it.isAttachedToWindow } ?: fallback
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
            setTag(R.id.qself_inline_cancelable, cancelable)
            setPadding(0, 0, 0, SettingsVisuals.dp(context, 6))
            isClickable = true
        }
        (content.parent as? ViewGroup)?.removeView(content)
        box.addView(content, LinearLayout.LayoutParams(-1, -2))
        if (cancelable) {
            box.addView(buildCloseStrip(content, box, activity, onCancel), LinearLayout.LayoutParams(-1, -2))
        }

        if (!mount(box, target, fallback)) return null

        val panel = Panel(activity, box, cancelable, onClose, onCancel)
        host.panels.add(panel)
        box.addOnAttachStateChangeListener(panel.detachListener)
        // Probe: which anchor shape the panel attached to, so a wrong host
        // (row vs card vs fallback) is visible in the journal.
        FeatureJournal.record("UI", "panel.open",
            "anchor=${target.javaClass.simpleName} cancelable=$cancelable")
        // The panel is a level inside the card it expanded, if any.
        openLevel(activity, box, expandedContainer(box)) { panel.back() }

        // Card body and panel grow through the same motion.
        SettingsMotion.expandBody(box)
        return { panel.requestClose() }
    }

    /** Mounts the box under its anchor; false when the anchor shape is unusable. */
    private fun mount(box: LinearLayout, target: View, fallback: View): Boolean = when {
        target is TitleValueCell -> {
            target.inlineContent.addView(box, LinearLayout.LayoutParams(-1, -2))
            true
        }
        target.parent is LinearLayout -> {
            val parent = target.parent as LinearLayout
            parent.addView(box, parent.indexOfChild(target) + 1, LinearLayout.LayoutParams(-1, -2))
            true
        }
        fallback is LinearLayout -> {
            fallback.addView(box, LinearLayout.LayoutParams(-1, -2))
            true
        }
        else -> false
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
                else hosts[activity]?.panels?.firstOrNull { it.box === box }?.requestClose()
            }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
            addView(ImageView(content.context).apply {
                setImageResource(R.drawable.qself_expand_more)
                rotation = 180f // down chevron -> up chevron
                setColorFilter(palette.secondary)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(SettingsVisuals.dp(content.context, 24),
                SettingsVisuals.dp(content.context, 24), Gravity.CENTER))
        }
    }
}
