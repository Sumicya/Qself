// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min

/** 原生半透明材质：不重画 QQ 的视图树，点击/滚动/键盘变化时也不会录到旧帧或 QQ 的模糊层。 */
class Glass(private val host: View, private val radius: Float = Float.MAX_VALUE, private val selected: (() -> View?)? = null) : Drawable() {
    private val dp = host.dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val here = IntArray(2)
    private val there = IntArray(2)
    private var press = 0f
    private var pressed = false
    private val pressAnim = ValueAnimator.ofFloat(0f, 0f).apply {
        duration = 140
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
        rect.inset(press * 2 * dp, press * dp) // 只缩玻璃，不缩 QQ 的触摸目标/文字
        val r = min(radius, rect.height() / 2f)
        val accent = monet(night, 0xFF)
        paint.shader = null
        paint.color = wash(if (night) 0x7818181E else 0x68FFFFFF, accent)
        canvas.drawRoundRect(rect, r, r, paint)
        // 没有常驻描边；按下才有从上缘滑入的柔光。
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            (0x24 + press * 0x62).toInt() shl 24 or 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        pill(canvas, night)
    }

    private fun setPress(value: Boolean) {
        if (value == pressed) return
        pressed = value
        pressAnim.cancel()
        pressAnim.setFloatValues(press, if (value) 1f else 0f)
        pressAnim.start()
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        setPress(state.contains(android.R.attr.state_pressed))
        return true
    }

    /** 底栏的触摸由页签接收；其他玻璃用系统 pressed 状态，不需要轮询取样。 */
    fun sync() {
        if (selected?.invoke() !== target) invalidateSelf()
        val strip = (host as? ViewGroup)?.getChildAt(0) as? ViewGroup
        setPress(host.isPressed || (strip != null && (0 until strip.childCount).any { strip.getChildAt(it).isPressed }))
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
        val accent = monet(night, 0xFF)
        val top = wash(if (night) 0x40FFFFFF else 0xC8FFFFFF.toInt(), accent)
        val bottom = wash(if (night) 0x24FFFFFF else 0x8CFFFFFF.toInt(), accent)
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, bounds.top + inset, 0f, bounds.bottom - inset, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, bounds.centerY().toFloat(), d / 2f, paint)
        paint.shader = null
    }

    private fun spring(t: Float): Float = (1.0 - exp(-6.0 * t) * cos(2 * PI * 0.9 * t)).toFloat()

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
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
