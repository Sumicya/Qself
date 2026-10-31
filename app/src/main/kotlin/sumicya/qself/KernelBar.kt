// SPDX-License-Identifier: GPL-3.0-or-later
// KernelSU 3186a2fe: FloatingBottomBar / DampedDragAnimation 的无依赖 View 移植。
// Lens 和 BloomStroke AGSL 保留上游 Apache-2.0 实现，来源和许可见 third_party/NOTICE.md。
package sumicya.qself

import android.graphics.*
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.*

/** Compose spring 的解析解，不以固定时长的插值器冒充弹簧。单位质量。 */
internal class KernelSpring(val damping: Float, val stiffness: Float, initial: Float = 0f) {
    var value = initial
    var velocity = 0f
    var target = initial
    fun snap(to: Float) { value = to; target = to; velocity = 0f }
    fun step(seconds: Float): Boolean {
        val t = seconds.coerceAtLeast(0f)
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

/** 上游 BottomBarMiuix.defaultMinSize(76.dp)，56dp 是高度，不是页签宽度。 */
internal fun kernelTabWidth(density: Float, badgeWidth: Int): Int =
    max((76 * density).toInt(), badgeWidth)

internal fun kernelRadius(width: Float, height: Float, limit: Float): Float =
    min(limit, min(width, height) / 2).coerceAtLeast(0f)

/** QQ 仍持有真实页签、点击及无障碍；这里只接管底栏背景、按压和横向选择。 */
internal class KernelBar(private val host: View, private val behind: (Canvas) -> Unit,
    private val invalidate: () -> Unit, val standalone: Boolean = false, private val cornerRadius: Float = Float.MAX_VALUE, private val deformBody: Boolean = true) {
    private val dp = host.dp
    private val scene = RenderNode("ksu-scene")
    private val base = RenderNode("ksu-base")
    private val tabSurface = RenderNode("ksu-tab-surface")
    private val tabLens = RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER)
    private val tabContent = RenderNode("ksu-tab-content")
    private val combined = RenderNode("ksu-combined-backdrop")
    private var frames = 0
    private var lastSceneFrame = -1
    private var lastCombinedFrame = -1
    private val neutralTint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coordinates = Matrix()
    private var drawingTabs = false
    private val dispatchTabs = ViewGroup::class.java.getDeclaredMethod("dispatchDraw",Canvas::class.java).apply { isAccessible = true }
    private val indicator = RenderNode("ksu-indicator")
    private val shadow = RenderNode("ksu-inner-shadow")
    private val lens = RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER)
    private val colorControls = RuntimeShader(COLOR_CONTROLS_SHADER)
    private val dispersion = RuntimeShader(ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER)
    private val interactive = RuntimeShader(INTERACTIVE_HIGHLIGHT_SHADER)
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
    private var touching = false
    private var releasing = false
    private var dragging = false
    private var chosen: View? = null
    private var observed: View? = null
    private var tracker: VelocityTracker? = null
    private var trackingVelocity = false
    private var touchX = 0f
    private var touchY = 0f
    private var dragX = 0f
    private var attached = false
    private var baseKey = 0L
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            posted = false
            val dt = if (lastFrame == 0L) 1f / 60 else (now - lastFrame) / 1_000_000_000f
            lastFrame = now
            val before = value.value
            var moving = value.step(dt)
            // 与上游一样跟踪动画值，而非直接拿弹簧导数再滤一次。
            if (trackingVelocity && value.value != before) {
                val time = now / 1_000_000
                val sample = MotionEvent.obtain(time, time, MotionEvent.ACTION_MOVE, value.value, 0f, 0)
                try { tracker?.addMovement(sample) } finally { sample.recycle() }
                tracker?.computeCurrentVelocity(1000)
                speed.snap((tracker?.xVelocity ?: 0f) / (tabs().size - 1).coerceAtLeast(1))
            }
            for (spring in springs) if (spring !== value) moving = spring.step(dt) || moving
            if (releasing && abs(value.value - value.target) < (tabs().size - 1).coerceAtLeast(1) * .025f) {
                releasing = false
                progress.target = 0f; scaleX.target = 1f; scaleY.target = 1f
                moving = true
            }
            invalidate()
            if (moving) schedule() else lastFrame = 0L
        }
    }
    private fun schedule() {
        if (!attached || posted) return
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            springs.forEach { it.snap(it.target) }
            if (releasing) { releasing = false; progress.snap(0f); scaleX.snap(1f); scaleY.snap(1f) }
            invalidate(); return
        }
        posted = true; frameClock.postFrameCallback(frame)
    }
    private fun tabs(): List<View> {
        if (standalone) return emptyList()
        val strip = (host as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return emptyList()
        if (strip.visibility != View.VISIBLE) return emptyList()
        return (0 until strip.childCount).map { strip.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
    }
    private fun panel(): Float = 4 * dp * sign(offset.value) *
        ease.getInterpolation((abs(offset.value) / host.width.coerceAtLeast(1)).coerceIn(0f, 1f))
    // View 坐标/命中区域不变；材质、普通内容、强调色副本分别在 Canvas 上变换。
    fun sync() {
        val tabs = tabs()
        val selected = tabs.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        val actual = tabs.getOrNull(selected)
        if (!ready && tabs.isNotEmpty()) { value.snap(selected.toFloat()); chosen = actual; observed = actual; ready = true }
        // 只响应原生选择的变化，不能每帧用尚未回调的旧选择覆盖刚释放的目标。
        if (!dragging && !touching && observed !== actual) {
            observed = actual
            if (chosen !== actual) {
                chosen = actual; value.target = selected.toFloat()
                trackingVelocity = false; speed.target = 0f
                press(true); releasing = true; highlight.target = 0f; schedule()
            }
        }
    }
    // DOWN/UP 来自实际分发链，不依赖 QQ 子视图延迟设置 pressed 或内容页绑定成功。
    fun touchDown(x: Float, y: Float = host.height / 2f) {
        touchX = x; touchY = y
        sync()
        touching = true
        tracker?.clear(); trackingVelocity = true
        value.target = kernelIndex(x / dp, host.width / dp, tabs().size,
            host.layoutDirection == View.LAYOUT_DIRECTION_RTL).toFloat()
        press(true)
    }
    fun touchEnd(commit: Boolean = false) {
        if (!touching) return
        touching = false
        if (dragging) end(false)
        else {
            val items = tabs()
            val index = if (commit) value.target.roundToInt().coerceIn(0, items.lastIndex.coerceAtLeast(0))
                else items.indexOfFirst { it.isSelected }.coerceAtLeast(0)
            chosen = items.getOrNull(index)
            value.target = index.toFloat()
            if (commit) chosen?.performClick()
            press(false)
        }
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
        dragging = false; touching = false
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
        tracker = VelocityTracker.obtain()
    }
    fun stop() {
        attached = false; dragging = false; touching = false; posted = false; lastFrame = 0L
        frameClock.removeFrameCallback(frame)
        tracker?.recycle(); tracker = null; trackingVelocity = false
        ready = false; chosen = null; observed = null; releasing = false
        springs.forEach { it.snap(if (it === scaleX || it === scaleY) 1f else 0f) }
        scene.discardDisplayList(); base.discardDisplayList(); indicator.discardDisplayList(); shadow.discardDisplayList()
        tabSurface.discardDisplayList(); tabContent.discardDisplayList(); combined.discardDisplayList()
        baseKey = 0L
    }
    private fun rounded(canvas: Canvas, rect: RectF, body: () -> Unit) {
        val save = canvas.save()
        try {
            val r = kernelRadius(rect.width(), rect.height(), cornerRadius)
            path.reset(); path.addRoundRect(rect, r, r, Path.Direction.CW); canvas.clipPath(path); body()
        }
        finally { canvas.restoreToCount(save) }
    }
    private fun configure(shader: RuntimeShader, w: Float, h: Float, pad: Float, height: Float, amount: Float, depth: Boolean) {
        shader.setFloatUniform("size", w, h); shader.setFloatUniform("offset", -pad, -pad)
        val r = kernelRadius(w, h, cornerRadius)
        shader.setFloatUniform("cornerRadii", r, r, r, r)
        shader.setFloatUniform("refractionHeight", height.coerceAtLeast(.001f))
        shader.setFloatUniform("refractionAmount", -amount)
        shader.setFloatUniform("depthEffect", if (depth) 1f else 0f)
    }
    private fun drawAt(canvas: Canvas, node: RenderNode, x: Float, y: Float) {
        val save = canvas.save()
        try { canvas.translate(x,y); canvas.drawRenderNode(node) } finally { canvas.restoreToCount(save) }
    }
    private fun material(canvas: Canvas, node: RenderNode, rect: RectF, shift: Float) {
        rounded(canvas,rect) {
            drawAt(canvas,node,0f,rect.top)
            paint.color = if (night()) 0x66121212 else 0x66FAFAFA
            canvas.drawRect(rect,paint)
            val hp = highlight.value.coerceIn(0f,1f)
            if (hp > 0) {
                paint.blendMode = BlendMode.PLUS
                paint.color = Color.argb((.08f*hp*255).toInt(),255,255,255)
                canvas.drawRect(rect,paint)
                val tabWidth = (rect.width()-8*dp)/tabs().size.coerceAtLeast(1)
                val logical = (value.value+.5f)*tabWidth
                val hx = if (standalone) touchX.coerceIn(0f, rect.width()) else if (host.layoutDirection == View.LAYOUT_DIRECTION_RTL) rect.width()-logical else logical
                interactive.setFloatUniform("size", rect.width(), rect.height())
                interactive.setColorUniform("color", Color.argb((.15f*hp*255).toInt(),255,255,255))
                interactive.setFloatUniform("radius", min(rect.width(),rect.height())*1.5f)
                interactive.setFloatUniform("position", (hx+shift).coerceIn(0f,rect.width()), if (standalone) touchY.coerceIn(0f, rect.height()) else rect.height()/2)
                val save = canvas.save()
                try {
                    canvas.translate(rect.left,rect.top); paint.shader = interactive
                    canvas.drawRect(0f,0f,rect.width(),rect.height(),paint)
                } finally { canvas.restoreToCount(save); paint.shader = null; paint.blendMode = null }
            }
        }
    }
    private fun body(canvas: Canvas, w: Int, h: Int, p: Float) {
        val surface = RectF(0f,0f,w.toFloat(),h.toFloat())
        val save = canvas.save()
        try {
            val grow = if (deformBody) 1f+16*dp/w.coerceAtLeast(1)*p else 1f
            canvas.scale(grow,grow,surface.centerX(),surface.centerY())
            material(canvas,base,surface,panel())
        } finally { canvas.restoreToCount(save) }
    }
    private fun prepare(bounds: Rect) {
        val w = bounds.width(); val h = bounds.height(); val pad = (40*dp).toInt()
        scene.setPosition(-pad,-pad,w+pad,h+pad)
        // 底图重录要遍历背后的兄弟视图（聊天页就是整张消息列表）。真机 122Hz 下每帧录一次
        // 等于把列表画两遍，这里改成每两帧录一次：滚动最多差一帧，肉眼看不出来，帧预算省一半。
        frames++
        if (lastSceneFrame < 0 || frames - lastSceneFrame >= 2) {
            val sc = scene.beginRecording(w+2*pad,h+2*pad)
            try { sc.translate(pad-bounds.left.toFloat()-panel(),pad-bounds.top.toFloat()); behind(sc) } finally { scene.endRecording() }
            lastSceneFrame = frames
        }
        val blurRadius = (glassBlurDp()*dp).coerceAtLeast(.1f)
        val key = (w.toLong() shl 32) or (h.toLong() and 0xFFFFL) or ((blurRadius * 100f).toLong() shl 16)
        if (baseKey != key) {
            colorControls.setFloatUniform("in_brightness", 0f)
            colorControls.setFloatUniform("in_contrast", 1f)
            colorControls.setFloatUniform("in_saturation", 1.5f)
            val color = RenderEffect.createRuntimeShaderEffect(colorControls,"child")
            val blur = RenderEffect.createBlurEffect(blurRadius,blurRadius,color,Shader.TileMode.CLAMP)
            configure(lens,w.toFloat(),h.toFloat(),pad.toFloat(),24*dp,24*dp,false)
            base.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens,"content"),blur))
            if (!standalone) {
                configure(tabLens,w.toFloat(),56*dp,pad.toFloat(),24*dp,24*dp,false)
                tabSurface.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(tabLens,"content"),blur))
            }
            baseKey = key
        }
        base.setPosition(-pad,-pad,w+pad,h+pad)
        val bc = base.beginRecording(w+2*pad,h+2*pad)
        try { bc.translate(pad.toFloat(),pad.toFloat()); bc.drawRenderNode(scene) } finally { base.endRecording() }
    }
    fun draw(canvas: Canvas, bounds: Rect, alpha: Int, filter: ColorFilter?) {
        if (bounds.isEmpty) return
        if (!canvas.isHardwareAccelerated) {
            paint.color = if (night()) 0xFF242424.toInt() else 0xFFF5F6FA.toInt()
            val r = kernelRadius(bounds.width().toFloat(), bounds.height().toFloat(), cornerRadius)
            canvas.drawRoundRect(RectF(bounds),r,r,paint)
            return // 软件 Canvas 不能执行 RenderNode，不作为玻璃验收。
        }
        // 主底栏整体在 dispatchDraw 合成，不能在内容之前再盖一份背景。
        if (!standalone) return
        val pad = (40*dp).toInt()
        val layer = canvas.saveLayer(RectF(bounds).apply { inset(-pad.toFloat(), -pad.toFloat()) }, Paint().apply { this.alpha = alpha; colorFilter = filter })
        try {
            prepare(bounds)
            canvas.translate(bounds.left.toFloat()+panel(), bounds.top.toFloat())
            body(canvas,bounds.width(),bounds.height(),progress.value.coerceIn(0f,1f))
        } finally { paint.shader=null; paint.blendMode=null; canvas.restoreToCount(layer) }
    }
    /** 只录制真实页签的内容孩子，不把 TabView 的旧选中背景/水波纹当作图标。 */
    private fun captureTabs(w: Int, h: Int, pad: Int) {
        tabContent.setPosition(-pad,-pad,w+pad,h+pad)
        val tc = tabContent.beginRecording(w+2*pad,h+2*pad)
        tc.translate(pad.toFloat(),pad.toFloat())
        drawingTabs = true
        try {
            for (tab in tabs()) {
                if (tab !is ViewGroup) continue
                coordinates.reset(); tab.transformMatrixToGlobal(coordinates); host.transformMatrixToLocal(coordinates)
                val save = tc.save()
                try {
                    tc.concat(coordinates)
                    tc.translate(-tab.scrollX.toFloat(),-tab.scrollY.toFloat())
                    var opacity = 1f
                    var ancestor: View? = tab
                    while (ancestor != null && ancestor !== host) {
                        opacity *= ancestor.alpha
                        ancestor = ancestor.parent as? View
                    }
                    val layer = tc.saveLayer(null, Paint().apply { alpha = (opacity * 255).toInt().coerceIn(0,255) })
                    // 使用原生 drawChild 链：保留子控件矩阵、alpha、动画和 display list。
                    // 只跳过 TabView 自己的背景/前景，不重画或替换 QQ 内容控件。
                    try { dispatchTabs.invoke(tab,tc) } finally { tc.restoreToCount(layer) }
                } finally { tc.restoreToCount(save) }
            }
        } finally { drawingTabs = false; tabContent.endRecording() }
    }
    /** dispatchDraw 阶段：普通内容在底层，强调色副本进入组合背景，再由前景透镜取样。 */
    fun drawContent(canvas: Canvas, bounds: Rect): Boolean {
        if (standalone || drawingTabs || bounds.isEmpty || !canvas.isHardwareAccelerated || tabs().isEmpty()) return false
        sync()
        val w = bounds.width(); val h = bounds.height(); val pad = (40*dp).toInt()
        val p = progress.value.coerceIn(0f,1f)
        prepare(bounds)
        // 页签内容与合成底图同样按两帧一次的节奏重录；中间的帧只用上一份。
        val refresh = frames - lastCombinedFrame >= 2 || lastCombinedFrame < 0
        if (refresh) captureTabs(w,h,pad)
        tabSurface.setPosition(-pad,-pad,w+pad,ceil(56*dp).toInt()+pad)
        val tb = tabSurface.beginRecording(w+2*pad,ceil(56*dp+2*pad).toInt())
        try { tb.translate(pad.toFloat(),pad-4*dp); tb.drawRenderNode(scene) } finally { tabSurface.endRecording() }
        combined.setPosition(-pad,-pad,w+pad,h+pad)
        if (refresh) {
            val cc = combined.beginRecording(w+2*pad,h+2*pad)
            try {
                cc.translate(pad.toFloat(),pad.toFloat()); cc.drawRenderNode(scene)
                material(cc,tabSurface,RectF(0f,4*dp,w.toFloat(),60*dp),panel())
                tint.colorFilter = PorterDuffColorFilter(monet(night(),0xFF) ?: if(night()) 0xFF0091FF.toInt() else 0xFF0088FF.toInt(), PorterDuff.Mode.SRC_IN)
                val layer = cc.saveLayer(null,tint)
                try {
                    val factor = 1f + .2f * p
                    for (tab in tabs()) {
                        coordinates.reset(); tab.transformMatrixToGlobal(coordinates); host.transformMatrixToLocal(coordinates)
                        val rect = RectF(0f,0f,tab.width.toFloat(),tab.height.toFloat()).also(coordinates::mapRect)
                        val save = cc.save()
                        try {
                            cc.scale(factor,factor,rect.centerX(),rect.centerY())
                            cc.clipRect(rect.left,-pad.toFloat(),rect.right,h+pad.toFloat())
                            cc.drawRenderNode(tabContent)
                        } finally { cc.restoreToCount(save) }
                    }
                } finally { cc.restoreToCount(layer) }
            } finally {
                combined.endRecording()
                lastCombinedFrame = frames
            }
        }
        val layer = canvas.saveLayer(RectF(bounds).apply { inset(-pad.toFloat(),-pad.toFloat()) },null)
        try {
            canvas.translate(bounds.left.toFloat()+panel(),bounds.top.toFloat())
            // 覆盖 TabLayout.onDraw 的旧装饰，避免旧选中背景混进玻璃。
            body(canvas,w,h,p)
            // 普通行统一中性色，不能透出 QQ 的另一套选中蓝；只改变录制层，不写 QQ 控件属性。
            neutralTint.colorFilter = PorterDuffColorFilter(if (night()) 0xFFF5F6FA.toInt() else 0xFF1C1C1E.toInt(),PorterDuff.Mode.SRC_IN)
            val normalLayer = canvas.saveLayer(null,neutralTint)
            try { canvas.drawRenderNode(tabContent) } finally { canvas.restoreToCount(normalLayer) }
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
                indicator.setPosition(0,0,ceil(tw+2*pad).toInt(),ceil(56*dp+2*pad).toInt())
                val ic = indicator.beginRecording(ceil(tw+2*pad).toInt(),ceil(56*dp+2*pad).toInt())
                try { ic.translate(pad-rect.left,pad-rect.top); ic.drawRenderNode(combined) } finally { indicator.endRecording() }
                rounded(canvas,rect) {
                    drawAt(canvas, indicator, rect.left-pad, rect.top-pad)
                    // AndroidLiquidGlass 前景不再叠 40% surface，避免再次压暗强调色。
                    paint.color = Color.argb((.1f*(1-p)*255).toInt(),if(night())255 else 0,if(night())255 else 0,if(night())255 else 0)
                    canvas.drawRect(rect,paint)
                    paint.color = Color.argb((.03f*p*255).toInt(),0,0,0)
                    canvas.drawRect(rect,paint)
                    if (p > .001f) {
                        val blurRadius = 8*dp*p
                        shadow.setPosition(0,0,ceil(tw+2*pad).toInt(),ceil(56*dp+2*pad).toInt())
                        shadow.setRenderEffect(RenderEffect.createBlurEffect(blurRadius,blurRadius,Shader.TileMode.DECAL))
                        val sh = shadow.beginRecording(ceil(tw+2*pad).toInt(),ceil(56*dp+2*pad).toInt())
                        try {
                            val outline = RectF(pad.toFloat(),pad.toFloat(),tw+pad,56*dp+pad)
                            // 模糊外侧反向遮罩，再由前景胶囊裁切，四周均有内阴影。
                            paint.color = Color.argb((.15f*p*255).toInt(),0,0,0)
                            sh.drawRect(0f,0f,tw+2*pad,56*dp+2*pad,paint)
                            paint.blendMode = BlendMode.CLEAR
                            sh.drawRoundRect(outline,28*dp,28*dp,paint)
                            paint.blendMode = null
                        } finally { shadow.endRecording() }
                        drawAt(canvas, shadow, rect.left-pad, rect.top-pad)
                    }
                }
            } finally { canvas.restoreToCount(save) }

        } finally { paint.shader=null; paint.blendMode=null; canvas.restoreToCount(layer) }
        return true
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

// KernelSU InteractiveHighlight.kt 原 smoothstep 高光，不能以线性 RadialGradient 替代。
private const val INTERACTIVE_HIGHLIGHT_SHADER = """
    uniform float2 size;
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;

    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.5, dist);
        return color * intensity;
    }"""

