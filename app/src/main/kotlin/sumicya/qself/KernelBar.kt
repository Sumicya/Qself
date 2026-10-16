// SPDX-License-Identifier: GPL-3.0-or-later
// KernelSU 3186a2fe: FloatingBottomBar / DampedDragAnimation 的无依赖 View 移植。
// Lens 和 BloomStroke AGSL 保留上游 Apache-2.0 实现，来源和许可见 third_party/NOTICE.md。
package sumicya.qself

import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Choreographer
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import java.util.WeakHashMap
import kotlin.math.*

/** Compose spring 的解析解，不以固定时长的插值器冒充弹簧。单位质量。 */
internal class KernelSpring(val damping: Float, val stiffness: Float, initial: Float = 0f) {
    var value = initial
    var velocity = 0f
    var target = initial
    fun snap(to: Float) { value = to; target = to; velocity = 0f }
    fun step(seconds: Float): Boolean {
        val t = seconds.coerceIn(0f, .064f)
        val w = sqrt(stiffness)
        val x = value - target
        val v = velocity
        if (damping == 1f) {
            val c = v + w * x
            val e = exp(-w * t)
            value = target + (x + c * t) * e
            velocity = (v - w * c * t) * e
        } else {
            val a = damping * w
            val b = w * sqrt(1f - damping * damping)
            val c = (v + a * x) / b
            val e = exp(-a * t)
            value = target + e * (x * cos(b * t) + c * sin(b * t))
            velocity = e * (v * cos(b * t) - (a * c + b * x) * sin(b * t))
        }
        if (abs(value - target) < .001f && abs(velocity) < .0625f) { snap(target); return false }
        return true
    }
}

internal fun kernelIndex(x: Float, width: Float, count: Int, rtl: Boolean): Int =
    if (count <= 0 || width <= 8f) 0 else (((if (rtl) width - x else x) - 4f) /
        ((width - 8f) / count)).toInt().coerceIn(0, count - 1)

/** QQ 仍持有真实页签、点击及无障碍；这里只接管底栏背景、按压和横向选择。 */
internal class KernelBar(private val host: View, private val behind: (Canvas) -> Unit,
    private val invalidate: () -> Unit) : SensorEventListener {
    private val dp = host.dp
    private val scene = RenderNode("ksu-scene")
    private val base = RenderNode("ksu-base")
    private val indicator = RenderNode("ksu-indicator")
    private val shadow = RenderNode("ksu-inner-shadow")
    private val lens = RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER)
    private val colorControls = RuntimeShader(COLOR_CONTROLS_SHADER)
    private val dispersion = RuntimeShader(ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER)
    private val bloom = RuntimeShader(BLOOM_STROKE_SHADER_DUAL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val value = KernelSpring(1f, 1000f)
    private val progress = KernelSpring(1f, 1000f)
    private val scaleX = KernelSpring(.6f, 250f, 1f)
    private val scaleY = KernelSpring(.7f, 250f, 1f)
    private val speed = KernelSpring(.5f, 300f)
    private val offset = KernelSpring(1f, 300f)
    private val highlight = KernelSpring(.5f, 300f)
    private val springs = arrayOf(value, progress, scaleX, scaleY, speed, offset, highlight)
    private val frameClock = Choreographer.getInstance()
    private val ease = PathInterpolator(0f, 0f, .58f, 1f)
    private var posted = false
    private var lastFrame = 0L
    private var ready = false
    private var pressed = false
    private var releasing = false
    private var dragging = false
    private var chosen: View? = null
    private var dragX = 0f
    private var angle = -PI.toFloat() / 2
    private var attached = false
    private val sensors = host.context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val originals = WeakHashMap<View, FloatArray>()
    private var baseKey = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            posted = false
            val dt = if (lastFrame == 0L) 1f / 60 else (now - lastFrame) / 1_000_000_000f
            lastFrame = now
            speed.target = value.velocity / (tabs().size - 1).coerceAtLeast(1)
            var moving = false
            for (spring in springs) moving = spring.step(dt) || moving
            if (releasing && abs(value.value - value.target) < (tabs().size - 1).coerceAtLeast(1) * .025f) {
                releasing = false
                progress.target = 0f; scaleX.target = 1f; scaleY.target = 1f
                moving = true
            }
            applyLayers()
            invalidate()
            if (moving) schedule() else lastFrame = 0L
        }
    }
    private fun schedule() {
        if (!attached || posted) return
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            springs.forEach { it.snap(it.target) }
            if (releasing) { releasing = false; progress.snap(0f); scaleX.snap(1f); scaleY.snap(1f) }
            applyLayers(); invalidate(); return
        }
        posted = true; frameClock.postFrameCallback(frame)
    }
    private fun tabs(): List<View> {
        val strip = (host as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return emptyList()
        return (0 until strip.childCount).map { strip.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
    }
    private fun remember(view: View) = originals.getOrPut(view) {
        floatArrayOf(view.scaleX, view.scaleY, view.translationX)
    }
    private fun panel(): Float = 4 * dp * sign(offset.value) *
        ease.getInterpolation((abs(offset.value) / host.width.coerceAtLeast(1)).coerceIn(0f, 1f))
    private fun applyLayers() {
        val p = progress.value.coerceIn(0f, 1f)
        val original = remember(host)
        val grow = 1f + 16 * dp / host.width.coerceAtLeast(1) * p
        host.scaleX = original[0] * grow; host.scaleY = original[1] * grow
        val strip = (host as? ViewGroup)?.getChildAt(0)
        strip?.let { it.translationX = remember(it)[2] + panel() }
        tabs().forEach { tab ->
            val old = remember(tab)
            val factor = if (tab === chosen || tab.isSelected) 1f + .2f * p else 1f
            tab.scaleX = old[0] * factor; tab.scaleY = old[1] * factor
        }
    }
    fun sync(isPressed: Boolean) {
        val tabs = tabs()
        val selected = tabs.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        if (!ready && tabs.isNotEmpty()) { value.snap(selected.toFloat()); chosen = tabs.getOrNull(selected); ready = true }
        if (!dragging && chosen !== tabs.getOrNull(selected)) {
            chosen = tabs.getOrNull(selected); value.target = selected.toFloat()
            press(true); releasing = true; schedule()
        }
        if (!dragging && isPressed != pressed) { pressed = isPressed; press(isPressed) }
    }
    private fun press(down: Boolean) {
        if (down) {
            releasing = false; progress.target = 1f
            scaleX.target = 78f / 56f; scaleY.target = 78f / 56f
            highlight.target = 1f
        } else { releasing = true; highlight.target = 0f; offset.target = 0f }
        schedule()
    }
    fun begin(x: Float) {
        if (tabs().isEmpty()) return
        dragging = true; dragX = x
        value.target = kernelIndex(x / dp, host.width / dp, tabs().size, host.layoutDirection == View.LAYOUT_DIRECTION_RTL).toFloat()
        press(true)
    }
    fun drag(x: Float) {
        if (!dragging) return
        val tabs = tabs()
        if (x in 0f..host.width.toFloat() && dragX in 0f..host.width.toFloat()) {
            val dx = x - dragX
            val width = (host.width - 8 * dp) / tabs.size.coerceAtLeast(1)
            value.target = (value.target + dx / width * if (host.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1)
                .coerceIn(0f, (tabs.size - 1).coerceAtLeast(0).toFloat())
            offset.snap(offset.value + dx)
        }
        dragX = x; schedule()
    }
    fun end(commit: Boolean) {
        if (!dragging) return
        dragging = false
        val tabs = tabs()
        val index = if (commit) value.target.roundToInt().coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
            else tabs.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        chosen = tabs.getOrNull(index)
        value.target = index.toFloat()
        if (commit) chosen?.performClick()
        press(false)
    }
    fun attach() {
        if (attached) return
        attached = true
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }
    fun stop() {
        attached = false; dragging = false; posted = false; lastFrame = 0L
        frameClock.removeFrameCallback(frame); sensors.unregisterListener(this)
        originals.forEach { (view, old) -> view.scaleX = old[0]; view.scaleY = old[1]; view.translationX = old[2] }
        originals.clear(); ready = false; chosen = null; releasing = false; pressed = false
        springs.forEach { it.snap(if (it === scaleX || it === scaleY) 1f else 0f) }
        scene.discardDisplayList(); base.discardDisplayList(); indicator.discardDisplayList(); shadow.discardDisplayList()
        baseKey = 0L
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        var x = event.values[0] / SensorManager.GRAVITY_EARTH
        var y = event.values[1] / SensorManager.GRAVITY_EARTH
        val oldX = x
        when (host.display?.rotation) {
            Surface.ROTATION_90 -> { x = -y; y = oldX }
            Surface.ROTATION_180 -> { x = -x; y = -y }
            Surface.ROTATION_270 -> { x = y; y = -oldX }
        }
        val step = (3 * PI / 180).toFloat()
        val next = if (x*x + y*y > .01f) (atan2(y, x) / step).roundToInt() * step else -PI.toFloat()/2
        if (angle != next) { angle = next; invalidate() }
    }
    private fun rounded(canvas: Canvas, rect: RectF, body: () -> Unit) {
        val save = canvas.save()
        try { path.reset(); path.addRoundRect(rect, rect.height()/2, rect.height()/2, Path.Direction.CW); canvas.clipPath(path); body() }
        finally { canvas.restoreToCount(save) }
    }
    private fun configure(shader: RuntimeShader, w: Float, h: Float, pad: Float, height: Float, amount: Float, depth: Boolean) {
        shader.setFloatUniform("size", w, h); shader.setFloatUniform("offset", -pad, -pad)
        val r = min(w, h)/2
        shader.setFloatUniform("cornerRadii", r, r, r, r)
        shader.setFloatUniform("refractionHeight", height.coerceAtLeast(.001f))
        shader.setFloatUniform("refractionAmount", -amount)
        shader.setFloatUniform("depthEffect", if (depth) 1f else 0f)
    }
    private fun shine(canvas: Canvas, rect: RectF, alpha: Float, degrees: Float) {
        if (alpha <= 0f) return
        val w = rect.width(); val h = rect.height(); val r = min(w,h)/2
        bloom.setFloatUniform("halfView", w/2, h/2); bloom.setFloatUniform("halfViewFloor", floor(w/2), floor(h/2))
        bloom.setFloatUniform("cornerRadii", r,r,r,r); bloom.setFloatUniform("strokeWidth", dp)
        bloom.setFloatUniform("innerBlurRadius", 2*dp); bloom.setFloatUniform("innerBlurRadiusSq", 4*dp*dp)
        bloom.setFloatUniform("highlightAlpha", alpha); bloom.setColorUniform("strokeColor", Color.WHITE)
        bloom.setFloatUniform("strokeAlphaMul", .12f)
        val rad = angle + degrees * PI.toFloat()/180
        val len = sqrt(1f + .05f*.05f)
        bloom.setFloatUniform("lightDir1", cos(rad)/len, sin(rad)/len, -.05f/len)
        bloom.setColorUniform("lightColor1", Color.WHITE); bloom.setFloatUniform("lightIntensity1", 1f)
        val l2 = sqrt(.1f*.1f + .5f*.5f)
        bloom.setFloatUniform("lightDir2", 0f, .1f/l2, -.5f/l2)
        bloom.setColorUniform("lightColor2", Color.WHITE); bloom.setFloatUniform("lightIntensity2", .4f)
        val save = canvas.save()
        try {
            canvas.translate(rect.left, rect.top)
            paint.shader = bloom; paint.blendMode = BlendMode.PLUS
            canvas.drawRect(0f,0f,w,h,paint)
        } finally { paint.shader = null; paint.blendMode = null; canvas.restoreToCount(save) }
    }
    fun draw(canvas: Canvas, bounds: Rect, alpha: Int, filter: ColorFilter?) {
        if (bounds.isEmpty) return
        if (!canvas.isHardwareAccelerated) {
            paint.color = if (night()) 0xFF242428.toInt() else 0xFFF5F5F5.toInt()
            canvas.drawRoundRect(RectF(bounds),bounds.height()/2f,bounds.height()/2f,paint)
            return // 软件截图不能执行 RenderNode；不是把纯色路径当作玻璃验收。
        }
        val w = bounds.width(); val h = bounds.height(); val pad = (40*dp).toInt()
        val p = progress.value.coerceIn(0f,1f); val surface = RectF(0f,0f,w.toFloat(),h.toFloat())
        val layer = canvas.saveLayer(RectF(bounds), Paint().apply { this.alpha = alpha; colorFilter = filter })
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        try {
            scene.setPosition(-pad,-pad,w+pad,h+pad)
            val sc = scene.beginRecording(w+2*pad,h+2*pad)
            try { sc.translate(pad.toFloat(),pad.toFloat()); behind(sc) } finally { scene.endRecording() }
            val key = (w.toLong() shl 32) or h.toLong()
            if (baseKey != key) {
                configure(lens,w.toFloat(),h.toFloat(),pad.toFloat(),24*dp,24*dp,false)
                colorControls.setFloatUniform("in_brightness", 0f)
                colorControls.setFloatUniform("in_contrast", 1f)
                colorControls.setFloatUniform("in_saturation", 1.5f)
                val color = RenderEffect.createRuntimeShaderEffect(colorControls,"child")
                val blur = RenderEffect.createBlurEffect(4*dp,4*dp,color,Shader.TileMode.CLAMP)
                base.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens,"content"),blur))
                baseKey = key
            }
            base.setPosition(-pad,-pad,w+pad,h+pad)
            val bc = base.beginRecording(w+2*pad,h+2*pad)
            try { bc.translate(pad.toFloat(),pad.toFloat()); bc.drawRenderNode(scene) } finally { base.endRecording() }
            val shift = panel()
            canvas.translate(shift,0f)
            rounded(canvas,surface) {
                canvas.drawRenderNode(base)
                paint.color = if (night()) 0x66242424 else 0x66FFFFFF
                canvas.drawRoundRect(surface,h/2f,h/2f,paint)
                val hp = highlight.value.coerceIn(0f,1f)
                if (hp > 0) {
                    paint.blendMode = BlendMode.PLUS
                    paint.color = Color.argb((.06f*hp*255).toInt(),255,255,255)
                    canvas.drawRect(surface,paint)
                    paint.shader = RadialGradient((value.value+.5f)*((w-8*dp)/tabs().size.coerceAtLeast(1))+4*dp,
                        h/2f,h*1.2f,intArrayOf(Color.argb((.12f*hp*255).toInt(),255,255,255),Color.TRANSPARENT),
                        floatArrayOf(.5f,1f),Shader.TileMode.CLAMP)
                    canvas.drawRect(surface,paint); paint.shader = null; paint.blendMode = null
                }
            }
            shine(canvas,surface,.75f,-45f)
            if (!ready || tabs().isEmpty()) return
            val tw = (w-8*dp)/tabs().size
            val pos = if (host.layoutDirection == View.LAYOUT_DIRECTION_RTL) tabs().lastIndex-value.value else value.value
            val rect = RectF(4*dp+pos*tw,4*dp,4*dp+(pos+1)*tw,60*dp)
            val vel = speed.value/10
            val sx = scaleX.value / (1f-(vel*.75f).coerceIn(-.2f,.2f))
            val sy = scaleY.value * (1f-(vel*.25f).coerceIn(-.2f,.2f))
            val save = canvas.save()
            try {
                canvas.scale(sx,sy,rect.centerX(),rect.centerY())
                configure(dispersion,tw,56*dp,pad.toFloat(),10*dp*p,14*dp*p,true)
                dispersion.setFloatUniform("chromaticAberration",.5f)
                indicator.setRenderEffect(RenderEffect.createRuntimeShaderEffect(dispersion,"content"))
                indicator.setPosition((rect.left-pad).toInt(),(rect.top-pad).toInt(),(rect.right+pad).toInt(),(rect.bottom+pad).toInt())
                val ic = indicator.beginRecording((tw+2*pad).toInt(),(56*dp+2*pad).toInt())
                try { ic.translate(pad-rect.left,pad-rect.top); ic.drawRenderNode(base) } finally { indicator.endRecording() }
                rounded(canvas,rect) {
                    canvas.drawRenderNode(indicator)
                    paint.color = if (night()) 0x66242424 else 0x66FFFFFF
                    canvas.drawRect(rect,paint)
                    paint.color = Color.argb(((.1f*(1-p)+.03f*p)*255).toInt(),if(night())255 else 0,if(night())255 else 0,if(night())255 else 0)
                    canvas.drawRect(rect,paint)
                    if (p > .001f) {
                        val blurRadius = 8*dp*p
                        shadow.setPosition((rect.left-pad).toInt(),(rect.top-pad).toInt(),(rect.right+pad).toInt(),(rect.bottom+pad).toInt())
                        shadow.setRenderEffect(RenderEffect.createBlurEffect(blurRadius,blurRadius,Shader.TileMode.DECAL))
                        val sh = shadow.beginRecording((tw+2*pad).toInt(),(56*dp+2*pad).toInt())
                        try {
                            val outline = RectF(pad.toFloat(),pad.toFloat(),tw+pad,56*dp+pad)
                            val mask = Path().apply { addRoundRect(outline,28*dp,28*dp,Path.Direction.CW) }
                            sh.clipPath(mask)
                            paint.color = Color.argb((.15f*p*255).toInt(),0,0,0)
                            sh.drawRoundRect(outline,28*dp,28*dp,paint)
                            sh.translate(0f,blurRadius)
                            paint.blendMode = BlendMode.CLEAR
                            sh.drawRoundRect(outline,28*dp,28*dp,paint)
                            paint.blendMode = null
                        } finally { shadow.endRecording() }
                        canvas.drawRenderNode(shadow)
                    }
                }
                shine(canvas,rect,p,90f)
            } finally { canvas.restoreToCount(save) }
        } finally { paint.shader=null; paint.blendMode=null; canvas.restoreToCount(layer) }
    }
}

private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

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
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}
"""

private const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
"""

private const val ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}
"""

private const val BLOOM_STROKE_SHADER_DUAL = """
uniform float2 halfView;
uniform float2 halfViewFloor;
uniform float4 cornerRadii;
uniform float strokeWidth;
uniform float innerBlurRadius;
uniform float innerBlurRadiusSq;
uniform float highlightAlpha;

layout(color) uniform half4 strokeColor;
uniform float strokeAlphaMul;

uniform float3 lightDir1;
layout(color) uniform half4 lightColor1;
uniform float lightIntensity1;

uniform float3 lightDir2;
layout(color) uniform half4 lightColor2;
uniform float lightIntensity2;

float pickRadius(float2 fragCoord, float4 radii) {
    float2 up = fragCoord.y < halfView.y ? radii.xy : radii.zw;
    return fragCoord.x < halfView.x ? up.x : up.y;
}

// caller passes non-negative pos (already abs-folded), so skip the redundant abs.
float roundedBoxSDF(float2 pos, float2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    float2 d = pos - halfSize + radius;
    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - radius;
}

float3 getNormal(float2 fragCoord, float sdf, float R) {
    float2 xy = fragCoord - halfViewFloor;
    float2 xy_a = abs(xy);
    float t = smoothstep(-innerBlurRadius, 0.0, sdf);
    float z = sqrt(max(innerBlurRadiusSq - t * t, 0.0));
    float3 coord = float3(xy_a, -z);

    float2 corner = halfView - R;
    corner.x = min(corner.x, xy_a.x);
    corner.y = min(corner.y, xy_a.y);

    float2 dir = normalize(coord.xy - corner.xy);
    corner += dir * (R - innerBlurRadius);

    if (any(lessThan(xy_a, corner))) {
        return float3(0.0, 0.0, -1.0);
    }

    float2 signal = sign(xy);
    float3 n = normalize(coord - float3(corner, 0.0));
    n.xy *= signal;
    return n;
}

half4 main(float2 fragCoord) {
    float2 xy = abs(fragCoord - halfView);

    float originRadius = pickRadius(fragCoord, cornerRadii);
    float R = max(originRadius, innerBlurRadius);

    if (all(lessThan(xy, halfView - R))) {
        return half4(0.0);
    }

    float sdf = roundedBoxSDF(xy, halfView, originRadius);
    half outMask = half(smoothstep(0.0, -1.0, sdf));
    float strokeAlpha = smoothstep(-strokeWidth, -strokeWidth + 1.0, sdf);

    // Native: stroke = uStrokeColor * sa; result += stroke.rgb * stroke.a
    //       = strokeColor.rgb * strokeColor.a * sa^2
    half3 rgb = strokeColor.rgb * half(strokeAlphaMul * strokeAlpha * strokeAlpha);

    float3 n = getNormal(fragCoord, sdf, R);

    float l1 = dot(n.xy, lightDir1.xy);
    rgb += half(l1 * l1 * lightIntensity1) * lightColor1.rgb;
    float l2 = dot(n.xy, lightDir2.xy);
    rgb += half(l2 * l2 * lightIntensity2) * lightColor2.rgb;

    return half4(rgb * half(highlightAlpha), 1.0) * outMask;
}
"""

private const val COLOR_CONTROLS_SHADER = """
    uniform shader child;
    uniform float in_brightness;
    uniform float in_contrast;
    uniform float in_saturation;

    half4 main(float2 xy) {
        half4 src = child.eval(xy);
        half a = src.a;
        if (a < 0.001) return src;

        half3 c = src.rgb / a;

        if (in_brightness != 0.0) {
            c = pow(c, half3(2.2));
            if (in_brightness > 0.0) {
                c = mix(c, half3(1.0), half(in_brightness));
            } else {
                c = c * half(1.0 + in_brightness);
            }
            c = pow(c, half3(0.45454545));
        }

        if (in_contrast != 1.0) {
            c = (c - 0.5) * half(in_contrast) + 0.5;
        }

        if (in_saturation != 1.0) {
            half lum = dot(c, half3(0.2126, 0.7152, 0.0722));
            c = mix(half3(lum), c, half(in_saturation));
        }

        c = clamp(c, half3(0.0), half3(1.0));
        return half4(c * a, a);
    }
"""
