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
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min

/** 有明确可画的背后内容时做折射；QQ 的模糊控件不取样，失效时保留原生透明材质。 */
class Glass(private val host: View, private val radius: Float = Float.MAX_VALUE,
            private val selected: (() -> View?)? = null) : Drawable() {
    private val dp = host.dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val node = RenderNode("qself-lens")
    private val lens by lazy { RuntimeShader(LENS) }
    private val pad = (12 * dp).toInt()
    private var effectSize = 0L
    private var failures = 0
    private var sampled = false
    private var missing = false
    private var busy = false
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
        rect.inset(press * 8 * dp, press * 5 * dp) // 按压形变：整颗胶囊缩进去，松手弹回
        val r = min(radius, rect.height() / 2f)
        val accent = monet(night, 0xFF)
        val refracted = canvas.isHardwareAccelerated && failures < 3 && !busy && runCatching { backdrop(canvas) }
            .onFailure { if (++failures <= 2) log("玻璃取样失败 ${host.javaClass.simpleName}，使用透明材质", it) }
            .getOrDefault(false)
        paint.style = Paint.Style.FILL
        paint.shader = null
        // 静止时没有高光。Monet 关掉即纯灰阶；有真实底图时才降低遮罩透明度。按压整体略压暗。
        val base = wash(if (night) 0xAE333333.toInt() else 0xC0F5F5F5.toInt(), accent)
        val alpha = (Color.alpha(base) * (if (refracted) 0.38f else 1f) * (1f - press * 0.12f)).toInt()
        paint.color = (base and 0xFFFFFF) or (alpha shl 24)
        canvas.drawRoundRect(rect, r, r, paint)
        pill(canvas, night)
    }

    /** QQ 的页签消耗触摸，底栏自身不进入 pressed；从其专用触摸入口驱动反馈。 */
    fun press(down: Boolean) = setPress(down)

    private fun backdrop(canvas: Canvas): Boolean {
        if (!host.isAttachedToWindow) return false
        val b = bounds
        val w = b.width() + 2 * pad
        val h = b.height() + 2 * pad
        busy = true
        try {
            if (effectSize != (w.toLong() shl 32 or h.toLong())) {
                lens.setFloatUniform("size", w.toFloat(), h.toFloat())
                lens.setFloatUniform("pad", pad.toFloat())
                lens.setFloatUniform("dp", dp)
                lens.setFloatUniform("bend", 12 * dp)
                node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"))
                effectSize = w.toLong() shl 32 or h.toLong()
            }
            lens.setFloatUniform("rad", min(radius, b.height() / 2f))
            lens.setFloatUniform("press", press)
            node.setPosition(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
            val rc = node.beginRecording(w, h)
            val count = try {
                rc.translate((pad - b.left).toFloat(), (pad - b.top).toFloat())
                behind(rc)
            } finally { node.endRecording() }
            if (count == 0) {
                if (!missing) { log("玻璃无可见底图: ${host.javaClass.simpleName}，使用透明材质"); missing = true }
                return false
            }
            if (!sampled) { log("玻璃折射取样: ${host.javaClass.simpleName}, 图层 $count"); sampled = true }
            canvas.drawRenderNode(node)
            return true
        } finally { busy = false }
    }

    /** 只录背景和画在宿主之前的可见内容；QQ 的模糊层和另一块玻璃都不是原始底图。 */
    private fun behind(rc: Canvas): Int {
        host.getLocationInWindow(here)
        val levels = ArrayList<Pair<ViewGroup, View>>(6)
        var child: View = host
        while (true) {
            val p = child.parent as? ViewGroup ?: break
            levels += p to child
            child = p
        }
        var count = 0
        for ((p, current) in levels.asReversed()) {
            p.background?.let { bg ->
                if (p.tag != "qself-sheet" && bg !is Glass && !bg.javaClass.name.contains("blur", true)) {
                    paint(rc, p) { bg.draw(it) }
                    count++
                }
            }
            for (i in 0 until p.indexOfChild(current)) {
                val s = p.getChildAt(i)
                if (s.visibility != View.VISIBLE || s.width == 0 || s.height == 0 || !overlaps(s) || unsafe(s)) continue
                paint(rc, s) { s.draw(it) }
                count++
            }
        }
        return count
    }

    // 模糊或另一块玻璃藏在容器里面时也不把整个容器当作原图重放。
    private fun unsafe(v: View): Boolean {
        if (v.background is Glass || v.javaClass.name.contains("blur", true) ||
            v.background?.javaClass?.name?.contains("blur", true) == true) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            val child = v.getChildAt(i)
            if (child.visibility == View.VISIBLE && overlaps(child) && unsafe(child)) return true
        }
        return false
    }

    private inline fun paint(rc: Canvas, v: View, body: (Canvas) -> Unit) {
        v.getLocationInWindow(there)
        val save = rc.save()
        rc.translate((there[0] - here[0]).toFloat(), (there[1] - here[1]).toFloat())
        rc.clipRect(0, 0, v.width, v.height)
        body(rc)
        rc.restoreToCount(save)
    }

    private fun overlaps(v: View): Boolean {
        v.getLocationInWindow(there)
        return there[1] < here[1] + host.height + pad && there[1] + v.height > here[1] - pad &&
            there[0] < here[0] + host.width + pad && there[0] + v.width > here[0] - pad
    }

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

    private companion object {
        const val LENS = """
uniform shader content;
uniform float2 size;
uniform float pad;
uniform float dp;
uniform float rad;
uniform float bend;
uniform float press;
half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 h = c - float2(pad, pad) - press * dp * float2(8.0, 5.0);
    float r = min(rad, min(h.x, h.y));
    if (r <= 0.0) return half4(0.0);
    float2 spine = clamp(p, c - h + r, c + h - r);
    float2 v = p - spine;
    float d = length(v);
    if (d > r) return half4(0.0);
    float2 n = v / max(d, 0.001);
    float w = d / r;
    half4 col = content.eval(p - n * bend * w * w + n * press * bend * 0.55 * (1.0 - w));
    col.rgb *= half(1.0 - press * 0.12 * smoothstep(0.35, 1.0, w));
    return col;
}
"""
    }
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
