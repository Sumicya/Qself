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
 * Qself glass: whatever the host floats over is recorded into a RenderNode, blurred and lifted
 * in saturation, then bent by an AGSL lens near the edges — the rounded-rect SDF pushes the rim
 * outward with a touch of chromatic split. Same idea as the KernelSU / iOS 26 lens, no shader
 * library, no bitmap round trip: the node is a display list, re-recorded only when the content
 * under it moves.
 */
object GlassKit {
    private val applied = Decor<On>()

    val count: Int get() = applied.size

    /** Puts glass on [host], as its background. Repeats are no-ops. */
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

    fun remove(host: View) = applied[host]?.let(applied::drop)

    fun clearAll() = applied.dropAll()

    fun dp(v: View, x: Float) = x * v.resources.displayMetrics.density

    fun rectInWindow(v: View, out: Rect): Rect {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        out.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        return out
    }

    fun isBlur(v: View): Boolean = v.javaClass.simpleName.let { it.startsWith("QQBlurView") || it.startsWith("QQNativeBlurView") }

    /** QQ's own theme, which need not follow the system one. */
    private val qqNight by lazy {
        runCatching { Class.forName("com.tencent.mobileqq.utils.QQTheme", false, Core.loader).getMethod("isNowThemeIsNight") }.getOrNull()
    }

    fun night(v: View): Boolean = runCatching { qqNight!!.invoke(null) as Boolean }.getOrElse {
        v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private class On(override val anchor: View, capsule: Boolean, elevationDp: Float) : Decor.State {
        private val host = anchor
        private val background = host.background
        private val outline = host.outlineProvider
        private val clip = host.clipToOutline
        private val elevation = host.elevation
        private val surface = GlassSurface(host, capsule)
        private val held = ArrayList<View>()

        init {
            // QQ's own blur layer inside the host would paint over the glass.
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

/** The drawable itself. One per host. */
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

    /** The content under the glass moved: redraw. */
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
            // View.draw() expects the caller to have applied the view's own scroll.
            canvas.translate(-source.scrollX.toFloat(), -source.scrollY.toFloat())
            source.draw(canvas)
        } finally {
            node.endRecording()
        }
        return true
    }

    /**
     * What the host floats over: the biggest sibling (of the host or of any ancestor) that is
     * drawn before the host's branch, overlaps it and is not a hairline or a blur layer.
     */
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
            // Only the bottom edge of a full-bleed bar is on screen; the rest of the rect is pushed out.
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
        /** Why the AGSL lens could not be built, if it could not. */
        var shaderError: String? = null

        // Rounded-rect SDF, bending the sample outwards along the edge normal, hardest at the rim
        // (circleMap), with a small red/blue split towards the corners.
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

/** Selected-tab capsule: a tint of the glass underneath, no stroke. */
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

/** One host wearing glass, as per-view state that dies with the view. */
private class GlassOn(override val anchor: View) : Decor.State {
    override fun undo() = GlassKit.remove(anchor)
}

/** Home top bar (avatar, title, quick entry) as full-width glass over the chat list. */
object GlassTitle : ViewRule("glass_title") {
    private val titles = Decor<GlassOn>()

    override fun match(v: View): Boolean {
        if (!v.javaClass.name.endsWith("TitleAreaLeftLayout")) return false
        val row = v.parent as? ViewGroup ?: return false
        val outer = row.parent as? ViewGroup
        // QQ's own blur layer sits next to the row; the glass goes on whatever wraps both.
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
 * Chat screen: the title bar becomes full-width glass with the lens on its bottom edge, and the
 * Telegram-style input row becomes a floating glass capsule. The grey input box behind the row is
 * cleared while the glass is on, and given back on disable.
 */
object GlassChat : ViewRule("glass_chat") {
    private val rows = Decor<Row>()
    private val titles = Decor<GlassOn>()
    private val cleared = HashMap<View, android.graphics.drawable.Drawable?>()

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

    private inner class Row(override val anchor: View) : Decor.State {
        private val row = anchor
        private val lp = row.layoutParams as? ViewGroup.MarginLayoutParams
        private val margins = lp?.let { intArrayOf(it.leftMargin, it.rightMargin, it.bottomMargin) }

        init {
            lp?.let {
                it.leftMargin = GlassKit.dp(row, 8f).toInt()
                it.rightMargin = GlassKit.dp(row, 8f).toInt()
                it.bottomMargin = GlassKit.dp(row, 8f).toInt()
                row.layoutParams = it
            }
            GlassKit.apply(row, capsule = true, elevationDp = 3f)
            findEdit(row)?.let { edit -> clear(edit); (edit.parent as? View)?.let(::clear) }
        }

        fun sync() = findEdit(row)?.let { edit -> clear(edit); (edit.parent as? View)?.let(::clear) }

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
