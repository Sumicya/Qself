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
    private val editPadding = intArrayOf(edit.paddingLeft, edit.paddingTop, edit.paddingRight, edit.paddingBottom)
    private val boxPadding = intArrayOf(box.paddingLeft, box.paddingTop, box.paddingRight, box.paddingBottom)
    private val framePadding = frame?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
    // 镜片放在真正接收按压的 QQ EditText 上，避免父盒子不进入 pressed、始终不亮。
    private val glass = Glass(edit, 22 * dp)
    private var frameCleared = false
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) { restore(); fields.remove(strip) }
    }

    init {
        strip.addOnAttachStateChangeListener(detach)
        sync()
    }

    fun active() = edit.parent === box

    fun sync() {
        if (edit.background !== glass) {
            edit.background = glass
            edit.setPadding(editPadding[0], editPadding[1], editPadding[2], editPadding[3])
        }
        // 外层原来的矩形背景去掉；保留 QQ 原来的间距、按钮、输入法与触摸处理。
        if (box.background != null) {
            box.background = null
            box.setPadding(boxPadding[0], boxPadding[1], boxPadding[2], boxPadding[3])
        }
        if (frame != null && frame !== box && frame.height in 1..(120 * dp).toInt() &&
            frame.background === frameBg) {
            frame.background = null
            framePadding?.let { frame.setPadding(it[0], it[1], it[2], it[3]) }
            frameCleared = true
        }
    }

    fun restore() {
        strip.removeOnAttachStateChangeListener(detach)
        if (edit.background === glass) {
            edit.background = editBg
            edit.setPadding(editPadding[0], editPadding[1], editPadding[2], editPadding[3])
        }
        if (box.background == null) {
            box.background = boxBg
            box.setPadding(boxPadding[0], boxPadding[1], boxPadding[2], boxPadding[3])
        }
        if (frameCleared) frame?.let { v ->
            if (v.background == null) {
                v.background = frameBg
                framePadding?.let { v.setPadding(it[0], it[1], it[2], it[3]) }
            }
        }
    }
}
