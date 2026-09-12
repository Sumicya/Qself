/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.RippleDrawable
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference

/**
 * Optical material for module settings, not a window/screen capture API.
 * The scene is the settings background itself. Glass samples that same scene at
 * its actual scrolled position, with a blur kernel, SDF edge refraction and specular rim.
 * Text and controls are drawn afterwards, never blurred. No timers or background work.
 */
internal object SettingsGlass {
    private const val SCENE = """
        uniform float2 viewport;
        uniform float dark;
        float3 scene(float2 point) {
            float2 p = point / max(viewport.x, 1.0);
            float3 base = mix(float3(0.91,0.94,0.97), float3(0.025,0.042,0.069), dark);
            float a = exp(-dot(p-float2(0.92,0.22),p-float2(0.92,0.22))*3.4);
            float b = exp(-dot(p-float2(0.08,1.12),p-float2(0.08,1.12))*4.1);
            base = mix(base, mix(float3(0.65,0.80,0.94),float3(0.10,0.22,0.34),dark), a*0.65);
            base = mix(base, mix(float3(0.73,0.86,0.83),float3(0.07,0.22,0.23),dark), b*0.55);
            // A restrained curved light ribbon gives the lens something to refract.
            float curve = 0.88 + 0.24*sin(p.x*3.6 + 0.2);
            float ribbon = exp(-pow((p.y-curve)/0.055, 2.0));
            base += ribbon * mix(float3(0.07,0.065,0.045),float3(0.025,0.055,0.065),dark);
            return clamp(base,0.0,1.0);
        }
    """
    private const val BACKGROUND = SCENE + """
        half4 main(float2 p) { return half4(scene(p),1.0); }
    """
    private const val LENS = SCENE + """
        uniform float2 size;
        uniform float2 origin;
        uniform float radius;
        uniform float density;
        uniform float softness;
        half4 main(float2 p) {
            float2 c = p-size*0.5;
            float2 q = abs(c)-size*0.5+radius;
            float sd = length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;
            float inside = max(-sd,0.0);
            float2 corner = max(q,0.0);
            float2 n = length(corner)>0.001 ? sign(c)*normalize(corner) :
                (q.x>q.y ? float2(sign(c.x),0.0) : float2(0.0,sign(c.y)));
            float band = min(18.0*density,min(size.x,size.y)*0.22);
            float edge = 1.0-smoothstep(0.0,max(band,1.0),inside);
            float2 uv = origin+p-n*edge*edge*12.0*density;
            float spread = mix(2.3,4.5,softness)*density;
            float3 col = scene(uv)*4.0;
            col += scene(uv+float2(spread,0.0))*2.0 + scene(uv-float2(spread,0.0))*2.0;
            col += scene(uv+float2(0.0,spread))*2.0 + scene(uv-float2(0.0,spread))*2.0;
            col += scene(uv+float2(spread,spread)) + scene(uv-float2(spread,spread));
            col += scene(uv+float2(spread,-spread)) + scene(uv+float2(-spread,spread));
            col /= 16.0;
            // Small chromatic separation at the rim, not across body text.
            col.r += (scene(uv+n*density).r-scene(uv).r)*edge;
            col.b += (scene(uv-n*density).b-scene(uv).b)*edge;
            col = mix(col,mix(float3(1.0),float3(0.055,0.073,0.095),dark),mix(0.16,0.29,softness));
            float spec = pow(max(dot(n,normalize(float2(-0.6,-0.8))),0.0),2.0);
            float rim = 1.0-smoothstep(0.2*density,1.35*density,inside);
            col = mix(col,float3(1.0),rim*(0.16+0.65*spec));
            float inner = exp(-pow((inside-2.3*density)/(1.1*density),2.0));
            col *= 1.0-inner*0.10;
            return half4(clamp(col,0.0,1.0),1.0);
        }
    """

    private interface Program {
        val shader: Shader
        fun scene(width: Float, height: Float, dark: Boolean)
        fun lens(w: Float, h: Float, x: Float, y: Float, radius: Float, density: Float, softness: Float)
    }

    @RequiresApi(33)
    private class OpticalProgram(lens: Boolean) : Program {
        private val runtime = RuntimeShader(if (lens) LENS else BACKGROUND)
        override val shader: Shader get() = runtime
        override fun scene(width: Float, height: Float, dark: Boolean) {
            runtime.setFloatUniform("viewport", width, height)
            runtime.setFloatUniform("dark", if (dark) 1f else 0f)
        }
        override fun lens(w: Float, h: Float, x: Float, y: Float, radius: Float, density: Float, softness: Float) {
            runtime.setFloatUniform("size", w, h)
            runtime.setFloatUniform("origin", x, y)
            runtime.setFloatUniform("radius", radius)
            runtime.setFloatUniform("density", density)
            runtime.setFloatUniform("softness", softness)
        }
    }

    private fun program(lens: Boolean): Program? = if (Build.VERSION.SDK_INT >= 33) {
        try { OpticalProgram(lens) } catch (_: RuntimeException) { null }
    } else null

    fun backdrop(p: SettingsVisuals.Palette, fallback: Drawable): Drawable = Backdrop(p, fallback)
    fun material(context: Context, p: SettingsVisuals.Palette, radius: Int, owner: View?, fallback: Drawable): Drawable =
        Material(context.resources.displayMetrics.density, p, radius, owner, fallback)

    /** Moving a cached RenderNode does not redraw its background. Refresh only on actual scrolling. */
    fun invalidateMaterials(view: View) {
        val background = view.background
        if (background is Material || background is RippleDrawable) background.invalidateSelf()
        if (view is ViewGroup) for (i in 0 until view.childCount) invalidateMaterials(view.getChildAt(i))
    }

    // Public to tests in the same module: a supported renderer must not silently fall back.
    fun isOptical(drawable: Drawable): Boolean = when (drawable) {
        is Material -> drawable.program != null
        is Backdrop -> drawable.program != null
        else -> false
    }

    private class Backdrop(val p: SettingsVisuals.Palette, val fallback: Drawable) : Drawable() {
        val program = if (p.mode == 2) null else SettingsGlass.program(false)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val effect = program
            if (effect == null || bounds.isEmpty || !canvas.isHardwareAccelerated()) { fallback.bounds = bounds; fallback.draw(canvas); return }
            effect.scene(bounds.width().toFloat(), bounds.height().toFloat(), p.dark)
            paint.shader = effect.shader
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), paint)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; fallback.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.OPAQUE
    }

    private class Material(val density: Float, val p: SettingsVisuals.Palette, val corner: Int,
                           owner: View?, val fallback: Drawable) : Drawable() {
        val program = if (p.mode == 2) null else SettingsGlass.program(true)
        private val ownerRef = WeakReference(owner)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val effect = program
            if (effect == null || bounds.isEmpty || !canvas.isHardwareAccelerated()) { fallback.bounds = bounds; fallback.draw(canvas); return }
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            var x = 0f; var y = 0f
            var view = ownerRef.get()
            var sceneW = maxOf(w, 360f*density)
            var sceneH = maxOf(h, 800f*density)
            while (view != null) {
                if (view.background is Backdrop) {
                    sceneW = view.width.toFloat().coerceAtLeast(1f)
                    sceneH = view.height.toFloat().coerceAtLeast(1f)
                    break
                }
                val parent = view.parent as? View ?: break
                x += view.x-parent.scrollX
                y += view.y-parent.scrollY
                sceneW = parent.width.toFloat().coerceAtLeast(1f)
                sceneH = parent.height.toFloat().coerceAtLeast(1f)
                view = parent
            }
            val r = minOf(corner*density, w/2f, h/2f)
            effect.scene(sceneW, sceneH, p.dark)
            effect.lens(w, h, x+bounds.left, y+bounds.top, r, density, if (p.mode == 0) 0f else 1f)
            paint.shader = effect.shader
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; fallback.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
