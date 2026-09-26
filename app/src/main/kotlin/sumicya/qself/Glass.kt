// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup

/**
 * 玻璃：把宿主身后的内容（各级祖先的背景 + 排在宿主前面的兄弟，也就是画在它底下的东西）
 * 录进一个 RenderNode，交给系统的 RenderEffect 做高斯模糊 + 提饱和，再罩一层霜色、描一圈亮边。
 * 全在 GPU 上，每帧只多录一遍身后的 display list，没有位图拷贝。
 */
class Glass(private val host: View) : Drawable() {
    private val dp = host.dp
    private val node = RenderNode("qself-glass").apply {
        val blur = RenderEffect.createBlurEffect(24 * dp, 24 * dp, Shader.TileMode.CLAMP)
        val vivid = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })
        setRenderEffect(RenderEffect.createColorFilterEffect(vivid, blur))
    }
    private val pad = (48 * dp).toInt() // 多录一圈，模糊到边缘不拖影
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp }
    private val rect = RectF()
    private val here = IntArray(2)
    private val there = IntArray(2)
    private var busy = false
    private var broken = false

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (canvas.isHardwareAccelerated && !busy && !broken) {
            busy = true
            try {
                node.setPosition(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                val rc = node.beginRecording(b.width() + 2 * pad, b.height() + 2 * pad)
                try {
                    rc.translate((pad - b.left).toFloat(), (pad - b.top).toFloat())
                    behind(rc)
                } finally {
                    node.endRecording()
                }
                canvas.drawRenderNode(node)
            } catch (t: Throwable) {
                // 这里不在钩子里，抛出去就是 QQ 崩溃：退化成纯霜色，记一次日志。
                broken = true
                log("玻璃录制失败，退化为纯色", t)
            } finally {
                busy = false
            }
        }
        val night = night()
        val r = b.height() / 2f
        rect.set(b)
        tint.color = if (night) 0xA61C1C1E.toInt() else 0xB8F5F5F8.toInt()
        canvas.drawRoundRect(rect, r, r, tint)
        rect.inset(dp / 2, dp / 2)
        edge.color = if (night) 0x33FFFFFF else 0x99FFFFFF.toInt()
        canvas.drawRoundRect(rect, r, r, edge)
    }

    /** 按画家顺序（先远后近）把宿主底下的东西画到以宿主左上角为原点的画布上。 */
    private fun behind(rc: Canvas) {
        host.getLocationInWindow(here)
        val levels = ArrayList<Pair<ViewGroup, View>>(8)
        var child: View = host
        while (true) {
            val p = child.parent as? ViewGroup ?: break
            levels += p to child
            child = p
        }
        for ((p, c) in levels.asReversed()) {
            p.background?.let { paint(rc, p) { bg -> it.draw(bg) } }
            for (i in 0 until p.indexOfChild(c)) {
                val s = p.getChildAt(i)
                if (s.visibility != View.VISIBLE || s.javaClass.simpleName.contains("Blur") || !overlaps(s)) continue
                paint(rc, s) { s.draw(it) }
            }
        }
    }

    private inline fun paint(rc: Canvas, v: View, body: (Canvas) -> Unit) {
        v.getLocationInWindow(there)
        val save = rc.save()
        rc.translate((there[0] - here[0]).toFloat(), (there[1] - here[1]).toFloat())
        rc.clipRect(0, 0, v.width, v.height)
        rc.translate(-v.scrollX.toFloat(), -v.scrollY.toFloat()) // 直接调 draw() 不经过父级，滚动量得自己减
        body(rc)
        rc.restoreToCount(save)
    }

    private fun overlaps(v: View): Boolean {
        v.getLocationInWindow(there)
        return there[1] < here[1] + host.height + pad && there[1] + v.height > here[1] - pad &&
            there[0] < here[0] + host.width + pad && there[0] + v.width > here[0] - pad
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 选中页签底下的半透明胶囊，跟着 TabLayout 的指示器动画滑。 */
class Pill(private val dp: Float) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        rect.inset(4 * dp, 4 * dp)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val r = rect.height() / 2f
        fill.color = if (night()) 0x2EFFFFFF else 0x14000000
        canvas.drawRoundRect(rect, r, r, fill)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private val isNight by lazy { runCatching { cls("com.tencent.mobileqq.utils.QQTheme").getMethod("isNowThemeIsNight") }.getOrNull() }

/** QQ 自己的夜间模式开关；拿不到就看系统。 */
fun night(): Boolean = runCatching { isNight?.invoke(null) as? Boolean }.getOrNull()
    ?: (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
