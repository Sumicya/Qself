/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import io.github.qauxv.dsl.cell.TitleValueCell
import io.github.qauxv.dsl.item.UiAgentItem
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Module-owned settings only. No global Dialog hooks and no hidden secondary windows. */
object InlineSettings {
    private val anchors = WeakHashMap<Activity, WeakReference<View>>()
    private val fallbacks = WeakHashMap<Activity, WeakReference<View>>()
    private val closers = WeakHashMap<Activity, MutableList<() -> Unit>>()
    @JvmStatic fun register(activity: Activity, fallback: View) { fallbacks[activity] = WeakReference(fallback) }
    @JvmStatic fun unregister(activity: Activity) {
        closers[activity]?.toList()?.asReversed()?.forEach { it() }
        anchors.remove(activity); fallbacks.remove(activity); closers.remove(activity)
    }
    @JvmStatic fun anchor(view: View) {
        UiAgentItem.findActivity(view.context)?.let { anchors[it] = WeakReference(view) }
    }
    @JvmStatic fun available(context: Context): Boolean = UiAgentItem.findActivity(context)?.let { fallbacks[it]?.get()?.isAttachedToWindow == true } == true
    @JvmStatic fun closeLast(activity: Activity): Boolean {
        val list = closers[activity] ?: return false
        val close = list.lastOrNull() ?: return false
        close(); return true
    }
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

    @JvmStatic @JvmOverloads fun show(context: Context, content: View, onClose: Runnable? = null, cancelable: Boolean = true, onCancel: Runnable? = null): (() -> Unit)? {
        val activity = UiAgentItem.findActivity(context) ?: return null
        val fallback = fallbacks[activity]?.get()?.takeIf { it.isAttachedToWindow } ?: return null
        var target = anchors[activity]?.get()?.takeIf { it.isAttachedToWindow } ?: fallback
        // A text/icon inside a row anchors to that row, not to its internal label column.
        var ancestor: View? = target
        while (ancestor != null && ancestor !== fallback) {
            if (ancestor is TitleValueCell) { target = ancestor; break }
            ancestor = ancestor.parent as? View
        }
        val box = LinearLayout(content.context).apply {
            orientation = LinearLayout.VERTICAL
            val p = SettingsVisuals.palette(context, 2)
            background = SettingsVisuals.surface(context, p, 20)
            setPadding(0, SettingsVisuals.dp(context, 8), 0, SettingsVisuals.dp(context, 8))
            isClickable = true
        }
        (content.parent as? ViewGroup)?.removeView(content)
        box.addView(content, LinearLayout.LayoutParams(-1, -2))
        var closed = false
        lateinit var close: () -> Unit
        close = {
            if (!closed) {
                closed = true
                (box.parent as? ViewGroup)?.removeView(box)
                closers[activity]?.remove(close)
                onClose?.run()
            }
        }
        val cancel = { if (onCancel != null) onCancel.run() else close() }
        if (cancelable) box.addView(MaterialButton(content.context).apply {
            text = "收起"; contentDescription = "收起当前展开内容"
            setOnClickListener { cancel() }
        }, LinearLayout.LayoutParams(-1, -2))
        when {
            target is TitleValueCell -> target.inlineContent.addView(box, LinearLayout.LayoutParams(-1, -2))
            target.parent is LinearLayout -> (target.parent as LinearLayout).let { parent ->
                parent.addView(box, parent.indexOfChild(target) + 1, LinearLayout.LayoutParams(-1, -2))
            }
            fallback is LinearLayout -> fallback.addView(box, LinearLayout.LayoutParams(-1, -2))
            else -> return null
        }
        if (cancelable) {
            lateinit var backAction: () -> Unit
            backAction = { closers[activity]?.remove(backAction); cancel() }
            closers.getOrPut(activity) { mutableListOf() }.add(backAction)
            box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { closers[activity]?.remove(backAction); close() }
            })
        }
        SettingsMotion.enter(box)
        return close
    }
}
