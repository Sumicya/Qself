// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * QQ 的真实编辑框、发送和附件按钮必须留在 QQ 自己的父容器里。
 * 先前把编辑框搬进镜像行后，QQ 的触摸/布局链失效，还留下原框的矩形背景。
 * 这里只替换原框的材质；不再重排或复制任何负责输入的控件。
 */
private val fields = WeakHashMap<View, InputGlass>()

fun tgInput(strip: ViewGroup) {
    val old = fields[strip]
    if (!on("TG输入栏")) {
        old?.restore()
        fields.remove(strip)
        return
    }
    if (old?.active() == true) { old.sync(); return }
    val scope = strip.parent as? ViewGroup ?: return
    val edit = findInput(scope, strip) ?: return
    val box = edit.parent as? ViewGroup ?: return
    // 只动原生编辑框的背景，不重挂节点，也不隐藏 QQ 的按钮与事件监听。
    fields[strip] = InputGlass(strip, edit, box, box.parent as? ViewGroup)
}

private fun findInput(scope: View, strip: View): EditText? {
    val y = IntArray(2).also(strip::getLocationInWindow)[1]
    val candidates = ArrayList<EditText>(2)
    fun collect(v: View) {
        if (v is EditText && v.isShown && v.height > 0 &&
            abs(IntArray(2).also(v::getLocationInWindow)[1] - y) < 120 * strip.dp) candidates += v
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
    }
    collect(scope)
    // 同一输入行里优先认 QQ 的 input 资源；不能因 NO_ID 就拿到别的编辑框。
    return candidates.minByOrNull { v ->
        val input = v.id != View.NO_ID && runCatching {
            v.resources.getResourceEntryName(v.id) == "input"
        }.getOrDefault(false)
        (if (input) 0 else 10000) + abs(IntArray(2).also(v::getLocationInWindow)[1] - y)
    }
}

private class InputGlass(private val strip: View, private val edit: EditText, private val box: ViewGroup,
                         private val frame: ViewGroup?) {
    private val dp = box.dp
    private val editBg: Drawable? = edit.background
    private val boxBg: Drawable? = box.background
    private val frameBg: Drawable? = frame?.background
    private val outline = box.outlineProvider
    private val clip = box.clipToOutline
    private val glass = Glass(box, 22 * dp)
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) { restore(); fields.remove(strip) }
    }

    init {
        strip.addOnAttachStateChangeListener(detach)
        box.outlineProvider = capsule(22 * dp)
        // Glass 自己裁镜片；不要裁 QQ 原生编辑框和附件按钮的内容与触摸区域。
        box.clipToOutline = false
        sync()
    }

    fun active() = edit.parent === box

    fun sync() {
        if (edit.background != null) edit.background = null
        if (box.background !== glass) box.background = glass
        if (frame != null && frame !== box && frame.height in 1..(120 * dp).toInt() && frame.background != null)
            frame.background = null
    }

    fun restore() {
        strip.removeOnAttachStateChangeListener(detach)
        if (edit.background == null) edit.background = editBg
        if (box.background === glass) box.background = boxBg
        if (frame != null && frame !== box && frame.background == null) frame.background = frameBg
        box.outlineProvider = outline
        box.clipToOutline = clip
    }
}
