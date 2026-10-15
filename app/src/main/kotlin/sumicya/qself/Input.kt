// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.EditText
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 原生输入区玻璃化：不搬输入框、不替换按钮、不藏图标带、不压缩任何兄弟容器。 */
private val inputs = WeakHashMap<ViewGroup, WeakReference<NativeInput>>()

fun tgInput(strip: ViewGroup) {
    // 本次修复按要求重新启用一次；此后用户关闭仍受尊重，不在每次扫描时强开。
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
    // 在图标带最近的输入区祖先里找真正的 EditText，不把 TextView 占位文字误认成输入框。
    val edit = generateSequence(strip.parent as? ViewGroup) { it.parent as? ViewGroup }
        .take(4).firstNotNullOfOrNull { findEdit(it) } ?: return
    val box = edit.parent as? ViewGroup ?: return
    inputs[strip] = WeakReference(NativeInput(strip, edit, box).also { it.sync() })
}

private class NativeInput(strip: ViewGroup, edit: EditText, box: ViewGroup) :
    ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private val strip = WeakReference(strip)
    private val edit = WeakReference(edit)
    private val box = WeakReference(box)
    private val boxBackground: Drawable? = box.background
    private val editBackground: Drawable? = edit.background
    private val outline: ViewOutlineProvider? = box.outlineProvider
    private val clip = box.clipToOutline
    private val boxMinimum = box.minimumHeight
    private val editMinimum = edit.minimumHeight
    private val glass = Glass(box, 22 * box.dp)
    private val shape = capsule(22 * box.dp)
    private var applied = false
    private var focusAnimator: ValueAnimator? = null
    private var focused = edit.hasFocus()
    private val originalTranslationZ = box.translationZ

    init {
        strip.viewTreeObserver.addOnPreDrawListener(this)
        strip.addOnAttachStateChangeListener(this)
    }
    fun sync() {
        val edit = edit.get() ?: return
        val box = box.get() ?: return
        if (!on("TG输入栏")) { restore(); return }
        if (box.background !== glass) box.background = glass
        if (edit.background != null) edit.background = null
        if (box.outlineProvider !== shape) box.outlineProvider = shape
        if (!box.clipToOutline) box.clipToOutline = true
        val boxHeight = maxOf(boxMinimum, (48 * box.dp).toInt())
        val editHeight = maxOf(editMinimum, (40 * edit.dp).toInt())
        if (box.minimumHeight != boxHeight) box.minimumHeight = boxHeight
        if (edit.minimumHeight != editHeight) edit.minimumHeight = editHeight
        glass.sync()
        val focus = edit.hasFocus()
        if (!applied || focused != focus) {
            focused = focus
            focusAnimator?.cancel()
            val target = originalTranslationZ + if (focus) 2 * box.dp else 0f
            if (!ValueAnimator.areAnimatorsEnabled()) box.translationZ = target
            else focusAnimator = ValueAnimator.ofFloat(box.translationZ, target).apply {
                duration = 140
                addUpdateListener { this@NativeInput.box.get()?.translationZ = it.animatedValue as Float }
                start()
            }
        }
        applied = true
    }
    private fun restore() {
        if (!applied) return
        box.get()?.let {
            focusAnimator?.cancel(); focusAnimator = null; it.translationZ = originalTranslationZ
            it.background = boxBackground
            it.outlineProvider = outline
            it.clipToOutline = clip
            it.minimumHeight = boxMinimum
        }
        edit.get()?.let { it.background = editBackground; it.minimumHeight = editMinimum }
        applied = false
    }
    override fun onPreDraw(): Boolean { sync(); return true }
    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) = dispose()
    fun dispose() {
        restore()
        glass.dispose()
        val view = strip.get() ?: return
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
        view.removeOnAttachStateChangeListener(this)
        strip.get()?.let { inputs.remove(it) }
    }
}

fun resetInputLayout() {
    inputs.values.mapNotNull { it.get() }.forEach { it.dispose() }
    inputs.clear()
}
