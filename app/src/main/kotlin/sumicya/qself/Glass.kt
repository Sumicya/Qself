// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min

/** KernelSU 管理器式底栏胶囊：实色材质，不模糊、不取样、无描边。
 *  按压 = 整颗缩进并压暗、松手弹回；选中项 = 弹着滑过去的亮圆。颜色可跟 Monet 走。 */
class Glass(private val host: View, private val radius: Float = Float.MAX_VALUE,
            private val selected: (() -> View?)? = null) : Drawable() {
    private val dp = host.dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val here = IntArray(2)
    private val there = IntArray(2)
    private var press = 0f
    private var pressed = false
    private val pressAnim = ValueAnimator.ofFloat(0f, 0f).apply {
        addUpdateListener { press = it.animatedValue as Float; invalidateSelf() }
    }
    private var target: View? = null
    private var fromX = 0f
    private var toX = 0f
    private val slide = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 480
        interpolator = null
        addUpdateListener { invalidateSelf() }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val night = night()
        rect.set(b)
        rect.inset(press * 8 * dp, press * 5 * dp) // 按压形变：整颗胶囊缩进去，松手弹回
        val r = min(radius, rect.height() / 2f)
        paint.style = Paint.Style.FILL
        paint.shader = null
        val base = wash(if (night) 0xFF303030.toInt() else 0xFFF2F2F2.toInt(), monet(night, 0xFF))
        paint.color = dim(base, press)
        canvas.drawRoundRect(rect, r, r, paint)
        pill(canvas, night)
    }

    /** QQ 的页签消耗触摸，底栏自身不进入 pressed；从其专用触摸入口驱动反馈。 */
    fun press(down: Boolean) = setPress(down)

    private fun setPress(value: Boolean) {
        if (value == pressed) return
        pressed = value
        pressAnim.cancel()
        // 按下去快、弹回来慢：轻点一下也能看见凹下去再弹回，不会只剩一帧
        pressAnim.duration = if (value) 50 else 150
        pressAnim.setFloatValues(press, if (value) 1f else 0f)
        pressAnim.start()
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        if (selected == null) setPress(state.contains(android.R.attr.state_pressed))
        return true
    }

    /** 选中目标可能变化而底栏自身未失效；按压由 QQTabLayout 专用触摸钩子驱动。 */
    fun sync() {
        if (selected?.invoke() !== target) invalidateSelf()
    }

    private fun pill(canvas: Canvas, night: Boolean) {
        val tab = selected?.invoke() ?: return
        host.getLocationInWindow(here)
        tab.getLocationInWindow(there)
        val x = there[0] - here[0] + tab.width / 2f
        if (tab !== target) {
            val first = target == null
            val f = if (slide.isRunning) spring(slide.animatedFraction) else 1f
            fromX = if (first) x else fromX + (toX - fromX) * f
            target = tab
            slide.cancel()
            if (!first) slide.start()
        }
        toX = x
        val cx = fromX + (toX - fromX) * (if (slide.isRunning) spring(slide.animatedFraction) else 1f)
        val inset = 4 * dp
        val d = min(tab.width.toFloat(), bounds.height().toFloat()) - 2 * inset
        paint.color = wash(if (night) 0x4AFFFFFF else 0xAAFFFFFF.toInt(), monet(night, 0xFF))
        paint.shader = null
        canvas.drawCircle(cx, bounds.centerY().toFloat(), d / 2f, paint)
    }

    private fun spring(t: Float): Float = (1.0 - exp(-6.0 * t) * cos(2 * PI * 0.9 * t)).toFloat()

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 按压压暗：往黑里压一点。 */
private fun dim(c: Int, k: Float): Int {
    if (k <= 0f) return c
    val f = 1f - k * 0.1f
    return (c and 0xFF000000.toInt()) or ((Color.red(c) * f).toInt() shl 16) or
        ((Color.green(c) * f).toInt() shl 8) or (Color.blue(c) * f).toInt()
}

/** 圆角上限为 [max] 的胶囊轮廓（不传就是全圆），配合 clipToOutline 用。 */
fun capsule(max: Float = Float.MAX_VALUE) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, min(max, view.height / 2f))
}

private val isNight by lazy { runCatching { cls("com.tencent.mobileqq.utils.QQTheme").getMethod("isNowThemeIsNight") }.getOrNull() }

/** Monet 混进中性色，保留中性色的透明度。 */
private fun wash(c: Int, accent: Int?): Int {
    if (accent == null) return c
    fun mix(a: Int, b: Int) = (a + ((b - a) * 0.22f).toInt()).coerceIn(0, 255)
    return (c and 0xFF000000.toInt()) or (mix(Color.red(c), Color.red(accent)) shl 16) or
        (mix(Color.green(c), Color.green(accent)) shl 8) or mix(Color.blue(c), Color.blue(accent))
}

fun monet(night: Boolean, alpha: Int): Int? =
    if (!on("Monet取色")) null else runCatching {
        val id = if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
        Resources.getSystem().getColor(id, null) and 0x00FFFFFF or (alpha shl 24)
    }.getOrNull()

fun night(): Boolean = runCatching { isNight?.invoke(null) as? Boolean }.getOrNull()
    ?: (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
