// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.min

/** 只接管 QQ 图标控件的绘制，TabView、标签、红点及原生点击/拖动事件仍归 QQ。 */
fun tabGlyphs() {
    val icon = cls("com.tencent.mobileqq.widget.TabDragAnimationView")
    hook(icon.getDeclaredMethod("onDraw", Canvas::class.java)) { chain ->
        val view = chain.thisObject as View
        val bar = generateSequence(view.parent) { it.parent }
            .filterIsInstance<ViewGroup>()
            .firstOrNull { it.javaClass.name == "com.tencent.mobileqq.widget.QQTabLayout" }
        val strip = bar?.getChildAt(0) as? ViewGroup
        val tab = strip?.let { p -> (0 until p.childCount).map(p::getChildAt).firstOrNull { it === view || it.isParentOf(view) } }
        val labels = tab?.let(::texts).orEmpty()
        val kind = when {
            labels.any { it == "消息" } -> 0
            labels.any { it == "联系人" } -> 1
            labels.any { it == "动态" || it == "发现" } -> 2
            labels.any { it == "我的" || it == "我" } -> 3
            else -> -1
        }
        if (kind < 0 || view.width <= 0 || view.height <= 0) chain.proceed()
        else {
            val count = if (kind == 0) unreadIn(tab!!) else 0
            // QQ 若已给出“99+”而没有原始数量，不冒充精确计数。
            if (kind == 0 && count == null) chain.proceed()
            else {
                drawTabGlyph(chain.args[0] as Canvas, view, kind, count ?: 0, tab?.isSelected == true)
                null
            }
        }
    }
}

private fun View.isParentOf(child: View): Boolean = generateSequence(child.parent) { it.parent }.any { it === this }

private val originalBadgeAlpha = WeakHashMap<View, Float>()

/** 原角标保留数据来源，只隐藏其绘制；开关关闭或源仅有 99+ 时恢复。 */
fun tabBadge(bar: ViewGroup) {
    val strip = bar.getChildAt(0) as? ViewGroup ?: return
    val tab = (0 until strip.childCount).map(strip::getChildAt)
        .firstOrNull { texts(it).any { text -> text == "消息" } } ?: return
    val replace = on("底栏数字与图标") && unreadIn(tab) != null
    fun scan(v: View) {
        if (v.javaClass.name == "com.tencent.mobileqq.tianshu.ui.RedTouch") {
            if (replace) {
                if (!originalBadgeAlpha.containsKey(v)) originalBadgeAlpha[v] = v.alpha
                if (v.alpha != 0f) v.alpha = 0f
            } else originalBadgeAlpha.remove(v)?.let { v.alpha = it }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) scan(v.getChildAt(i))
    }
    scan(tab)
    tab.invalidate()
}

/** 没有红点时为零；只认 QQ 实际渲染出的纯数字，不能把“99+”谎报成 99。 */
private fun unreadIn(tab: View): Int? {
    var unread = 0
    var truncated = false
    fun scan(v: View) {
        if (v is TextView && v.isShown) {
            val s = v.text?.toString()?.trim().orEmpty()
            if (s == "99+") truncated = true
            else if (s.length in 1..7 && s.all(Char::isDigit)) unread = maxOf(unread, s.toIntOrNull() ?: 0)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) scan(v.getChildAt(i))
    }
    scan(tab)
    return if (truncated && unread == 0) null else unread
}

private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}
private val line = Path()

private fun drawTabGlyph(c: Canvas, v: View, kind: Int, count: Int, selected: Boolean) {
    val dp = v.dp
    val centerX = v.width / 2f
    val centerY = v.height / 2f
    val u = min(24 * dp, min(v.width, v.height) * 0.72f) / 24f
    ink.shader = null
    ink.style = Paint.Style.STROKE
    ink.strokeWidth = 1.8f * dp
    ink.color = monet(night(), 0xFF) ?: if (night()) {
        if (selected) Color.WHITE else 0xFFB2B2B8.toInt()
    } else if (selected) 0xFF1C1C1E.toInt() else 0xFF62626B.toInt()
    val save = c.save()
    c.translate(centerX, centerY)
    c.scale(u, u)
    line.reset()
    when (kind) {
        0 -> {
            ink.style = Paint.Style.FILL
            ink.textAlign = Paint.Align.CENTER
            val digits = count.toString()
            ink.textSize = if (digits.length >= 5) 9f else if (digits.length >= 4) 11f else 16f
            c.drawText(digits, 0f, -(ink.ascent() + ink.descent()) / 2f, ink)
        }
        1 -> {
            c.drawCircle(-4.5f, -4f, 3.3f, ink)
            c.drawCircle(5.4f, -3f, 2.6f, ink)
            line.moveTo(-11f, 7f); line.cubicTo(-11f, 0f, 2f, 0f, 2f, 7f)
            line.moveTo(4f, 7f); line.cubicTo(4f, 2f, 11f, 2f, 11f, 7f)
            c.drawPath(line, ink)
        }
        2 -> {
            c.drawCircle(0f, 0f, 9f, ink)
            line.moveTo(3.5f, -3.5f); line.lineTo(1.5f, 1.5f)
            line.lineTo(-3.5f, 3.5f); line.lineTo(-1.5f, -1.5f); line.close()
            c.drawPath(line, ink)
        }
        3 -> {
            c.drawCircle(0f, -5f, 3.7f, ink)
            line.moveTo(-9f, 8f); line.cubicTo(-9f, -0.5f, 9f, -0.5f, 9f, 8f)
            c.drawPath(line, ink)
        }
    }
    c.restoreToCount(save)
}
