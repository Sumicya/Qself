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
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 原生半透明材质：不重画 QQ 的视图树，点击/滚动/键盘变化时也不会录到旧帧或 QQ 的模糊层。 */
class Glass(private val host: View, private val radius: Float = Float.MAX_VALUE, private val selected: (() -> View?)? = null) : Drawable() {
    private val dp = host.dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val contour = Path()
    private val here = IntArray(2)
    private val there = IntArray(2)
    private var touchX = Float.NaN
    private var touchY = Float.NaN
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
        rect.inset(press * 4 * dp, press * 2 * dp) // 背景缩进去；命中区域和文字保持原位
        val r = min(radius, rect.height() / 2f)
        val accent = monet(night, 0xFF)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(
                wash(if (night) 0xA05E6881.toInt() else 0xACFFFFFF.toInt(), accent),
                wash(if (night) 0x76505C78 else 0x66EBF4FF, accent),
                wash(if (night) 0x8A323E56.toInt() else 0x7DD7E4F2, accent),
            ), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, paint)

        contour.reset()
        contour.addRoundRect(rect, r, r, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(contour)
        // 按压才出现聚焦的亮斑；按在镜像按钮上也传过来，松手 260ms 淡出。
        if (press > 0f) {
            val x = if (touchX.isNaN()) rect.centerX() else touchX.coerceIn(rect.left, rect.right)
            val y = if (touchY.isNaN()) rect.top else touchY.coerceIn(rect.top, rect.bottom)
            paint.shader = RadialGradient(x, y, max(52 * dp, min(rect.width() * 0.6f, 170 * dp)),
                intArrayOf(((press * 0x9C).toInt() shl 24) or 0xFFFFFF, 0x00FFFFFF),
                null, Shader.TileMode.CLAMP)
            canvas.drawRect(rect, paint)
        }
        canvas.restoreToCount(save)
        // 只沿上缘的定向反射，不用整圈白描边冒充玻璃。
        val edge = rect.height() * 0.55f
        val top = canvas.save()
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + edge)
        paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.top,
            intArrayOf(0x00FFFFFF, ((0x70 + press * 0x80).toInt() shl 24) or 0xFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.40f, 1f), Shader.TileMode.CLAMP)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (1.2f + press * 1.5f) * dp
        rect.inset(paint.strokeWidth / 2f, paint.strokeWidth / 2f)
        canvas.drawRoundRect(rect, r, r, paint)
        canvas.restoreToCount(top)
        paint.style = Paint.Style.FILL
        paint.shader = null
        pill(canvas, night)
    }

    /** 输入框的子按钮接收点击时，按压亮斑仍跟随实际手指位置。 */
    fun touch(x: Float, y: Float) {
        touchX = x
        touchY = y
        if (pressed) invalidateSelf()
    }

    private fun setPress(value: Boolean) {
        if (value == pressed) return
        pressed = value
        pressAnim.cancel()
        pressAnim.duration = if (value) 120 else 260
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
        val stretch = if (slide.isRunning) sin(PI * slide.animatedFraction).toFloat() * 0.16f else 0f
        val cy = bounds.centerY().toFloat()
        canvas.drawOval(cx - d * (0.5f + stretch), cy - d / 2f,
            cx + d * (0.5f + stretch), cy + d / 2f, paint)
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
