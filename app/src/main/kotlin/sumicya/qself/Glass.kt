// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
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
import android.view.ViewOutlineProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min

/**
 * 液态玻璃（iOS 26 的「透明」款）：把宿主身后的内容（各级祖先的背景 + 排在宿主前面的兄弟）录进一个
 * RenderNode，不模糊，过一遍 AGSL：边缘一圈把底下的画面往外顶（凸透镜）、贴边一道 2dp 的高光
 * （顶边最亮、底边次之、两头暗）、整体提一点饱和、罩一层淡色。没有描边。全在 GPU 上，
 * 每帧只多录一遍身后的 display list。
 *
 * [radius] 圆角上限（默认全圆 = 胶囊 / 圆钮），[selected] 给底栏用：返回当前选中的页签，
 * 玻璃里就多一块会弹着滑过去的亮胶囊。
 */
class Glass(private val host: View, private val radius: Float = Float.MAX_VALUE, private val selected: (() -> View?)? = null) : Drawable() {
    private val dp = host.dp
    private val node = RenderNode("qself-glass")
    private val pad = (12 * dp).toInt() // 边缘折射往里采样，留一点余量就够
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
        val r = min(radius, b.height() / 2f)
        if (!drawn || lens == null) { // 没有着色器时的霜色
            rect.set(b)
            paint.shader = null
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
        val s = lens ?: return RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.25f) }))
        s.setFloatUniform("size", w.toFloat(), h.toFloat())
        s.setFloatUniform("pad", pad.toFloat())
        s.setFloatUniform("rad", radius)
        s.setFloatUniform("rim", 18 * dp)
        s.setFloatUniform("bend", 12 * dp)
        s.setFloatUniform("px", dp)
        s.setFloatUniform("night", if (night) 1f else 0f)
        return RenderEffect.createRuntimeShaderEffect(s, "content")
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
        // 上亮下暗一点，像一块有厚度的玻璃
        val top = if (night) 0x40FFFFFF else 0xC8FFFFFF.toInt()
        val bottom = if (night) 0x24FFFFFF else 0x8CFFFFFF.toInt()
        paint.color = -1
        paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
        val r = rect.height() / 2f
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
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
uniform float rad;
uniform float rim;
uniform float bend;
uniform float px;
uniform float night;
half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 h = c - float2(pad, pad);
    float r = min(rad, min(h.x, h.y));
    float2 spine = float2(clamp(p.x, c.x - (h.x - r), c.x + (h.x - r)), clamp(p.y, c.y - (h.y - r), c.y + (h.y - r)));
    float2 v = p - spine;
    float d = length(v);
    if (d > r) { return half4(0.0); }
    float2 n = v / max(d, 0.001);
    float t = smoothstep(r - rim, r, d);
    half4 col = content.eval(p - n * bend * t * t);
    half l = dot(col.rgb, half3(0.299, 0.587, 0.114));
    col.rgb = mix(half3(l), col.rgb, half(1.25));
    half3 tint = mix(half3(1.0), half3(0.07), half(night));
    col.rgb = mix(col.rgb, tint, mix(half(0.28), half(0.34), half(night)));
    float k = dot(n, normalize(float2(-0.55, -0.83)));
    float lit = max(k, 0.0);
    float back = max(-k, 0.0);
    float band = smoothstep(r - 3.0 * px, r - 0.8 * px, d);
    float glow = t * t * t;
    float spec = band * (0.10 + 0.70 * lit * lit + 0.35 * back * back) + glow * 0.12 * lit;
    col.rgb += half3(spec * mix(0.75, 0.55, night));
    col.rgb -= half3(glow * 0.08 * back);
    col.a = 1.0;
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

/** QQ 自己的夜间模式开关；拿不到就看系统。 */
fun night(): Boolean = runCatching { isNight?.invoke(null) as? Boolean }.getOrNull()
    ?: (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
