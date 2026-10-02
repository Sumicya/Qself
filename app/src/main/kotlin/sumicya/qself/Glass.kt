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

/**
 * 液态玻璃（iOS 26 的「透明」款）：把宿主身后的内容（各级祖先的背景 + 排在宿主前面的兄弟）录进一个
 * RenderNode，不模糊，过一遍 AGSL：整块把底下的画面往外顶（凸透镜，中心轻边缘满）、整体提一点饱和、
 * 罩一层淡色。没有高光也没有描边；按下去透镜凹一点、整面浮一层柔光。全在 GPU 上，
 * 每帧只多录一遍身后的 display list。
 *
 * [radius] 圆角上限（默认全圆 = 胶囊 / 圆钮），[selected] 给底栏用：返回当前选中的页签，
 * 玻璃里就多一块会弹着滑过去的亮胶囊。
 */
class Glass(private val host: View, private val radius: Float = Float.MAX_VALUE, private val selected: (() -> View?)? = null) : Drawable() {
    private val dp = host.dp
    private val node = RenderNode("qself-glass")
    private val pad = (12 * dp).toInt() // 边缘折射往里采样，留一点余量就够
    private val lens = RuntimeShader(LENS)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val here = IntArray(2)
    private val there = IntArray(2)
    private var key = 0L
    private var keyAccent = 0
    private var busy = false
    private var broken = false
    // 按压：透镜往内凹一点 + 一层柔光，140ms 起落
    private var press = 0f
    private var pressed = false
    private val pressAnim = ValueAnimator.ofFloat(0f, 0f).apply {
        duration = 140
        addUpdateListener { press = it.animatedValue as Float; invalidateSelf() }
    }

    // 选中高亮：圆心从 fromX 弹到 toX
    private var target: View? = null
    private var fromX = 0f
    private var toX = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 480
        interpolator = null
        addUpdateListener { invalidateSelf() }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val night = night()
        lens.setFloatUniform("press", press)
        val drawn = canvas.isHardwareAccelerated && !busy && !broken && runCatching { backdrop(canvas, night) }
            .onFailure { broken = true; log("玻璃录制失败，退化为纯色", it) }.getOrDefault(false)
        val r = min(radius, b.height() / 2f)
        if (!drawn) { // 软件画布 / 录制失败时的霜色
            rect.set(b)
            paint.shader = null
            paint.color = monet(night, if (night) 0xA6 else 0xB8)
                ?: if (night) 0xA61C1C1E.toInt() else 0xB8F5F5F8.toInt()
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
            // Monet 关了或者系统没给动态色时，accent 就等于着色器里那套中性色，混进去等于没混。
            val accent = monet(night, 0xFF) ?: if (night) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
            val k = (w.toLong() shl 40) or (h.toLong() shl 8) or (if (night) 1L else 0L)
            if (k != key || accent != keyAccent) { node.setRenderEffect(effect(w, h, night, accent)); key = k; keyAccent = accent }
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

    private fun effect(w: Int, h: Int, night: Boolean, accent: Int): RenderEffect {
        lens.setFloatUniform("size", w.toFloat(), h.toFloat())
        lens.setFloatUniform("pad", pad.toFloat())
        lens.setFloatUniform("rad", radius)
        lens.setFloatUniform("bend", 12 * dp)
        lens.setFloatUniform("night", if (night) 1f else 0f)
        lens.setFloatUniform("accent", Color.red(accent) / 255f, Color.green(accent) / 255f, Color.blue(accent) / 255f)
        return RenderEffect.createRuntimeShaderEffect(lens, "content")
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

    private fun setPress(p: Boolean) {
        if (p == pressed) return
        pressed = p
        pressAnim.cancel()
        pressAnim.setFloatValues(press, if (p) 1f else 0f)
        pressAnim.start()
    }

    override fun onStateChange(state: IntArray): Boolean {
        setPress(state.contains(android.R.attr.state_pressed))
        return false
    }

    /** 选中页签换了就重画；每帧 pre-draw 时由宿主调一次。底栏自己拿不到 pressed
     * （触摸被页签吃掉），在这里看子/孙视图有没有被按着。 */
    fun sync() {
        if (selected?.invoke() !== target) invalidateSelf()
        val g = host as? ViewGroup
        val kid = g?.let { p ->
            p.isPressed || (0 until p.childCount).any { p.getChildAt(it).isPressed } ||
                (p.getChildAt(0) as? ViewGroup)?.let { c -> (0 until c.childCount).any { c.getChildAt(it).isPressed } } == true
        } == true
        setPress(host.isPressed || kid)
    }

    private fun pill(canvas: Canvas, night: Boolean) {
        val t = selected?.invoke() ?: return
        host.getLocationInWindow(here)
        t.getLocationInWindow(there)
        val x = there[0] - here[0] + t.width / 2f
        if (t !== target) {
            // 从现在画着的位置弹过去；第一次直接落位
            val first = target == null
            val f = if (anim.isRunning) spring(anim.animatedFraction) else 1f
            fromX = if (first) x else fromX + (toX - fromX) * f
            target = t
            anim.cancel()
            if (!first) anim.start()
        }
        toX = x
        val cx = fromX + (toX - fromX) * (if (anim.isRunning) spring(anim.animatedFraction) else 1f)
        val inset = 4 * dp
        // 圆贴着页签钮：直径取页签宽和栏高里小的那个，减一圈留白；窄页签不会溢到邻座
        val d = min(t.width.toFloat(), bounds.height().toFloat()) - 2 * inset
        // 上亮下暗一点，像一块有厚度的玻璃；白高光里混一点 Monet 强调色，跟玻璃罩色一个份量。
        val accent = monet(night, 0xFF)
        val top = wash(if (night) 0x40FFFFFF else 0xC8FFFFFF.toInt(), accent)
        val bottom = wash(if (night) 0x24FFFFFF else 0x8CFFFFFF.toInt(), accent)
        paint.color = -1
        paint.shader = LinearGradient(0f, bounds.top + inset, 0f, bounds.bottom - inset, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, bounds.centerY().toFloat(), d / 2f, paint)
        paint.shader = null
    }

    /** 欠阻尼弹簧：冲过头一点再回来。 */
    private fun spring(t: Float): Float = (1.0 - exp(-6.0 * t) * cos(2 * PI * 0.9 * t)).toFloat()

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        /** 输入是身后的原图（不模糊）。坐标是节点像素；胶囊 = 节点四周去掉 pad。 */
        const val LENS = """
uniform shader content;
uniform float2 size;
uniform float pad;
uniform float rad;
uniform float bend;
uniform float press;
uniform float night;
uniform float3 accent;
half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 h = c - float2(pad, pad);
    float r = min(rad, min(h.x, h.y));
    float2 spine = float2(clamp(p.x, c.x - (h.x - r), c.x + (h.x - r)), clamp(p.y, c.y - (h.y - r), c.y + (h.y - r)));
    float2 v = p - spine;
    float d = length(v);
    if (d > r) { return half4(0.0); }
    float2 n = v / max(d, 0.001);
    float w = d / max(r, 0.001); // 全扭曲：折射量从中心 0 长到边缘满，整块都是透镜
    // 按压变形：中心取样往外挪（凹进去），边缘不动
    half4 col = content.eval(p - n * bend * w * w + n * (press * bend * 0.9) * (1.0 - w));
    half l = dot(col.rgb, half3(0.299, 0.587, 0.114));
    col.rgb = mix(half3(l), col.rgb, half(1.25));
    half3 tint = mix(mix(half3(1.0), half3(0.07), half(night)), half3(accent), half(0.22));
    col.rgb = mix(col.rgb, tint, mix(half(0.28), half(0.34), half(night)));
    col.rgb += half3(press * mix(0.14, 0.08, night) * (1.0 - 0.55 * w)); // 按压高光：整面柔光，中心最亮
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

/** 把 Monet 强调色按 0.22 混进 [c]，保留 [c] 自己的透明度；accent 为 null（Monet 关着）就原样返回。 */
private fun wash(c: Int, accent: Int?): Int {
    if (accent == null) return c
    fun mix(a: Int, b: Int) = (a + ((b - a) * 0.22f).toInt()).coerceIn(0, 255)
    return (c and 0xFF000000.toInt()) or (mix(Color.red(c), Color.red(accent)) shl 16) or
        (mix(Color.green(c), Color.green(accent)) shl 8) or mix(Color.blue(c), Color.blue(accent))
}

/** NagramX 那套 Monet：系统动态色跟壁纸走。开关关了返回 null，调用方用写死的中性色。 */
fun monet(night: Boolean, alpha: Int): Int? =
    if (!on("Monet取色")) null else runCatching {
        val id = if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
        Resources.getSystem().getColor(id, null) and 0x00FFFFFF or (alpha shl 24)
    }.getOrNull()

/** QQ 自己的夜间模式；拿不到就看系统。 */
fun night(): Boolean = runCatching { isNight?.invoke(null) as? Boolean }.getOrNull()
    ?: (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
