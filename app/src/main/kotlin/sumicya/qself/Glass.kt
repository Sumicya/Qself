// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.animation.ValueAnimator
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
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp

/**
 * 液态玻璃：把宿主身后的内容（各级祖先的背景 + 排在宿主前面的兄弟）录进一个 RenderNode，
 * 先高斯模糊，再过一遍 AGSL：边缘一圈把底下的画面往里折（透镜）、朝光的一侧泛白、背光的一侧压暗，
 * 中间只提饱和加一层淡淡的霜色。没有描边。全在 GPU 上，每帧只多录一遍身后的 display list。
 *
 * [selected] 给底栏用：返回当前选中的页签，玻璃里就多一块会弹着滑过去的亮胶囊。
 */
class Glass(private val host: View, private val selected: (() -> View?)? = null) : Drawable() {
    private val dp = host.dp
    private val node = RenderNode("qself-glass")
    private val pad = (40 * dp).toInt() // 多录一圈，模糊到边缘不拖影
    private val lens = if (Build.VERSION.SDK_INT >= 33) runCatching { RuntimeShader(LENS) }.getOrNull() else null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val here = IntArray(2)
    private val there = IntArray(2)
    private var key = 0L
    private var busy = false
    private var broken = false

    // 滑块：从 (fromX, fromW) 弹到 (toX, toW)
    private var target: View? = null
    private var fromX = 0f
    private var fromW = 0f
    private var toX = 0f
    private var toW = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 480
        interpolator = null
        addUpdateListener { invalidateSelf() }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val night = night()
        val drawn = canvas.isHardwareAccelerated && !busy && !broken && runCatching { backdrop(canvas, night) }
            .onFailure { broken = true; log("玻璃录制失败，退化为纯色", it) }.getOrDefault(false)
        val r = b.height() / 2f
        if (!drawn || lens == null) { // 没有着色器时的霜色
            rect.set(b)
            paint.color = if (night) 0xA61C1C1E.toInt() else 0xB8F5F5F8.toInt()
            canvas.drawRoundRect(rect, r, r, paint)
        }
        pill(canvas, night)
    }

    private fun backdrop(canvas: Canvas, night: Boolean): Boolean {
        val b = bounds
        busy = true
        try {
            val w = b.width() + 2 * pad
            val h = b.height() + 2 * pad
            val k = (w.toLong() shl 40) or (h.toLong() shl 8) or (if (night) 1L else 0L)
            if (k != key) { node.setRenderEffect(effect(w, h, night)); key = k }
            node.setPosition(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
            val rc = node.beginRecording(w, h)
            try {
                rc.translate((pad - b.left).toFloat(), (pad - b.top).toFloat())
                behind(rc)
            } finally {
                node.endRecording()
            }
            canvas.drawRenderNode(node)
            return true
        } finally {
            busy = false
        }
    }

    private fun effect(w: Int, h: Int, night: Boolean): RenderEffect {
        val blur = RenderEffect.createBlurEffect(14 * dp, 14 * dp, Shader.TileMode.CLAMP)
        val s = lens ?: return RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.4f) }), blur)
        s.setFloatUniform("size", w.toFloat(), h.toFloat())
        s.setFloatUniform("pad", pad.toFloat())
        s.setFloatUniform("rim", 14 * dp)
        s.setFloatUniform("bend", 9 * dp)
        s.setFloatUniform("night", if (night) 1f else 0f)
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(s, "content"), blur)
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

    /** 选中页签换了就重画；每帧 pre-draw 时由宿主调一次。 */
    fun sync() {
        if (selected?.invoke() !== target) invalidateSelf()
    }

    private fun pill(canvas: Canvas, night: Boolean) {
        val t = selected?.invoke() ?: return
        host.getLocationInWindow(here)
        t.getLocationInWindow(there)
        val x = (there[0] - here[0]).toFloat()
        val w = t.width.toFloat()
        if (t !== target) {
            // 从现在画着的位置弹过去；第一次直接落位
            val first = target == null
            val f = if (anim.isRunning) spring(anim.animatedFraction) else 1f
            fromX = if (first) x else fromX + (toX - fromX) * f
            fromW = if (first) w else fromW + (toW - fromW) * f
            target = t
            anim.cancel()
            if (!first) anim.start()
        }
        toX = x
        toW = w
        val f = if (anim.isRunning) anim.animatedFraction else 1f
        val s = if (anim.isRunning) spring(f) else 1f
        val bulge = 0.3f * abs(toX - fromX) * 4f * f * (1f - f) // 滑动途中拉长，像液体
        val cx = (fromX + fromW / 2f) + ((toX + toW / 2f) - (fromX + fromW / 2f)) * s
        val cw = fromW + (toW - fromW) * s + bulge
        val inset = 4 * dp
        rect.set(cx - cw / 2f + 2 * dp, bounds.top + inset, cx + cw / 2f - 2 * dp, bounds.bottom - inset)
        paint.color = if (night) 0x33FFFFFF else 0x80FFFFFF.toInt()
        val r = rect.height() / 2f
        canvas.drawRoundRect(rect, r, r, paint)
    }

    /** 欠阻尼弹簧：冲过头一点再回来。 */
    private fun spring(t: Float): Float = (1.0 - exp(-6.0 * t) * cos(2 * PI * 0.9 * t)).toFloat()

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /** 输入是模糊后的底图。坐标是节点像素；胶囊 = 节点四周去掉 pad。 */
        const val LENS = """
uniform shader content;
uniform float2 size;
uniform float pad;
uniform float rim;
uniform float bend;
uniform float night;
half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 h = c - float2(pad, pad);
    float r = min(h.x, h.y);
    float2 spine = float2(clamp(p.x, c.x - (h.x - r), c.x + (h.x - r)), clamp(p.y, c.y - (h.y - r), c.y + (h.y - r)));
    float2 v = p - spine;
    float d = length(v);
    if (d > r) { return half4(0.0); }
    float2 n = v / max(d, 0.001);
    float t = smoothstep(r - rim, r, d);
    half4 col = content.eval(p - n * bend * t * t);
    half l = dot(col.rgb, half3(0.299, 0.587, 0.114));
    col.rgb = mix(half3(l), col.rgb, half(1.35));
    half3 frost = mix(half3(1.0), half3(0.06), half(night));
    col.rgb = mix(col.rgb, frost, mix(half(0.30), half(0.38), half(night)));
    float k = dot(n, normalize(float2(-0.6, -0.8)));
    float e = t * t * t;
    col.rgb += half3(e * (0.55 + 0.45 * k) * 0.30) - half3(e * (0.55 - 0.45 * k) * 0.10);
    col.a = 1.0;
    return col;
}
"""
    }
}

private val isNight by lazy { runCatching { cls("com.tencent.mobileqq.utils.QQTheme").getMethod("isNowThemeIsNight") }.getOrNull() }

/** QQ 自己的夜间模式开关；拿不到就看系统。 */
fun night(): Boolean = runCatching { isNight?.invoke(null) as? Boolean }.getOrNull()
    ?: (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
