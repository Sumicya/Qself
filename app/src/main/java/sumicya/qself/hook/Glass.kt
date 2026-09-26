// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver

/**
 * Qself glass: a light blur of whatever is drawn behind a view, saturation lifted, the backdrop
 * bent by a lens near the edges, and a soft tint that thickens towards the edges.
 * No specular highlight, no stroke: the edge is defined by refraction and the gradient alone.
 */
object GlassKit {
    /** QQ's own day/night theme, which need not follow the system. */
    private val qqNight by lazy {
        runCatching { Class.forName("com.tencent.mobileqq.utils.QQTheme", false, Runtime.loader).getMethod("isNowThemeIsNight") }.getOrNull()
    }

    fun night(v: View): Boolean = runCatching { qqNight!!.invoke(null) as Boolean }.getOrElse {
        v.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    fun dp(v: View, x: Float) = x * v.resources.displayMetrics.density

    private val loc = IntArray(2)
    fun rectInWindow(v: View, out: Rect): Rect {
        v.getLocationInWindow(loc)
        out.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        return out
    }

    fun isBlur(v: View) = v.javaClass.simpleName.let { it.startsWith("QQBlurView") || it.startsWith("QQNativeBlurView") || it == "QQBlurView" }
    fun isHairline(v: View, host: View) = (v.height in 1..2 && v.width >= host.width / 2) || (v.width in 1..2 && v.height >= host.height / 2)
}

/**
 * Everything painted behind [host], in paint order: each ancestor's background followed by its
 * children that come before the path to [host] and overlap it. [host] itself and its ancestors
 * are never drawn, so there is no feedback loop. QQ's own blur layers and 1px dividers behind
 * the host are reported separately so the caller can hide them.
 */
object Backdrop {
    class Layer(val view: View, val backgroundOnly: Boolean)

    private val hr = Rect()
    private val vr = Rect()

    fun layers(host: View, out: MutableList<Layer>, obstructions: MutableList<View>?) {
        out.clear()
        GlassKit.rectInWindow(host, hr)
        val levels = ArrayList<List<Layer>>()
        var child: View = host
        var parent = host.parent as? ViewGroup
        while (parent != null) {
            val level = ArrayList<Layer>()
            if (parent.background != null) level += Layer(parent, true)
            val idx = parent.indexOfChild(child)
            for (i in 0 until idx) {
                val v = parent.getChildAt(i)
                if (v.width <= 0 || v.height <= 0) continue
                if (!Rect.intersects(GlassKit.rectInWindow(v, vr), hr)) continue
                if (GlassKit.isBlur(v) || GlassKit.isHairline(v, host)) {
                    obstructions?.add(v)
                    continue
                }
                if (v.visibility != View.VISIBLE || v.alpha < 0.01f) continue
                level += Layer(v, false)
            }
            levels += level
            child = parent
            parent = parent.parent as? ViewGroup
        }
        for (i in levels.indices.reversed()) out += levels[i]
    }
}

class GlassSpec(
    /** Rounded all round with radius = height / 2, else a full-bleed bar with only the bottom edge shaped. */
    val capsule: Boolean,
    val blurDp: Float = 4f,
    val lensDp: Float = 24f,
    val refractDp: Float = 20f,
)

/** The glass itself, set as a view's background. */
class GlassDrawable(private val host: View, private val spec: GlassSpec) : Drawable() {
    private val node = RenderNode("qself-glass")
    private val layers = ArrayList<Backdrop.Layer>()
    private val hl = IntArray(2)
    private val vl = IntArray(2)
    private var drawing = false
    private val shader: RuntimeShader? = runCatching { RuntimeShader(SHADER) }
        .onFailure { shaderError = it.toString() }.getOrNull()
    private var effectKey = ""
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val night = GlassKit.night(host)
        val shaded = canvas.isHardwareAccelerated && !drawing && shader != null
        if (canvas.isHardwareAccelerated && !drawing) {
            drawing = true
            try {
                node.setPosition(0, 0, b.width(), b.height())
                updateEffect(b.width(), b.height(), night)
                record(b.width(), b.height())
                canvas.drawRenderNode(node)
            } catch (_: Throwable) {
            } finally {
                drawing = false
            }
        }
        if (!shaded) {
            // Software canvas (screenshots, transitions) or no AGSL: a flat tint.
            fill.color = if (night) 0x8C1C1C1E.toInt() else 0x80F4F4F7.toInt()
            rect.set(b)
            val r = if (spec.capsule) b.height() / 2f else 0f
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }

    private fun record(w: Int, h: Int) {
        Backdrop.layers(host, layers, null)
        host.getLocationInWindow(hl)
        val rc = node.beginRecording(w, h)
        try {
            val root = host.rootView
            root.background?.let { bg ->
                root.getLocationInWindow(vl)
                rc.save(); rc.translate((vl[0] - hl[0]).toFloat(), (vl[1] - hl[1]).toFloat()); bg.draw(rc); rc.restore()
            }
            for (l in layers) {
                val v = l.view
                v.getLocationInWindow(vl)
                rc.save()
                rc.translate((vl[0] - hl[0]).toFloat(), (vl[1] - hl[1]).toFloat())
                if (l.backgroundOnly) {
                    v.background?.let { bg -> bg.setBounds(0, 0, v.width, v.height); bg.draw(rc) }
                } else {
                    // View.draw() expects the caller to have applied the view's own scroll.
                    rc.translate(-v.scrollX.toFloat(), -v.scrollY.toFloat())
                    if (v.alpha < 1f) rc.saveLayerAlpha(v.scrollX.toFloat(), v.scrollY.toFloat(), (v.scrollX + v.width).toFloat(), (v.scrollY + v.height).toFloat(), (v.alpha * 255).toInt())
                    v.draw(rc)
                }
                rc.restore()
            }
        } finally {
            node.endRecording()
        }
    }

    private fun updateEffect(w: Int, h: Int, night: Boolean) {
        val key = "$w:$h:$night"
        if (key == effectKey) return
        effectKey = key
        val blurPx = GlassKit.dp(host, spec.blurDp)
        var fx = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
        fx = RenderEffect.createChainEffect(
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) })), fx,
        )
        val s = shader
        if (s != null) {
            val lens = GlassKit.dp(host, spec.lensDp)
            s.setFloatUniform("size", w.toFloat(), h.toFloat())
            if (spec.capsule) {
                s.setFloatUniform("shape", 0f, 0f, w.toFloat(), h.toFloat())
                s.setFloatUniform("radius", h / 2f)
                s.setFloatUniform("lens", minOf(lens, h / 2f))
            } else {
                // Only the bottom edge lies inside: the other three are pushed out of view.
                val out = lens * 2
                s.setFloatUniform("shape", -out, -out, w + out, h.toFloat())
                s.setFloatUniform("radius", 0f)
                s.setFloatUniform("lens", minOf(lens * 0.6f, h / 2f))
            }
            s.setFloatUniform("bend", GlassKit.dp(host, spec.refractDp) * if (spec.capsule) 1f else 0.5f)
            if (night) {
                s.setFloatUniform("tint", 0.11f, 0.11f, 0.12f, 0.46f)
                s.setFloatUniform("edge", 0.30f, 0.30f, 0.33f, 0.30f)
            } else {
                s.setFloatUniform("tint", 0.97f, 0.97f, 0.98f, 0.32f)
                s.setFloatUniform("edge", 1f, 1f, 1f, 0.38f)
            }
            fx = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(s, "img"), fx)
        }
        node.setRenderEffect(fx)
    }

    fun release() = node.discardDisplayList()

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Why the AGSL lens could not be built, if it could not (self-check report). */
        var shaderError: String? = null
            private set

        // Own lens: inside the rounded rect, within `lens` px of the edge, sample the backdrop
        // pulled inwards along the edge normal, harder the closer to the edge. Then a base tint,
        // and an edge tint that fades in towards the rim.
        private const val SHADER = """
uniform shader img;
uniform float2 size;
uniform float4 shape;
uniform float radius;
uniform float lens;
uniform float bend;
uniform float4 tint;
uniform float4 edge;

float sdBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 xy) {
    float2 c = (shape.xy + shape.zw) * 0.5;
    float2 b = (shape.zw - shape.xy) * 0.5;
    float2 p = xy - c;
    float d = sdBox(p, b, radius);
    if (d > 0.0) { return half4(0.0); }
    float e = clamp(1.0 + d / max(lens, 1.0), 0.0, 1.0);
    float2 g = float2(sdBox(p + float2(1.0, 0.0), b, radius) - sdBox(p - float2(1.0, 0.0), b, radius),
                      sdBox(p + float2(0.0, 1.0), b, radius) - sdBox(p - float2(0.0, 1.0), b, radius));
    float gl = length(g);
    float2 n = gl > 0.0001 ? g / gl : float2(0.0);
    float k = e * e * (3.0 - 2.0 * e);
    float2 uv = clamp(xy - n * bend * k * k, float2(0.5), size - float2(0.5));
    half4 col = img.eval(uv);
    half3 rgb = mix(col.rgb, half3(tint.rgb), half(tint.a));
    rgb = mix(rgb, half3(edge.rgb), half(edge.a) * half(pow(e, 1.6)));
    return half4(rgb, 1.0);
}
"""
    }
}

/**
 * Puts glass behind a view and keeps it fresh. The backdrop is re-recorded when something behind
 * may have changed: a scroll anywhere in the window, a layout pass, or (throttled to 4 Hz) any other
 * frame. It never invalidates itself in a loop, so an idle screen costs nothing. QQ's own blur
 * layers and dividers behind it are held down; same-size wrappers lose their solid background.
 * Listeners follow the host's window attachment. Everything is undone by [restore].
 */
class GlassSurface(val host: View, spec: GlassSpec, private val elevationDp: Float = 0f) {
    private val background = host.background
    private val outline = host.outlineProvider
    private val clip = host.clipToOutline
    private val elevation = host.elevation
    private val glass = GlassDrawable(host, spec)
    private val held = HashMap<View, Int>()
    private val cleared = HashMap<View, Drawable?>()
    private val scratchLayers = ArrayList<Backdrop.Layer>()
    private val scratchObs = ArrayList<View>()
    private var lastRefresh = 0L
    private var observer: ViewTreeObserver? = null

    private fun refresh() {
        lastRefresh = android.os.SystemClock.uptimeMillis()
        glass.invalidateSelf()
    }

    private val onScroll = ViewTreeObserver.OnScrollChangedListener { refresh() }
    private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { holdObstructions(); refresh() }
    private val onPreDraw = ViewTreeObserver.OnPreDrawListener {
        if (android.os.SystemClock.uptimeMillis() - lastRefresh > 250) refresh()
        true
    }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = listen()
        override fun onViewDetachedFromWindow(v: View) = unlisten()
    }

    init {
        host.background = glass
        if (spec.capsule) {
            host.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
            host.clipToOutline = true
        }
        if (elevationDp > 0) host.elevation = GlassKit.dp(host, elevationDp)
        holdObstructions()
        host.addOnAttachStateChangeListener(attach)
        if (host.isAttachedToWindow) listen()
    }

    private fun listen() {
        unlisten()
        observer = host.viewTreeObserver.also {
            it.addOnScrollChangedListener(onScroll)
            it.addOnGlobalLayoutListener(onLayout)
            it.addOnPreDrawListener(onPreDraw)
        }
    }

    private fun unlisten() {
        observer?.takeIf { it.isAlive }?.let {
            it.removeOnScrollChangedListener(onScroll)
            it.removeOnGlobalLayoutListener(onLayout)
            it.removeOnPreDrawListener(onPreDraw)
        }
        observer = null
    }

    private fun holdObstructions() {
        scratchObs.clear()
        Backdrop.layers(host, scratchLayers, scratchObs)
        val hr = GlassKit.rectInWindow(host, Rect())
        val vr = Rect()
        // QQ's blur layer may also sit inside the host; full-size children with a flat colour would hide the glass.
        (host as? ViewGroup)?.let { g ->
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (GlassKit.isBlur(c)) scratchObs += c
                else if (c.background is android.graphics.drawable.ColorDrawable && covers(GlassKit.rectInWindow(c, vr), hr)) clear(c)
            }
        }
        // A divider may be drawn after the host, right on its edge.
        (host.parent as? ViewGroup)?.let { p ->
            for (i in 0 until p.childCount) {
                val v = p.getChildAt(i)
                if (v === host || !GlassKit.isHairline(v, host)) continue
                GlassKit.rectInWindow(v, vr).inset(0, -4)
                if (Rect.intersects(vr, hr) || kotlin.math.abs(vr.centerY() - hr.top) < 8) scratchObs += v
            }
        }
        // Wrappers exactly around the host paint over whatever should show through.
        var a = host.parent as? View
        while (a != null && covers(hr, GlassKit.rectInWindow(a, vr))) {
            if (a.background != null) clear(a)
            a = a.parent as? View
        }
        for (v in scratchObs) {
            if (v !in held) held[v] = v.visibility
            if (v.visibility == View.VISIBLE) v.visibility = View.INVISIBLE
        }
    }

    /** [a] covers [b] give or take a couple of pixels. */
    private fun covers(a: Rect, b: Rect) = a.left <= b.left + 2 && a.top <= b.top + 2 && a.right >= b.right - 2 && a.bottom >= b.bottom - 2

    private fun clear(v: View) {
        if (v in cleared) return
        cleared[v] = v.background
        v.background = null
    }

    fun restore() {
        host.removeOnAttachStateChangeListener(attach)
        unlisten()
        host.background = background
        host.outlineProvider = outline
        host.clipToOutline = clip
        host.elevation = elevation
        held.forEach { (v, vis) -> v.visibility = vis }
        held.clear()
        cleared.forEach { (v, bg) -> v.background = bg }
        cleared.clear()
        glass.release()
    }
}

/** Selected-tab pill: a small tinted capsule with the same edge gradient, no stroke. */
class PillDrawable(private val host: View) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val inset = GlassKit.dp(host, 4f)

    init { rim.maskFilter = BlurMaskFilter(GlassKit.dp(host, 5f), BlurMaskFilter.Blur.INNER) }

    override fun draw(canvas: Canvas) {
        val night = GlassKit.night(host)
        rect.set(bounds)
        rect.inset(inset, inset)
        if (rect.width() <= 0 || rect.height() <= 0) return
        val r = rect.height() / 2
        fill.color = if (night) 0x33FFFFFF else 0x8CFFFFFF.toInt()
        canvas.drawRoundRect(rect, r, r, fill)
        // Inner glow towards the rim instead of an outline.
        rim.color = if (night) 0x40FFFFFF else 0x99FFFFFF.toInt()
        canvas.drawRoundRect(rect, r, r, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun setTintList(tint: android.content.res.ColorStateList?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Glass on one view, as per-view state that dies with it (see [Attached]). */
private class GlassOn(override val anchor: View, spec: GlassSpec) : Attached.State {
    val surface = GlassSurface(anchor, spec)
    override fun undo() = surface.restore()
}

/**
 * Chat screen in glass: the title bar becomes full-width glass (lens + gradient on its bottom edge),
 * and the Telegram-style input row (tg_input_bar) becomes a floating glass capsule; the grey panel
 * and input box behind it are cleared so the capsule floats over the chat background.
 */
object GlassChat : ViewRule("glass_chat") {
    private val titles = Attached<GlassOn>(Attached.key())
    private val inputs = Attached<InputGlass>(Attached.key())

    override fun match(v: View): Boolean {
        when {
            v.tag == TgInputBar.ROW_TAG -> (inputs[v] ?: inputs.put(InputGlass(v)).also { hits++ }).sync()
            v.javaClass.name.endsWith("AIOTitleRelativeLayout") -> (v.parent as? ViewGroup)?.let { host ->
                if (titles[host] == null) { titles.put(GlassOn(host, GlassSpec(capsule = false))); hits++ }
            }
        }
        return false
    }

    override fun uninstall() {
        titles.dropAll()
        inputs.dropAll()
        super.uninstall()
    }

    /** Taken off when the row leaves the window (e.g. tg_input_bar switched off), so nothing stays cleared. */
    private class InputGlass(override val anchor: View) : Attached.State {
        private val row = anchor
        private val cleared = HashMap<View, Drawable?>()
        private val lp = row.layoutParams as? ViewGroup.MarginLayoutParams
        private val margins = lp?.let { intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin) }
        private val surface: GlassSurface
        private val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = inputs.drop(this@InputGlass)
        }

        init {
            findEdit(row)?.let { e -> clear(e); (e.parent as? View)?.let(::clear) }
            lp?.let {
                val side = GlassKit.dp(row, 8f).toInt()
                it.leftMargin = side; it.rightMargin = side
                it.topMargin = GlassKit.dp(row, 4f).toInt(); it.bottomMargin = GlassKit.dp(row, 8f).toInt()
                row.layoutParams = it
            }
            surface = GlassSurface(row, GlassSpec(capsule = true), elevationDp = 3f)
            row.addOnAttachStateChangeListener(detach)
            sync()
        }

        /**
         * The grey panel behind the row is cleared only while it just wraps the input area; once it grows
         * (emoji / 「+」 panel open) it gets its background back so the panel never turns see-through.
         */
        fun sync() {
            val limit = row.height * 2 + GlassKit.dp(row, 40f)
            var p = row.parent as? View
            repeat(3) {
                val v = p ?: return
                if (v.height in 1..limit.toInt()) clear(v) else cleared.remove(v)?.let { bg -> v.background = bg }
                p = v.parent as? View
            }
        }

        private fun clear(v: View) {
            if (v in cleared) return
            cleared[v] = v.background
            v.background = null
        }

        private fun findEdit(v: View): View? {
            if (v.javaClass.simpleName.contains("EditText")) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) findEdit(v.getChildAt(i))?.let { return it }
            return null
        }

        override fun undo() {
            row.removeOnAttachStateChangeListener(detach)
            surface.restore()
            lp?.let {
                it.leftMargin = margins!![0]; it.topMargin = margins[1]; it.rightMargin = margins[2]; it.bottomMargin = margins[3]
                row.layoutParams = it
            }
            cleared.forEach { (v, bg) -> v.background = bg }
            cleared.clear()
        }
    }
}

/** Home top bar (avatar, title, quick-entry button) as full-width glass over the chat list. */
object GlassHomeTitle : ViewRule("glass_title") {
    private val titles = Attached<GlassOn>(Attached.key())

    override fun match(v: View): Boolean {
        if (!v.javaClass.name.endsWith("TitleAreaLeftLayout")) return false
        val row = v.parent as? ViewGroup ?: return false
        val outer = row.parent as? ViewGroup
        val host = if (outer != null && (0 until outer.childCount).any { GlassKit.isBlur(outer.getChildAt(it)) }) outer else row
        if (titles[host] == null) { titles.put(GlassOn(host, GlassSpec(capsule = false))); hits++ }
        return false
    }

    override fun uninstall() {
        titles.dropAll()
        super.uninstall()
    }
}
