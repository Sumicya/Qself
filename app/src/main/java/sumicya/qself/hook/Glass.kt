// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RecordingCanvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver

/**
 * Qself 的玻璃：把宿主下面压着的东西录进一个 RenderNode，模糊、提饱和，再让 AGSL 透镜在边缘
 * 把它掰弯 —— 圆角矩形的 SDF 沿法线推出去一点，靠角的地方带一丝色散。跟 KernelSU / iOS 26 那个
 * 透镜一个思路：没有着色器库、不走位图，node 是 display list，只有下面动了才重录。
 */
object GlassKit {
    private val applied = Decor<On>()

    val count: Int get() = applied.size

    /** 给 [host] 贴上玻璃（做成它的 background）。重复调用是空操作。 */
    fun apply(host: View, capsule: Boolean, elevationDp: Float = 0f): Boolean {
        if (applied[host] != null) return true
        return runCatching {
            applied.put(On(host, capsule, elevationDp))
            true
        }.getOrElse {
            Core.log(Log.WARN, "glass on ${host.javaClass.simpleName}: $it")
            false
        }
    }

    fun remove(host: View) {
        applied[host]?.let(applied::drop)
    }

    fun clearAll() = applied.dropAll()

    fun dp(v: View, x: Float) = x * v.resources.displayMetrics.density

    fun rectInWindow(v: View, out: Rect): Rect {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        out.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        return out
    }

    fun isBlur(v: View): Boolean =
        v.javaClass.simpleName.let { it.startsWith("QQBlurView") || it.startsWith("QQNativeBlurView") }

    /** QQ 自己的主题，不跟着系统深浅色走。 */
    private val qqNight by lazy {
        runCatching { Class.forName("com.tencent.mobileqq.utils.QQTheme", false, Core.loader).getMethod("isNowThemeIsNight") }.getOrNull()
    }

    fun night(v: View): Boolean = runCatching { qqNight!!.invoke(null) as Boolean }.getOrElse {
        v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private class On(override val anchor: View, capsule: Boolean, elevationDp: Float) : DecorState {
        private val host = anchor
        private val background = host.background
        private val outline = host.outlineProvider
        private val clip = host.clipToOutline
        private val elevation = host.elevation
        private val surface = GlassSurface(host, capsule)
        private val held = ArrayList<View>()

        init {
            // 宿主里 QQ 自己那层模糊会盖在玻璃上，先按住。
            (host as? ViewGroup)?.let { group ->
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (isBlur(child) && child.visibility == View.VISIBLE) {
                        held += child
                        child.visibility = View.INVISIBLE
                    }
                }
            }
            host.background = surface
            if (capsule) {
                host.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                }
                host.clipToOutline = true
            }
            if (elevationDp > 0f) host.elevation = dp(host, elevationDp)
            host.addOnAttachStateChangeListener(surface)
            if (host.isAttachedToWindow) surface.listen()
        }

        override fun undo() {
            surface.unlisten()
            host.removeOnAttachStateChangeListener(surface)
            host.background = background
            host.outlineProvider = outline
            host.clipToOutline = clip
            host.elevation = elevation
            held.forEach { it.visibility = View.VISIBLE }
            held.clear()
        }
    }
}

/** 玻璃本体，一个宿主一个。 */
class GlassSurface(private val host: View, private val capsule: Boolean) :
    Drawable(), View.OnAttachStateChangeListener, ViewTreeObserver.OnScrollChangedListener, ViewTreeObserver.OnGlobalLayoutListener {

    private val node = RenderNode("qself-glass")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val here = IntArray(2)
    private val there = IntArray(2)
    private val hostRect = Rect()
    private val otherRect = Rect()

    private var backdrop: View? = null
    private var offsetX = 0
    private var offsetY = 0
    private var recording = false
    private var effectKey = ""
    private var observer: ViewTreeObserver? = null
    private val shader = runCatching { RuntimeShader(LENS) }
        .onFailure { shaderError = it.toString().take(80) }
        .getOrNull()

    fun listen() {
        unlisten()
        observer = host.viewTreeObserver.also {
            it.addOnScrollChangedListener(this)
            it.addOnGlobalLayoutListener(this)
        }
    }

    fun unlisten() {
        observer?.takeIf { it.isAlive }?.let {
            it.removeOnScrollChangedListener(this)
            it.removeOnGlobalLayoutListener(this)
        }
        observer = null
    }

    override fun onViewAttachedToWindow(v: View) = listen()
    override fun onViewDetachedFromWindow(v: View) = unlisten()

    /** 玻璃下面的东西动了，重画。 */
    override fun onScrollChanged() = host.invalidate()
    override fun onGlobalLayout() {
        backdrop = null
        host.invalidate()
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.isEmpty) return
        val target = canvas as? RecordingCanvas
        if (target == null || shader == null || recording) {
            flat(canvas, area)
            return
        }
        recording = true
        try {
            node.setPosition(0, 0, area.width(), area.height())
            updateEffect(area.width(), area.height())
            if (record(area.width(), area.height())) target.drawRenderNode(node) else flat(canvas, area)
        } catch (t: Throwable) {
            flat(canvas, area)
        } finally {
            recording = false
        }
    }

    private fun flat(canvas: Canvas, area: Rect) {
        rect.set(area)
        fill.color = if (GlassKit.night(host)) 0x8C1C1C1E.toInt() else 0x80F4F4F7.toInt()
        val r = if (capsule) area.height() / 2f else 0f
        canvas.drawRoundRect(rect, r, r, fill)
    }

    private fun record(w: Int, h: Int): Boolean {
        val source = backdrop ?: findBackdrop() ?: return false
        backdrop = source
        source.getLocationInWindow(there)
        host.getLocationInWindow(here)
        offsetX = there[0] - here[0]
        offsetY = there[1] - here[1]
        val canvas = node.beginRecording(w, h)
        try {
            canvas.translate(-offsetX.toFloat(), -offsetY.toFloat())
            // View.draw() 认为调用方已经把 view 自己的滚动量加上了。
            canvas.translate(-source.scrollX.toFloat(), -source.scrollY.toFloat())
            source.draw(canvas)
        } finally {
            node.endRecording()
        }
        return true
    }

    /** 宿主下面压着的东西：比它先画的、跟它重叠的、最大的那个兄弟（或祖先的兄弟）。 */
    private fun findBackdrop(): View? {
        GlassKit.rectInWindow(host, hostRect)
        var child: View = host
        var parent = host.parent as? ViewGroup
        while (parent != null) {
            var best: View? = null
            var bestArea = 0
            for (i in 0 until parent.indexOfChild(child)) {
                val v = parent.getChildAt(i)
                if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) continue
                if (GlassKit.isBlur(v) || isHairline(v)) continue
                val area = v.width * v.height
                if (area > bestArea && Rect.intersects(GlassKit.rectInWindow(v, otherRect), hostRect)) {
                    bestArea = area
                    best = v
                }
            }
            if (best != null) return best
            child = parent
            parent = parent.parent as? ViewGroup
        }
        return null
    }

    private fun isHairline(v: View): Boolean =
        (v.height in 1..2 && v.width >= host.width / 2) || (v.width in 1..2 && v.height >= host.height / 2)

    private fun updateEffect(w: Int, h: Int) {
        val night = GlassKit.night(host)
        val key = "$w:$h:$night:${if (capsule) 1 else 0}"
        if (key == effectKey) return
        effectKey = key
        val density = host.resources.displayMetrics.density
        val blur = 6f * density
        val lens = 22f * density
        var effect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
        effect = RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.6f) }), effect,
        )
        shader?.let { s ->
            val radius = if (capsule) h / 2f else 0f
            // 贴边的横条只有底边在屏幕上，其余部分推出画外，否则上下两条边都会发光。
            val out = lens * 2f
            s.setFloatUniform("shape", if (capsule) 0f else -out, if (capsule) 0f else -out, w + if (capsule) 0f else out, h.toFloat())
            s.setFloatUniform("radii", radius, radius, radius, radius)
            s.setFloatUniform("lens", minOf(lens, h / 2f))
            s.setFloatUniform("bend", (if (capsule) 18f else 10f) * density)
            s.setFloatUniform("dispersion", 0.35f)
            if (night) {
                s.setFloatUniform("tint", 0.11f, 0.11f, 0.12f, 0.40f)
                s.setFloatUniform("rim", 0.55f, 0.55f, 0.6f, 0.10f)
            } else {
                s.setFloatUniform("tint", 0.97f, 0.97f, 0.98f, 0.26f)
                s.setFloatUniform("rim", 1f, 1f, 1f, 0.22f)
            }
            effect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(s, "content"), effect)
        }
        node.setRenderEffect(effect)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** AGSL 透镜没建起来时的原因。 */
        var shaderError: String? = null

        // 圆角矩形 SDF，沿边缘法线往外掰样本，越靠边越狠（circleMap），角上带一点红蓝色散。
        private const val LENS = """
uniform shader content;
uniform float4 shape;
uniform float4 radii;
uniform float lens;
uniform float bend;
uniform float dispersion;
uniform float4 tint;
uniform float4 rim;

float radiusAt(float2 q, float4 r) {
    if (q.x >= 0.0) return q.y <= 0.0 ? r.z : r.w;
    return q.y <= 0.0 ? r.x : r.y;
}

float sdRound(float2 q, float2 b, float r) {
    float2 c = abs(q) - b + float2(r);
    return length(max(c, float2(0.0))) + min(max(c.x, c.y), 0.0) - r;
}

float2 gradRound(float2 q, float2 b, float r) {
    float2 c = abs(q) - b + float2(r);
    if (c.x >= 0.0 || c.y >= 0.0) return sign(q) * normalize(max(c, float2(0.0)));
    float t = step(c.y, c.x);
    return sign(q) * float2(t, 1.0 - t);
}

half4 main(float2 p) {
    float2 c = (shape.xy + shape.zw) * 0.5;
    float2 halfSize = (shape.zw - shape.xy) * 0.5;
    float2 q = p - c;
    float radius = radiusAt(q, radii);
    float sd = sdRound(q, halfSize, radius);
    if (-sd >= lens) {
        return content.eval(p);
    }
    float x = 1.0 - (-sd) / lens;
    float k = 1.0 - sqrt(max(1.0 - x * x, 0.0));
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradRound(q, halfSize, gradRadius) + 0.12 * normalize(q));
    float2 off = bend * k * grad;
    float2 split = off * dispersion * (q.x * q.y) / (halfSize.x * halfSize.y);
    float2 lo = shape.xy;
    float2 hi = shape.zw - 1.0;
    half4 col = content.eval(clamp(p + off, lo, hi));
    col.r = content.eval(clamp(p + off + split, lo, hi)).r;
    col.b = content.eval(clamp(p + off - split, lo, hi)).b;
    float edge = 1.0 - smoothstep(0.0, lens * 2.0, max(-sd, 0.0));
    half4 t = half4(tint);
    half4 r = half4(rim);
    col = mix(col, t, t.a);
    col = mix(col, r, r.a * half(edge));
    col.a = 1.0;
    return col;
}
"""
    }
}

/** 选中项的胶囊：玻璃下面那层的颜色，不描边。 */
class PillDrawable(private val host: View) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val inset = GlassKit.dp(host, 4f)

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        rect.inset(inset, inset)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val r = rect.height() / 2f
        fill.color = if (GlassKit.night(host)) 0x33FFFFFF else 0x8CFFFFFF.toInt()
        canvas.drawRoundRect(rect, r, r, fill)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 一个宿主戴着玻璃，跟着 view 一起生灭。 */
private class GlassOn(override val anchor: View) : DecorState {
    override fun undo() = GlassKit.remove(anchor)
}

/** 首页顶栏（头像、标题、快捷入口）做成盖在会话列表上的整幅玻璃。 */
object GlassTitle : ViewRule("glass_title") {
    private val titles = Decor<GlassOn>()

    override fun match(v: View): Boolean {
        if (!v.javaClass.name.endsWith("TitleAreaLeftLayout")) return false
        val row = v.parent as? ViewGroup ?: return false
        val outer = row.parent as? ViewGroup
        // QQ 自己的模糊层就在这行旁边；玻璃贴包住两者的那一层。
        val host = if (outer != null && (0 until outer.childCount).any { GlassKit.isBlur(outer.getChildAt(it)) }) outer else row
        if (titles[host] == null && GlassKit.apply(host, capsule = false)) {
            titles.put(GlassOn(host))
            hits++
        }
        return false
    }

    override fun uninstall() {
        titles.dropAll()
        super.uninstall()
    }
}

/**
 * 聊天页：标题栏做成整幅玻璃、透镜压在底边上；TG 化那一行输入栏做成浮起来的玻璃胶囊。
 * 玻璃上身时把输入框那层灰底清掉，关掉时还回去。
 */
object GlassChat : ViewRule("glass_chat") {
    private val rows = Decor<Row>()
    private val titles = Decor<GlassOn>()

    override fun match(v: View): Boolean {
        when {
            v.tag == InputBar.ROW_TAG -> (rows[v] ?: rows.put(Row(v))).sync()
            v.javaClass.name.endsWith("AIOTitleRelativeLayout") -> {
                val host = v.parent as? ViewGroup
                if (host != null && titles[host] == null && GlassKit.apply(host, capsule = false)) {
                    titles.put(GlassOn(host))
                    hits++
                }
            }
        }
        return false
    }

    override fun uninstall() {
        rows.dropAll()
        titles.dropAll()
        super.uninstall()
    }

    private class Row(override val anchor: View) : DecorState {
        private val row = anchor
        private val lp = row.layoutParams as? ViewGroup.MarginLayoutParams
        private val margins = lp?.let { intArrayOf(it.leftMargin, it.rightMargin, it.bottomMargin) }
        private val cleared = HashMap<View, Drawable?>()

        init {
            lp?.let {
                it.leftMargin = GlassKit.dp(row, 8f).toInt()
                it.rightMargin = GlassKit.dp(row, 8f).toInt()
                it.bottomMargin = GlassKit.dp(row, 8f).toInt()
                row.layoutParams = it
            }
            GlassKit.apply(row, capsule = true, elevationDp = 3f)
            sync()
        }

        fun sync() {
            findEdit(row)?.let { edit -> clear(edit); (edit.parent as? View)?.let(::clear) }
        }

        private fun clear(v: View) {
            if (cleared.containsKey(v)) return
            cleared[v] = v.background
            v.background = null
        }

        private fun findEdit(v: View): View? {
            if (v.javaClass.simpleName.contains("EditText")) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) findEdit(v.getChildAt(i))?.let { return it }
            return null
        }

        override fun undo() {
            lp?.let {
                it.leftMargin = margins!![0]
                it.rightMargin = margins[1]
                it.bottomMargin = margins[2]
                row.layoutParams = it
            }
            GlassKit.remove(row)
            cleared.forEach { (v, bg) -> v.background = bg }
            cleared.clear()
        }
    }
}
