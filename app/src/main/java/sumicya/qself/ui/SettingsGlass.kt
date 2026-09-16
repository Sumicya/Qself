/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference

/** Captures only the module Activity's actual View tree, in GPU memory. No generated scene or disk image. */
internal object SettingsGlass {
    private const val LENS = """
        uniform shader content;
        uniform float2 size;
        uniform float pad;
        uniform float radius;
        uniform float density;
        uniform float2 light;
        uniform float deformation;
        half4 main(float2 pixel) {
            float2 p = pixel - float2(pad);
            float2 c = p-size*0.5;
            float2 q = abs(c)-size*0.5+radius;
            float sd = length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;
            float inside = max(-sd,0.0);
            float2 corner = max(q,0.0);
            float2 n = length(corner)>0.001 ? sign(c)*normalize(corner) :
                (q.x>q.y ? float2(sign(c.x),0.0) : float2(0.0,sign(c.y)));
            float edge = 1.0-smoothstep(0.0,18.0*density,inside);
            float2 uv = pixel-n*edge*edge*(11.0+4.0*deformation)*density;
            half4 col = content.eval(uv);
            col.r = mix(col.r,content.eval(uv+n*density).r,edge*0.65);
            col.b = mix(col.b,content.eval(uv-n*density).b,edge*0.65);
            float rim = 1.0-smoothstep(0.2*density,1.4*density,inside);
            float spec = pow(max(dot(n,normalize(light)),0.0),2.0);
            col.rgb = mix(col.rgb,half3(1.0),rim*(0.12+0.48*spec));
            col.rgb *= 1.0-exp(-pow((inside-2.6*density)/(1.2*density),2.0))*0.07;
            return col;
        }
    """
    private val fallbackReasons = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Drawable, String>())
    private fun fallback(drawable: Drawable, reason: String): Drawable {
        fallbackReasons[drawable] = "实色回退：$reason"
        return drawable
    }
    private fun activity(context: Context): Activity? {
        var current = context
        repeat(12) {
            if (current is Activity) return current as Activity
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }
    @JvmOverloads
    fun material(context: Context, p: SettingsVisuals.Palette, radius: Int, owner: View?, fallback: Drawable,
                 source: View? = activity(context)?.window?.decorView): Drawable {
        if (p.mode == 2) return fallback
        if (Build.VERSION.SDK_INT < 33) return fallback(fallback, "需要 Android 13 或更高版本")
        if (activity(context)?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
            && activity(context) != null) return fallback(fallback, "窗口禁止取景，遵守安全标记")
        if (source == null) return fallback(fallback, "没有可用的设置窗口取景源")
        if (owner != null && owner.rootView === source.rootView) return fallback(fallback, "已阻止窗口内自采样")
        return try { LiveMaterial(context.resources.displayMetrics.density, p, radius, owner, source, fallback) }
        catch (error: Throwable) { sumicya.qself.diagnostics.FeatureJournal.error("SettingsGlass", error); fallback(fallback, "镜头初始化失败，请查看功能错误记录") }
    }
    fun backdrop(p: SettingsVisuals.Palette, fallback: Drawable): Drawable = fallback
    fun isOptical(drawable: Drawable): Boolean = Build.VERSION.SDK_INT >= 33 && drawable is LiveMaterial && !drawable.failed
    fun observeStatus(drawable: Drawable, listener: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && drawable is LiveMaterial) drawable.statusListener = listener
        else listener(fallbackReasons[drawable] ?: "实色背景：当前选择不取景")
    }
    fun deform(drawable: Drawable?, amount: Float) {
        if (Build.VERSION.SDK_INT >= 33 && drawable is LiveMaterial) drawable.deform(amount)
    }
    fun dispose(drawable: Drawable?) {
        if (Build.VERSION.SDK_INT >= 33 && drawable is LiveMaterial) drawable.release()
    }

    @RequiresApi(33)
    private class LiveMaterial(private val density: Float, private val p: SettingsVisuals.Palette, private val corner: Int,
                               owner: View?, source: View, private val fallback: Drawable) : Drawable(), View.OnAttachStateChangeListener {
        private val ownerRef = WeakReference(owner)
        private val sourceRef = WeakReference(source)
        private val node = RenderNode("Qself real overlay backdrop")
        private val lens = RuntimeShader(LENS)
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.surface; alpha = if (p.mode == 0) 24 else 48 }
        private val clip = Path()
        private val at = IntArray(2)
        private val from = IntArray(2)
        private var oldX = Int.MIN_VALUE
        private var oldY = Int.MIN_VALUE
        private var oldW = 0
        private var oldH = 0
        private var dirty = true
        private var opacity = 255
        private var stretch = 0f
        private var effectStretch = -1f
        fun deform(amount: Float) {
            val next = amount.coerceIn(0f, 1f)
            if (kotlin.math.abs(stretch - next) > .001f) { stretch = next; invalidateSelf() }
        }
        private var sourceObserver: ViewTreeObserver? = null
        private var ownerObserver: ViewTreeObserver? = null
        private var status = "等待实际背景首帧"
        var statusListener: ((String) -> Unit)? = null
            set(value) { field = value; value?.invoke(status) }
        var failed = false
            private set
        private val sourceFrame = ViewTreeObserver.OnPreDrawListener { dirty = true; invalidateSelf(); true }
        private val ownerFrame = ViewTreeObserver.OnPreDrawListener {
            ownerRef.get()?.let {
                it.getLocationOnScreen(at)
                if (oldX != at[0] || oldY != at[1]) invalidateSelf()
            }
            true
        }
        init {
            owner?.addOnAttachStateChangeListener(this)
            if (owner?.isAttachedToWindow == true) observe()
        }
        private fun report(value: String) {
            if (status == value) return
            status = value
            ownerRef.get()?.post { statusListener?.invoke(value) }
        }
        private fun observe() {
            unobserve()
            sourceObserver = sourceRef.get()?.viewTreeObserver?.also { it.addOnPreDrawListener(sourceFrame) }
            ownerObserver = ownerRef.get()?.viewTreeObserver?.also { it.addOnPreDrawListener(ownerFrame) }
        }
        private fun unobserve() {
            sourceObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(sourceFrame)
            ownerObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(ownerFrame)
            sourceObserver = null; ownerObserver = null
        }
        fun release() { unobserve(); ownerRef.get()?.removeOnAttachStateChangeListener(this); node.discardDisplayList(); statusListener = null }
        override fun onViewAttachedToWindow(view: View) { dirty = true; observe(); invalidateSelf() }
        override fun onViewDetachedFromWindow(view: View) { unobserve(); node.discardDisplayList(); dirty = true }
        override fun draw(canvas: Canvas) {
            val source = sourceRef.get()
            if (failed || !canvas.isHardwareAccelerated || source == null || source.width == 0 || source.height == 0) {
                fallback.bounds = bounds; fallback.draw(canvas)
                report("实色回退：背景或硬件渲染当前不可用")
                return
            }
            if (bounds.isEmpty || opacity == 0) return
            try {
                val owner = ownerRef.get()
                owner?.getLocationOnScreen(at) ?: run { at[0] = 0; at[1] = 0 }
                source.getLocationOnScreen(from)
                val moved = oldX != at[0] || oldY != at[1]
                val w = bounds.width(); val h = bounds.height()
                val pad = (24 * density).toInt()
                val deformation = maxOf(stretch, (kotlin.math.abs(owner?.translationY ?: 0f) / (32 * density)).coerceIn(0f, 1f))
                val radius = minOf(corner * density * (1f + .25f * deformation), w / 2f, h / 2f)
                if (owner == null || dirty || oldX != at[0] || oldY != at[1] || oldW != w || oldH != h || !node.hasDisplayList()) {
                    node.setPosition(0, 0, w + 2 * pad, h + 2 * pad)
                    val capture = node.beginRecording(w + 2 * pad, h + 2 * pad)
                    try {
                        capture.drawColor(p.background)
                        capture.translate((pad - at[0] + from[0] - bounds.left).toFloat(), (pad - at[1] + from[1] - bounds.top).toFloat())
                        source.draw(capture)
                    } finally { node.endRecording() }
                    oldX = at[0]; oldY = at[1]; dirty = false
                }
                lens.setFloatUniform("light", -.6f + at[0] / maxOf(source.width.toFloat(), 1f) * .25f,
                    -.8f + at[1] / maxOf(source.height.toFloat(), 1f) * .2f)
                if (oldW != w || oldH != h || moved || effectStretch != deformation) {
                    effectStretch = deformation
                    lens.setFloatUniform("deformation", deformation)
                    lens.setFloatUniform("size", w.toFloat(), h.toFloat())
                    lens.setFloatUniform("pad", pad.toFloat()); lens.setFloatUniform("radius", radius)
                    lens.setFloatUniform("density", density)
                    val blur = (if (p.mode == 0) 2f else 5f) * density
                    node.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"),
                        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)))
                    oldW = w; oldH = h
                }
                clip.reset(); clip.addRoundRect(RectF(bounds), radius, radius, Path.Direction.CW)
                val save = canvas.saveLayerAlpha(RectF(bounds), opacity)
                try {
                canvas.clipPath(clip)
                canvas.translate(bounds.left - pad.toFloat(), bounds.top - pad.toFloat())
                canvas.drawRenderNode(node)
                canvas.translate(pad.toFloat(), pad.toFloat())
                canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, tint)
                } finally { canvas.restoreToCount(save) }
                report("实际背景取景 · 折射与模糊")
            } catch (error: Throwable) {
                failed = true; unobserve(); node.discardDisplayList()
                sumicya.qself.diagnostics.FeatureJournal.error("SettingsGlass", error)
                fallback.bounds = bounds; fallback.draw(canvas)
                report("实色回退：实际玻璃渲染失败")
            }
        }
        override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { tint.colorFilter = filter; invalidateSelf() }
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
