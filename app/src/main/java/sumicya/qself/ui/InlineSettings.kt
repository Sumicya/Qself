/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.qauxv.dsl.cell.TitleValueCell
import io.github.qauxv.dsl.item.UiAgentItem
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Module-owned settings only. No global Dialog hooks and no hidden secondary windows. */
object InlineSettings {
    private val anchors = WeakHashMap<Activity, WeakReference<View>>()
    private val fallbacks = WeakHashMap<Activity, WeakReference<View>>()
    private val closers = WeakHashMap<Activity, MutableList<() -> Unit>>()
    // One ordered level stack per host: expanded cards and row panels share it, so back
    // unwinds them by open order instead of only knowing about panels.
    private val levels = WeakHashMap<Activity, SettingsLevelStack>()
    // A closer returns true when it actually closed; a protected (in-flight) panel refuses
    // and the press is consumed instead, exactly like the previous closeLast contract.
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
     *
     * Returns false only when nothing is open, so the caller can fall through to leaving the
     * screen. A protected (non-cancelable) level refuses to close and consumes the press.
     */
    @JvmStatic
    fun back(activity: Activity): Boolean {
        val token = levels[activity]?.top() ?: return false
        val closer = levelClosers[activity]?.get(token)
        if (closer == null) { closeLevel(activity, token); return true } // stale entry: drop it
        val closed = closer.invoke()
        // A protected level refuses to close and the press is consumed instead (the established
        // contract). Every other level must make progress, so a closer that forgot to unregister
        // itself can never leave back stuck on one level.
        if (closed && levels[activity]?.top() === token) closeLevel(activity, token)
        return true
    }

    /** A container that collapsed must take its open levels with it, innermost first. */
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
    @JvmStatic fun register(activity: Activity, fallback: View) { fallbacks[activity] = WeakReference(fallback) }
    @JvmStatic fun unregister(activity: Activity) {
        closers[activity]?.toList()?.asReversed()?.forEach { it() }
        anchors.remove(activity); fallbacks.remove(activity); closers.remove(activity)
        levels[activity]?.clear(); levels.remove(activity); levelClosers.remove(activity)
    }
    @JvmStatic fun anchor(view: View) {
        UiAgentItem.findActivity(view.context)?.let {
            anchors[it] = WeakReference(view)
            if (it is io.github.qauxv.activity.SettingsUiFragmentHostActivity && !available(it)) fallbacks[it] = WeakReference(view)
        }
    }
    @JvmStatic fun resetAnchor(activity: Activity) { anchors.remove(activity) }
    /**
     * Closes the panel hosted by [row] exactly like back does - through its registered level
     * closer, so no stale level is left behind. Returns true when the tap was consumed here.
     */
    @JvmStatic fun collapseRow(view: View): Boolean {
        val row = view as? TitleValueCell ?: return false
        if (row.inlineContent.childCount == 0) return false
        fun blocked(view: View): Boolean {
            if (view.getTag(io.github.qauxv.R.id.qself_inline_cancelable) == false) return true
            return view is ViewGroup && (0 until view.childCount).any { blocked(view.getChildAt(it)) }
        }
        // A protected (non-cancelable) panel keeps its content and swallows the re-tap.
        if (blocked(row.inlineContent)) return true
        val activity = io.github.qauxv.dsl.item.UiAgentItem.findActivity(row.context)
        val children = (0 until row.inlineContent.childCount).map { row.inlineContent.getChildAt(it) }
        for (child in children) {
            val closer = activity?.let { levelClosers[it]?.get(child) }
            if (closer != null) closer() else SettingsMotion.collapse(child) { }
        }
        return true
    }

    fun collapseAnchor(activity: Activity): Boolean = anchors[activity]?.get()?.let { collapseRow(it) } ?: false
    @JvmStatic fun available(context: Context): Boolean = UiAgentItem.findActivity(context)?.let { fallbacks[it]?.get()?.isAttachedToWindow == true } == true
    private val fragmentClosers = WeakHashMap<androidx.fragment.app.Fragment, () -> Unit>()
    @JvmStatic fun finishFragment(fragment: androidx.fragment.app.Fragment): Boolean {
        val close = fragmentClosers.remove(fragment) ?: return false
        close(); return true
    }
    @JvmStatic fun presentFragment(activity: androidx.fragment.app.FragmentActivity, fragment: androidx.fragment.app.Fragment): Boolean {
        if (!available(activity) || activity.supportFragmentManager.isStateSaved) return false
        val frame = android.widget.FrameLayout(activity).apply {
            id = View.generateViewId()
            minimumHeight = SettingsVisuals.dp(activity, 240)
        }
        // Legacy pages own a RecyclerView/ScrollView. Keep them bounded inside the expansion,
        // rather than allocating the height of an entire database/log in the outer list.
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(frame, LinearLayout.LayoutParams(-1, minOf(SettingsVisuals.dp(activity, 600), (resources.displayMetrics.heightPixels * .7f).toInt())))
        }
        fragment.arguments = android.os.Bundle(fragment.arguments ?: android.os.Bundle()).apply { putBoolean("qself.inline", true) }
        val close = show(activity, wrapper, Runnable {
            fragmentClosers.remove(fragment)
            if (fragment.isAdded && !activity.supportFragmentManager.isDestroyed) activity.supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
        }) ?: return false
        fragmentClosers[fragment] = close
        activity.supportFragmentManager.beginTransaction().add(frame.id, fragment).commit()
        return true
    }

    /** The category card a panel lives in, or null at the top level. */
    private fun expandedContainer(view: View): Any? {
        var current: View? = view
        while (current != null) {
            if (current is SettingsAccordion) return current
            current = current.parent as? View
        }
        return null
    }

    @JvmStatic @JvmOverloads fun show(context: Context, content: View, onClose: Runnable? = null, cancelable: Boolean = true, onCancel: Runnable? = null): (() -> Unit)? {
        val activity = UiAgentItem.findActivity(context) ?: return null
        val fallback = fallbacks[activity]?.get()?.takeIf { it.isAttachedToWindow } ?: return null
        // Single-panel contract: opening a new panel closes every existing one,
        // so rapid taps on several anchors can never stack or drill through panels.
        closers[activity]?.toList()?.asReversed()?.forEach { runCatching { it() } }
        var target = anchors[activity]?.get()?.takeIf { it.isAttachedToWindow } ?: fallback
        // A text/icon inside a row anchors to that row, not to its internal label column.
        var ancestor: View? = target
        while (ancestor != null) {
            if (ancestor is TitleValueCell) { target = ancestor; break }
            ancestor = ancestor.parent as? View
        }
        val box = LinearLayout(content.context).apply {
            orientation = LinearLayout.VERTICAL
            setTag(io.github.qauxv.R.id.qself_inline_cancelable, cancelable)
            // Part of the surrounding card, not a nested rounded container.
            background = null
            setPadding(0, 0, 0, SettingsVisuals.dp(context, 6))
            isClickable = true
        }
        (content.parent as? ViewGroup)?.removeView(content)
        box.addView(content, LinearLayout.LayoutParams(-1, -2))
        var closed = false
        var closing = false
        lateinit var close: () -> Unit
        close = {
            if (!closed && !closing) {
                closing = true
                closers[activity]?.remove(close)
                closeLevel(activity, box)
                SettingsMotion.collapse(box) {
                    closed = true
                    closing = false
                    onClose?.run()
                }
            }
        }
        val cancel = { if (onCancel != null) onCancel.run() else close() }
        if (cancelable) {
            // One affordance, not a titled button: a full-bleed strip with an up chevron at the
            // bottom of the panel. It reads as "close upward" instead of adding a third label,
            // and its state layer spans the panel so the whole strip is the touch target.
            val palette = SettingsVisuals.palette(content.context, 2)
            val strip = android.widget.FrameLayout(content.context).apply {
                contentDescription = "收起当前展开内容"
                isClickable = true
                isFocusable = true
                minimumHeight = SettingsVisuals.dp(content.context, 44)
                foreground = SettingsVisuals.rowStateLayer(content.context, palette, 0)
                setOnClickListener { cancel() }
                accessibilityDelegate = object : android.view.View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.Button::class.java.name
                    }
                }
            }
            strip.addView(android.widget.ImageView(content.context).apply {
                setImageResource(io.github.qauxv.R.drawable.qself_expand_more)
                rotation = 180f // down chevron -> up chevron
                setColorFilter(palette.secondary)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, android.widget.FrameLayout.LayoutParams(SettingsVisuals.dp(content.context, 24),
                SettingsVisuals.dp(content.context, 24), android.view.Gravity.CENTER))
            box.addView(strip, LinearLayout.LayoutParams(-1, -2))
        }
        when {
            target is TitleValueCell -> target.inlineContent.addView(box, LinearLayout.LayoutParams(-1, -2))
            target.parent is LinearLayout -> (target.parent as LinearLayout).let { parent ->
                parent.addView(box, parent.indexOfChild(target) + 1, LinearLayout.LayoutParams(-1, -2))
            }
            fallback is LinearLayout -> fallback.addView(box, LinearLayout.LayoutParams(-1, -2))
            else -> return null
        }
        // Register the panel as a level: the container is the expanded card it lives in, if any.
        openLevel(activity, box, expandedContainer(box), {
            if (cancelable) { cancel(); true } else false
        })
        run {
            lateinit var backAction: () -> Unit
            backAction = { if (cancelable) { closers[activity]?.remove(backAction); cancel() } }
            closers.getOrPut(activity) { mutableListOf() }.add(backAction)
            box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { closers[activity]?.remove(backAction); close() }
            })
        }
        // The card body and this panel grow through the same call, so opening a row and opening
        // a section are the same motion.
        SettingsMotion.expandBody(box)
        return close
    }
}
