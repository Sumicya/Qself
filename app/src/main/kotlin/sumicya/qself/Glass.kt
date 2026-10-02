// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min

/** 液态玻璃，完全照 KernelSU 管理器的配方（Apache 2.0，源自 Kyant0/AndroidLiquidGlass）：
 *  背板取样 → 提饱和 1.5 → 模糊 4dp → 边缘 24dp 透镜折射；表面是 40% 的表面色 + 1dp 高光细线。
 *  按压是「充气」不是「凹陷」：胶囊、页签、选中胶囊都放大，松手弹回。
 *  ponytail: KSU 的高光是双光源 BloomStroke、选中胶囊带色散透镜，这里用 1dp 白细线和纯放大近似，
 *  要完全体得把 miuix-blur 的 Highlight 系统搬过来，不值。 */
class Glass(private val host: View, private val radius: Float = Float.MAX_VALUE,
            private val selected: (() -> View?)? = null) : Drawable() {
    private val dp = host.dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val node = RenderNode("qself-lens")
    private val lens by lazy { RuntimeShader(LENS) }
    private val pad = (40 * dp).toInt() // KSU 给模糊留的取样外扩
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
        // 底栏走 KSU 的充气（放大在外层视图做）；输入栏/圆钮没有外层缩放，保留缩进形变
        if (selected == null) rect.inset(press * 6 * dp, press * 4 * dp)
        val r = min(radius, rect.height() / 2f)
        val refracted = canvas.isHardwareAccelerated && failures < 3 && !busy && runCatching { backdrop(canvas, r) }
            .onFailure { if (++failures <= 2) log("玻璃取样失败 ${host.javaClass.simpleName}，使用透明材质", it) }
            .getOrDefault(false)
        paint.style = Paint.Style.FILL
        paint.shader = null
        // KSU：surfaceContainer 40% 罩在折射层上；取不到底图就用近实色，别透字
        val surface = wash(if (night) 0xFF2E2E2E.toInt() else 0xFFF2F2F2.toInt(), monet(night, 0xFF))
        paint.color = if (refracted) (surface and 0xFFFFFF) or 0x66000000 else (surface and 0xFFFFFF) or 0xE6000000.toInt()
        canvas.drawRoundRect(rect, r, r, paint)
        // 1dp 高光细线（KSU BloomStroke 的近似）
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp
        paint.color = if (night) 0x28FFFFFF else 0x33FFFFFF
        canvas.drawRoundRect(rect.left + dp / 2, rect.top + dp / 2, rect.right - dp / 2, rect.bottom - dp / 2, r, r, paint)
        pill(canvas, night)
    }

    /** 页签消耗触摸，底栏自身不进入 pressed；从专用触摸入口驱动。按压值喂给选中亮胶囊的充气。 */
    fun press(down: Boolean) = setPress(down)

    private fun backdrop(canvas: Canvas, r: Float): Boolean {
        if (!host.isAttachedToWindow) return false
        val b = bounds
        val w = b.width() + 2 * pad
        val h = b.height() + 2 * pad
        busy = true
        try {
            if (effectSize != (w.toLong() shl 32 or h.toLong())) {
                // KSU 顺序：vibrancy（饱和 1.5）→ blur（4dp）→ lens（24dp 边缘折射）
                val vibrancy = RenderEffect.createColorFilterEffect(
                    ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) }))
                val blur = RenderEffect.createBlurEffect(4 * dp, 4 * dp, Shader.TileMode.CLAMP)
                lens.setFloatUniform("size", w.toFloat(), h.toFloat())
                lens.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())
                lens.setFloatUniform("refractionHeight", 24 * dp)
                lens.setFloatUniform("refractionAmount", -24 * dp) // KSU 传负值：向内折
                node.setRenderEffect(RenderEffect.createChainEffect(
                    RenderEffect.createChainEffect(vibrancy, blur),
                    RenderEffect.createRuntimeShaderEffect(lens, "content")))
                effectSize = w.toLong() shl 32 or h.toLong()
            }
            lens.setFloatUniform("rad", min(radius, b.height() / 2f))
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
        // 按下去快、弹回来慢：轻点一下也能看见，不会只剩一帧
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
        // KSU：选中的亮胶囊按压时充气到 1.39 倍
        val d = (min(tab.width.toFloat(), bounds.height().toFloat()) - 2 * inset) * (1f + press * 0.39f)
        paint.style = Paint.Style.FILL
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
        // KernelSU 管理器 liquid/Lens.kt 的圆角矩形折射（单圆角版）：
        // 形状内 24dp 边缘带按圆弧剖面把背后的画面往内折，中心不折。
        const val LENS = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float rad;
uniform float refractionHeight;
uniform float refractionAmount;

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    }
    float gradX = step(cornerCoord.y, cornerCoord.x);
    return sign(coord) * float2(gradX, 1.0 - gradX);
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float sd = sdRoundedRect(centeredCoord, halfSize, rad);
    if (sd > 0.0) return half4(0.0);
    if (-sd >= refractionHeight) return content.eval(coord);
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(rad * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius));
    return content.eval(coord + d * grad);
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
