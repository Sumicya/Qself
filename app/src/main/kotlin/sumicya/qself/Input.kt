// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.RelativeLayout
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 不搬移、不隐藏输入或按钮；玻璃只覆盖真实 EditText，不再给整个按钮父框铺底。 */
private val inputs = WeakHashMap<ViewGroup, WeakReference<NativeInput>>()

fun tgInput(strip: ViewGroup) {
    store()?.let { prefs ->
        if (!prefs.getBoolean("tg-input-native-layout", false)) {
            prefs.edit().putBoolean("TG输入栏", true).putBoolean("tg-input-native-layout", true).apply()
        }
    }
    inputs[strip]?.get()?.let { it.sync(); return }
    fun findEdit(view: View): EditText? {
        if (view is EditText) return view
        if (view.javaClass.name.let { "RecyclerView" in it || "ListView" in it || "ViewPager" in it }) return null
        if (view is ViewGroup) for (i in 0 until view.childCount) findEdit(view.getChildAt(i))?.let { return it }
        return null
    }
    val edit = generateSequence(strip.parent as? ViewGroup) { it.parent as? ViewGroup }
        .take(4).firstNotNullOfOrNull { findEdit(it) } ?: return
    inputs[strip] = WeakReference(NativeInput(strip, edit).also { it.sync() })
}

/** 只分配现有按钮之外的剩余空隙，不改按钮宽度/weight，不挤掉原生触摸区。 */
internal fun inputButtonGap(available: Int, occupied: Int, count: Int, desired: Int): Int =
    if (count < 2) 0 else minOf(desired, ((available - occupied) / (count - 1)).coerceAtLeast(0))

private class InputDecoration(view: View, private val hide: Boolean = false) {
    private val view = WeakReference(view)
    private val background = view.background
    private val foreground = view.foreground
    private val visibility = view.visibility
    fun apply() { view.get()?.let {
        if (hide) { if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE }
        else { if (it.background != null) it.background = null; if (it.foreground != null) it.foreground = null }
    } }
    fun restore() { view.get()?.let { it.background = background; it.foreground = foreground; if (hide) it.visibility = visibility } }
}

private class ButtonMargin(view: View, val params: ViewGroup.MarginLayoutParams) {
    private val view = WeakReference(view)
    private val start = params.marginStart
    private val end = params.marginEnd
    fun apply(gap: Int) {
        val view = view.get() ?: return
        if (view.layoutParams !== params || params.marginStart == gap && params.marginEnd == 0) return
        params.marginStart = gap; params.marginEnd = 0; view.layoutParams = params
    }
    fun restore() { view.get()?.let { if (it.layoutParams === params) {
        params.marginStart = start; params.marginEnd = end; it.layoutParams = params
    } } }
}

private class NativeInput(strip: ViewGroup, edit: EditText) :
    ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
    private val strip = WeakReference(strip)
    private val edit = WeakReference(edit)
    private val background = edit.background
    private val outline = edit.outlineProvider
    private val clip = edit.clipToOutline
    private val originalTranslationZ = edit.translationZ
    private val glass = Glass(edit, 22 * edit.dp)
    private val shape = capsule(22 * edit.dp)
    private val margins = WeakHashMap<View, ButtonMargin>()
    private val decorations = ArrayList<InputDecoration>()
    private var applied = false
    private var focused = edit.hasFocus()
    private var focusAnimator: ValueAnimator? = null
    init {
        // 真包布局 r/i/{ez,k_...}.xml：InputLinearLayout 和图标带各有一层实色背景。
        decorations.add(InputDecoration(strip))
        val row = generateSequence(edit.parent as? ViewGroup) { it.parent as? ViewGroup }
            .take(4).firstOrNull { it.javaClass.name.endsWith(".InputLinearLayout") }
        if (row != null) {
            generateSequence(edit.parent as? ViewGroup) { it.parent as? ViewGroup }
                .takeWhile { it !== row.parent }.forEach { decorations.add(InputDecoration(it)) }
            // 只处理明确与输入行对齐的 QQBlurView；不清整页聊天背景，不碰内容/回复面板。
            (row.parent as? ViewGroup)?.let { parent -> for (i in 0 until parent.childCount) {
                val view = parent.getChildAt(i)
                val lp = view.layoutParams as? RelativeLayout.LayoutParams ?: continue
                if (view.javaClass.name.endsWith(".QQBlurView") && row.id != View.NO_ID &&
                    lp.rules[RelativeLayout.ALIGN_TOP] == row.id && lp.rules[RelativeLayout.ALIGN_PARENT_BOTTOM] != 0)
                    decorations.add(InputDecoration(view, true))
            } }
        }
        strip.viewTreeObserver.addOnPreDrawListener(this)
        strip.viewTreeObserver.addOnGlobalLayoutListener(this)
        strip.addOnAttachStateChangeListener(this)
    }
    fun sync() {
        val edit = edit.get() ?: return
        if (!on("TG输入栏")) { restore(); return }
        decorations.forEach { it.apply() }
        if (edit.background !== glass) edit.background = glass
        if (edit.outlineProvider !== shape) edit.outlineProvider = shape
        if (!edit.clipToOutline) edit.clipToOutline = true
        glass.sync()
        val focus = edit.hasFocus()
        if (!applied || focused != focus) {
            focused = focus; focusAnimator?.cancel()
            val target = originalTranslationZ + if (focus) 2 * edit.dp else 0f
            if (!ValueAnimator.areAnimatorsEnabled()) edit.translationZ = target
            else focusAnimator = ValueAnimator.ofFloat(edit.translationZ, target).apply {
                duration = 140
                addUpdateListener { this@NativeInput.edit.get()?.translationZ = it.animatedValue as Float }
                start()
            }
        }
        if (!applied) { applied = true; onGlobalLayout() }
    }
    override fun onGlobalLayout() {
        if (!on("TG输入栏")) return
        val strip = strip.get() ?: return
        if (strip.width <= 0) return
        val children = (0 until strip.childCount).map(strip::getChildAt).filter { it.visibility == View.VISIBLE }
        if (children.any { it.width <= 0 }) return
        val gap = inputButtonGap(strip.width - strip.paddingLeft - strip.paddingRight,
            children.sumOf { it.width }, children.size, (8 * strip.dp).toInt())
        children.forEachIndexed { index, view ->
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEachIndexed
            val state = margins[view]?.takeIf { it.params === lp } ?: ButtonMargin(view, lp).also { margins[view] = it }
            state.apply(if (index == 0) 0 else gap)
        }
    }
    private fun restore() {
        if (!applied) return
        focusAnimator?.cancel(); focusAnimator = null
        edit.get()?.let {
            it.background = background; it.outlineProvider = outline; it.clipToOutline = clip
            it.translationZ = originalTranslationZ
        }
        decorations.forEach { it.restore() }
        margins.values.forEach { it.restore() }; margins.clear()
        applied = false
    }
    override fun onPreDraw(): Boolean { sync(); return true }
    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) = dispose()
    fun dispose() {
        restore(); glass.dispose()
        val view = strip.get() ?: return
        if (view.viewTreeObserver.isAlive) {
            view.viewTreeObserver.removeOnPreDrawListener(this)
            view.viewTreeObserver.removeOnGlobalLayoutListener(this)
        }
        view.removeOnAttachStateChangeListener(this)
        inputs.remove(view)
    }
}

fun resetInputLayout() {
    inputs.values.mapNotNull { it.get() }.forEach { it.dispose() }
    inputs.clear()
}
