// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 高光预设。底栏与亮胶囊用 KSU FloatingBottomBar 的 specular（BloomStroke 双峰、主光跟重力转），
 * 其余卡片按 miuix 的 GlassStroke 三级，取哪级看尺寸：大卡片 Big、输入栏 Middle、圆钮 Small。
 */
enum class GlassHighlight { Bar, Big, Middle, Small }

/**
 * 液态玻璃，完全照 KernelSU 管理器的配方（manager 侧 FloatingBottomBar.kt，liquid 的 Lens / InnerShadow /
 * CombinedBackdrop，miuix-blur 的 Highlight 与 DampedDragAnimation；均 Apache 2.0，配方源自 Kyant0/AndroidLiquidGlass）：
 *
 * - 底栏：背板取样 → 提饱和 1.5 → 模糊 4dp（miuix：sigma = 半径 × 0.45）→ 边缘 24dp 透镜
 *   → surfaceContainer 40% → 内容 → 1dp BloomStroke 高光（双峰、主光跟重力再转 -45°、alpha 0.75）
 * - 按压：KSU 的「充气」——进度 ζ=1/k=1000，整条底栏 1 + 16dp/宽度，亮胶囊 78/56（X ζ=0.6/250、Y ζ=0.7/250），
 *   指下再叠一层自己的进度（ζ=0.5/k=300：整块白 6% + 触点径向辉光 12%，画在内容之下）
 * - 亮胶囊：正好盖住一个页签槽；背板 = 应用原图 + 「隐页签行」那一层（vibrancy → blur → 24dp 透镜 + 40% 表面
 *   + 页签 1 + 0.2·进度 倍），自己再压色散透镜（10dp·进度 / 14dp·进度、depthEffect、色散 0.5），
 *   表面叠黑 10%·(1-进度) 与黑 3%·进度，内阴影 8dp·进度（黑 15%）；换页签时按 ζ=1/k=1000 弹过去，
 *   越界 2.5% 才算到位，越界期间保持按压
 * - 页签之上那一层（BloomStroke 高光 + 亮胶囊）在 [overlay] 里，由底栏的 foreground 画，叠放顺序和 KSU 一致
 *
 * 唯一的替代：容器色取 miuix 的 surfaceContainer（亮 0xFFFFFFFF、暗 0xFF242424）× 40%，再按 QQ 的取色
 * 混一点进主题色；QQ 里没有 miuix 的整套调色板。
 */
class Glass(private val host: View, private val radius: Float = Float.MAX_VALUE,
            private val selected: (() -> View?)? = null,
            private val preset: GlassHighlight = GlassHighlight.Middle) : Drawable() {

    private val isBar = selected != null
    private val look = if (isBar) GlassHighlight.Bar else preset // 底栏固定用 KSU 的 iosIndicatorSpecular
    private val dp = host.dp
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val plusShader = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.PLUS }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.CLEAR }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tone = Paint(Paint.ANTI_ALIAS_FLAG) // 隐页签行里的页签按 KSU 上主题色
    private val toneRect = RectF()
    private val clip = Path() // 宿主形状
    private val pillClip = Path() // 亮胶囊形状
    private val rect = RectF()
    private val tabRect = RectF()
    private val pill = RectF()
    private val node = RenderNode("qself-glass") // 底栏自己的折射背板
    private val raw = RenderNode("qself-raw") // 胶囊脚下的应用原图
    private val back = RenderNode("qself-back") // 胶囊里那层「模糊背板」
    private val tabs = RenderNode("qself-tabs") // 隐页签行：模糊背板 + 表面 + 页签
    private val face = RenderNode("qself-face") // 胶囊本体：原图 + 隐页签行，过色散透镜
    private val inner = RenderNode("qself-inner") // 内阴影
    private val lens by lazy { RuntimeShader(LENS) }
    private val pillLens by lazy { RuntimeShader(LENS) } // 单独一份：两处的 uniform 不同
    private val dispersion by lazy { RuntimeShader(LENS_DISPERSION) }
    private val bloomDual by lazy { RuntimeShader(bloomShader(dual = true)) }
    private val bloomSingle by lazy { RuntimeShader(bloomShader(dual = false)) }
    private val glow by lazy { RuntimeShader(GLOW) }
    private val vibrancy by lazy {
        RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) }))
    }
    private val smear by lazy {
        RenderEffect.createBlurEffect(4 * dp * BLUR_RADIUS_TO_SIGMA, 4 * dp * BLUR_RADIUS_TO_SIGMA, Shader.TileMode.CLAMP)
    }
    private val barEffect by lazy {
        RenderEffect.createChainEffect(RenderEffect.createChainEffect(vibrancy, smear),
            RenderEffect.createRuntimeShaderEffect(lens, "content"))
    }
    private val backEffect by lazy {
        RenderEffect.createChainEffect(RenderEffect.createChainEffect(vibrancy, smear),
            RenderEffect.createRuntimeShaderEffect(pillLens, "content"))
    }
    private val dispersionEffect by lazy { RenderEffect.createRuntimeShaderEffect(dispersion, "content") }
    private val followers = ArrayList<Drawable>(1) // foreground 那层要跟着一起重画
    private val pad = (40 * dp).toInt() // KSU：padding = max(padding, 40dp)
    private val here = IntArray(2)
    private val there = IntArray(2)
    private var failures = 0
    private var sampled = false
    private var missing = false
    private var busy = false
    private var finger = false // 手指按着
    private var hold = false // 换页签时保持按压（KSU 的 release 要等 value 到位）
    private var down = false // 当前生效的按压态
    private var progress = 0f // 底栏按压进度（ζ=1、k=1000）
    private var touch = 0f // InteractiveHighlight 自己的进度（ζ=0.5、k=300）
    private var gripX = 1f // 亮胶囊的弹簧缩放（KSU 78/56）
    private var gripY = 1f
    private var anchor = Float.NaN // 亮胶囊中心（换页签时从这里弹走）
    private var shift = 0f // 弹过去的位移
    private var slideFrom = 0f
    private var travel = 1f // 首尾页签中心的距离：速度按它归一化
    private var speed = 0f // KSU velocityAnimation 的当前值
    private var speedFrom = 0f
    private var speedAt = 0L
    private var lastAt = 0L
    private var lastX = 0f
    private var current: View? = null // 上一个选中的页签
    private val progressAnim = ValueAnimator.ofFloat(0f, 1f)
    private val touchAnim = ValueAnimator.ofFloat(0f, 1f)
    private val gripXAnim = ValueAnimator.ofFloat(0f, 1f)
    private val gripYAnim = ValueAnimator.ofFloat(0f, 1f)
    private val slide = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = (SLIDE.settle * 1000).toLong().coerceAtLeast(1)
        addUpdateListener {
            shift = slideFrom * (1f - SLIDE.response(it.animatedFraction * SLIDE.settle))
            sample()
            if (hold && abs(shift) < travel * RELEASE_FRACTION) { hold = false; pressTarget() }
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                shift = 0f
                lastAt = 0L
                if (hold) { hold = false; pressTarget() }
                invalidate()
            }
        })
    }
    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply { // 速度按 ζ=0.5/k=300 解析衰减
        duration = 16
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { tick(it) }
    }

    /** 按压进度（0→1）每帧喂出去：KSU 里整条底栏和页签都用同一个进度。 */
    var onPress: ((Float) -> Unit)? = null

    init {
        if (isBar) {
            Tilt.watch(this)
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Tilt.acquire(v.context)
                override fun onViewDetachedFromWindow(v: View) {
                    Tilt.release()
                    progress = 0f
                    touch = 0f
                    shift = 0f
                    host.scaleX = 1f
                    host.scaleY = 1f
                }
            })
            if (host.isAttachedToWindow) Tilt.acquire(host.context)
        }
    }

    /** 页签之上那一层（BloomStroke 高光 + 亮胶囊）。给底栏的 foreground 用。 */
    fun overlay(): Drawable = object : Drawable() {
        override fun draw(canvas: Canvas) = front(canvas)
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }.also { followers += it }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val night = night()
        rect.set(b)
        if (!isBar) rect.inset(progress * 6 * dp, progress * 4 * dp) // 圆钮/输入栏没有外层缩放，保留缩进形变
        val r = min(radius, rect.height() / 2f)
        shape(r)
        // 1. 折射背板（KSU：vibrancy → blur(4dp) → lens(24dp, 24dp)）
        val refracted = canvas.isHardwareAccelerated && failures < 3 && !busy &&
            runCatching { backdrop(canvas, r) }
                .onFailure { if (++failures <= 2) log("玻璃取样失败 ${host.javaClass.simpleName}，改用不透明材质", it) }
                .getOrDefault(false)
        // 2. 表面：surfaceContainer 40%（KSU 的 containerColor）
        fill.style = Paint.Style.FILL
        fill.shader = null
        val surface = wash(if (night) 0xFF242424.toInt() else 0xFFFFFFFF.toInt(), monet(night, 0xFF))
        fill.color = if (refracted) (surface and 0xFFFFFF) or 0x66000000 else (surface and 0xFFFFFF) or 0xE6000000.toInt()
        canvas.drawPath(clip, fill)
        // 3. 按压辉光（KSU InteractiveHighlight：整块白 6% + 触点的径向辉光），压在内容之下
        if (isBar && touch > 0.004f) interactive(canvas)
    }

    /** 前景层：KSU 把 BloomStroke 画在内容之上，亮胶囊又画在最后。 */
    private fun front(canvas: Canvas) {
        val b = bounds
        if (!isBar || b.isEmpty) return
        rect.set(b)
        val r = min(radius, rect.height() / 2f)
        shape(r)
        // KSU：底栏高光 alpha 0.75，主光在重力方向再转 -45°
        bloom(canvas, rect.left, rect.top, rect.width(), rect.height(), r, 0.75f, Tilt.angle - PI.toFloat() / 4f)
        pillFace(canvas, night())
    }

    // ------------------------------------------------------------ 背景折射（Lens.kt）

    /** KSU drawBackdrop 的那条链：背板 → vibrancy → blur → lens(=content) → 画回画布。 */
    private fun backdrop(canvas: Canvas, r: Float): Boolean {
        if (!host.isAttachedToWindow) return false
        val b = bounds
        val w = b.width() + 2 * pad
        val h = b.height() + 2 * pad
        busy = true
        try {
            lensUniforms(lens, w.toFloat(), h.toFloat(), r, 24 * dp, -24 * dp, 0f)
            node.setRenderEffect(barEffect)
            node.setPosition(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
            val rc = node.beginRecording(w, h)
            val count = try {
                rc.translate((pad - b.left).toFloat(), (pad - b.top).toFloat())
                behind(rc)
            } finally { node.endRecording() }
            if (count == 0) {
                if (!missing) { log("玻璃无可见底图: ${host.javaClass.simpleName}，改用不透明材质"); missing = true }
                return false
            }
            if (!sampled) { log("玻璃折射取样: ${host.javaClass.simpleName}, 图层 $count"); sampled = true }
            canvas.drawRenderNode(node)
            return true
        } finally { busy = false }
    }

    /** 只录背景和画在宿主之前的可见内容；QQ 的模糊层和另一块玻璃都不是原始底图。 */
    private fun behind(rc: Canvas): Int {
        host.getLocationInWindow(here)
        val levels = ArrayList<Pair<ViewGroup, View>>(6)
        var child: View = host
        while (true) {
            val p = child.parent as? ViewGroup ?: break
            levels += p to child
            child = p
        }
        var count = 0
        for ((p, cut) in levels.asReversed()) {
            p.background?.let { bg ->
                if (p.tag != "qself-sheet" && bg !is Glass && !bg.javaClass.name.contains("blur", true)) {
                    paint(rc, p) { bg.draw(it) }
                    count++
                }
            }
            for (i in 0 until p.indexOfChild(cut)) {
                val s = p.getChildAt(i)
                if (s.visibility != View.VISIBLE || s.width == 0 || s.height == 0 || !overlaps(s) || unsafe(s)) continue
                paint(rc, s) { s.draw(it) }
                count++
            }
        }
        return count
    }

    // 模糊或另一块玻璃藏在容器里面时也不把整个容器当作原图重放。
    private fun unsafe(v: View): Boolean {
        if (v.background is Glass || v.javaClass.name.contains("blur", true) ||
            v.background?.javaClass?.name?.contains("blur", true) == true) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            val child = v.getChildAt(i)
            if (child.visibility == View.VISIBLE && overlaps(child) && unsafe(child)) return true
        }
        return false
    }

    private inline fun paint(rc: Canvas, v: View, crop: Boolean = true, body: (Canvas) -> Unit) {
        v.getLocationInWindow(there)
        val save = rc.save()
        rc.translate((there[0] - here[0]).toFloat(), (there[1] - here[1]).toFloat())
        if (crop) rc.clipRect(0, 0, v.width, v.height)
        body(rc)
        rc.restoreToCount(save)
    }

    private fun overlaps(v: View): Boolean {
        v.getLocationInWindow(there)
        return there[1] < here[1] + host.height + pad && there[1] + v.height > here[1] - pad &&
            there[0] < here[0] + host.width + pad && there[0] + v.width > here[0] - pad
    }

    // ------------------------------------------------------------ 高光（HighlightStyle.kt）

    /** 1dp BloomStroke：着色器铺满整块形状，Plus 叠上去（KSU 就是这么合的）。 */
    private fun bloom(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, r: Float,
                      alpha: Float, angle: Float) {
        if (alpha <= 0f || w <= 0f || h <= 0f) return
        val style = style(night(), angle)
        val shader = if (style.dual) bloomDual else bloomSingle
        val innerBlur = style.innerBlur
        runCatching {
            shader.setFloatUniform("halfView", w / 2f, h / 2f)
            shader.setFloatUniform("halfViewFloor", floor(w / 2f), floor(h / 2f))
            shader.setFloatUniform("cornerRadii", r, r, r, r)
            shader.setFloatUniform("strokeWidth", min(dp, min(w, h) / 2f))
            shader.setFloatUniform("innerBlurRadius", innerBlur)
            shader.setFloatUniform("innerBlurRadiusSq", innerBlur * innerBlur)
            shader.setFloatUniform("highlightAlpha", alpha)
            shader.setColorUniform("strokeColor", style.color or 0xFF000000.toInt()) // color.copy(alpha = 1f)
            shader.setFloatUniform("strokeAlphaMul", Color.alpha(style.color) / 255f)
            light(shader, "1", style.primary, !style.dual)
            light(shader, "2", style.secondary, !style.dual)
        }.onFailure { if (failures < 3) log("玻璃高光装不上，跳过", it); return }
        val save = canvas.save()
        canvas.translate(left, top)
        plusShader.shader = shader
        canvas.drawRect(0f, 0f, w, h, plusShader)
        plusShader.shader = null
        canvas.restoreToCount(save)
    }

    /** miuix applyLightUniforms：方向 = normalize((x - 0.5, y - 0.7, z))。 */
    private fun light(s: RuntimeShader, suffix: String, l: Light, axis: Boolean) {
        val dx = l.x - LIGHT_REF_X
        val dy = l.y - LIGHT_REF_Y
        val dz = l.z
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
        val nx = dx / len
        val ny = dy / len
        s.setFloatUniform("lightDir$suffix", nx, ny, dz / len)
        s.setColorUniform("lightColor$suffix", 0xFFFFFFFF.toInt())
        s.setFloatUniform("lightIntensity$suffix", l.intensity)
        if (axis) {
            val xy = sqrt(nx * nx + ny * ny)
            if (xy > 1e-3f) s.setFloatUniform("axis$suffix", nx / xy, ny / xy)
            else s.setFloatUniform("axis$suffix", 0f, if (suffix == "1") -1f else 1f)
        }
    }

    private fun style(night: Boolean, angle: Float): Bloom = when (look) {
        // FloatingBottomBar 的 iosIndicatorSpecular：白 12%、内晕 2dp、主光 1.0 跟重力、副光 0.4、双峰
        GlassHighlight.Bar -> Bloom(0x1FFFFFFF, 2 * dp,
            Light(LIGHT_REF_X + cos(angle), LIGHT_REF_Y + sin(angle), -0.05f, 1f),
            Light(0.5f, 0.8f, -0.5f, 0.4f), true)
        GlassHighlight.Big -> if (night) Bloom(0x0DFFFFFF, 1.7f * dp, Light(0.5f, 0.5f, -0.5f, 0.4f), Light(0.5f, 0.6f, -0.5f, 0.25f), false)
            else Bloom(0x0DFFFFFF, 3.5f * dp, Light(0.5f, 0.5f, -0.5f, 0.3f), Light(0.5f, 0.6f, -0.5f, 0.2f), false)
        GlassHighlight.Middle -> if (night) Bloom(0x0FFFFFFF, 2.0f * dp, Light(0.5f, 0.5f, -0.5f, 0.5f), Light(0.5f, 0.8f, -0.5f, 0.25f), false)
            else Bloom(0x0DFFFFFF, 2.8f * dp, Light(0.5f, 0.5f, -0.5f, 0.4f), Light(0.5f, 0.8f, -0.5f, 0.25f), false)
        GlassHighlight.Small -> if (night) Bloom(0x14FFFFFF, 2.3f * dp, Light(0.5f, 0.5f, -0.5f, 0.6f), Light(0.5f, 0.95f, -0.36f, 0.25f), false)
            else Bloom(0x0DFFFFFF, 2.6f * dp, Light(0.5f, 0.5f, -0.5f, 0.6f), Light(0.5f, 0.95f, -0.5f, 0.35f), false)
    }

    // ------------------------------------------------------------ 选中亮胶囊

    /** KSU 的亮胶囊：正好盖住一个页签槽，缩放/速度拉伸/色散/内阴影全在它身上。 */
    private fun pillFace(canvas: Canvas, night: Boolean) {
        if (selected?.invoke() == null) return
        if (pill.width() <= 1f || pill.height() <= 1f) return
        val w = pill.width()
        val h = pill.height()
        val r = min(w, h) / 2f
        pillPath(r)
        val outer = canvas.save()
        if (shift != 0f) canvas.translate(shift, 0f)
        // KSU layerBlock：弹簧缩放，再按滑动速度拉一点
        val v = speed / 10f
        val sx = gripX / (1f - (v * 0.75f).coerceIn(-0.2f, 0.2f))
        val sy = gripY * (1f - (v * 0.25f).coerceIn(-0.2f, 0.2f))
        canvas.save()
        canvas.clipPath(pillClip)
        canvas.scale(sx, sy, pill.centerX(), pill.centerY())
        runCatching { pillBackdrop(canvas, night) }
            .onFailure { if (failures < 3) log("亮胶囊折射失败，跳过", it) }
        canvas.save()
        canvas.translate(pill.left, pill.top)
        // KSU onDrawSurface：浅色黑 10%（按压时退掉）、深色白 10%，再叠黑 3% × 进度
        tint.style = Paint.Style.FILL
        tint.shader = null
        tint.color = if (night) Color.argb(((1f - progress) * 25.5f).toInt(), 255, 255, 255)
        else Color.argb(((1f - progress) * 25.5f).toInt(), 0, 0, 0)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, tint)
        tint.color = Color.argb((progress * 7.65f).toInt(), 0, 0, 0)
        canvas.drawRoundRect(0f, 0f, w, h, r, r, tint)
        // KSU 亮胶囊高光：同一套 specular，主光再转 +90°，alpha = 进度
        bloom(canvas, 0f, 0f, w, h, r, progress, Tilt.angle + PI.toFloat() / 2f)
        canvas.restore()
        canvas.restore()
        // KSU InnerShadow：radius 8dp × 进度、黑 15% × 进度，裁在形状里、不跟着缩放
        if (progress > 0.004f) runCatching { innerShadow(canvas, w, h, r) }
            .onFailure { if (failures < 3) log("亮胶囊内阴影失败，跳过", it) }
        canvas.restoreToCount(outer)
    }

    /**
     * 胶囊的背板：KSU 的 combinedBackdrop = 应用原图 + 隐页签行。
     * 隐页签行自己是一条 vibrancy → blur(4dp) → lens(24dp, 24dp) 的链，表面压 40% 容器色，
     * 页签按 1 + 0.2·进度 倍画进去；最后胶囊本体再过色散透镜（10dp·进度 / 14dp·进度）。
     */
    private fun pillBackdrop(canvas: Canvas, night: Boolean) {
        val w = pill.width()
        val h = pill.height()
        val r = min(w, h) / 2f
        val pw = (w + 2 * pad).toInt()
        val ph = (h + 2 * pad).toInt()
        val left = (pill.left - pad).toInt()
        val top = (pill.top - pad).toInt()
        val offX = pad - pill.left
        val offY = pad - pill.top
        val at = { n: RenderNode -> n.setPosition(left, top, left + pw, top + ph) }
        // 1. 应用原图（combinedBackdrop 的第一项）
        at(raw)
        val rrc = raw.beginRecording(pw, ph)
        rrc.translate(offX, offY)
        behind(rrc)
        raw.endRecording()
        // 2. 隐页签行的背板：模糊 + 透镜（KSU 的第二项，effects 只作用在背板上）
        at(back)
        lensUniforms(pillLens, pw.toFloat(), ph.toFloat(), r, 24 * dp, -24 * dp, 0f)
        back.setRenderEffect(backEffect)
        val brc = back.beginRecording(pw, ph)
        brc.translate(offX, offY)
        brc.drawRenderNode(raw)
        back.endRecording()
        // 3. 隐页签行：背板 + 40% 表面 + 页签（1 + 0.2·进度 倍）
        at(tabs)
        tabs.setRenderEffect(null)
        val trc = tabs.beginRecording(pw, ph)
        trc.translate(offX, offY)
        trc.drawRenderNode(back)
        tint.style = Paint.Style.FILL
        tint.shader = null
        tint.color = (if (night) 0x242424 else 0xFFFFFF) or 0x66000000
        trc.drawRoundRect(pill.left, pill.top, pill.right, pill.bottom, r, r, tint)
        val strip = selected?.invoke()?.parent as? ViewGroup
        if (strip != null) {
            val s = 1f + 0.2f * progress
            // KSU 的隐页签行：页签用主题色（LocalContentColor provides accentColor）
            val accent = monet(night, 0xFF)
            tone.colorFilter = accent?.let { PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
            for (i in 0 until strip.childCount) {
                val tab = strip.getChildAt(i)
                if (tab.visibility != View.VISIBLE || tab.width == 0 || tab.height == 0) continue
                tab.getLocationInWindow(there)
                val x = there[0] - here[0] + offX
                val y = there[1] - here[1] + offY
                val save = trc.save()
                // 画布已经平移过 (offX, offY)，缩放支点也要跟着挪
                trc.scale(s, s, x + tab.width / 2f, y + tab.height / 2f)
                // 放大 25% 的层，缩放后的内容不会被裁掉
                toneRect.set(x - tab.width * 0.25f, y - tab.height * 0.25f,
                    x + tab.width * 1.25f, y + tab.height * 1.25f)
                val layer = if (accent != null) trc.saveLayer(toneRect, tone) else -1
                paint(trc, tab, crop = false) { tab.draw(it) } // KSU 的 graphicsLayer 不裁剪
                if (layer >= 0) trc.restoreToCount(layer)
                trc.restoreToCount(save)
            }
        }
        tabs.endRecording()
        // 4. 胶囊本体：原图 + 隐页签行，过色散透镜
        at(face)
        // KSU lens()：进度 0 时干脆不加透镜（refractionHeight <= 0 直接 return）
        if (progress > 0.004f) {
            lensUniforms(dispersion, pw.toFloat(), ph.toFloat(), r, 10 * dp * progress, -(14 * dp * progress), 1f)
            dispersion.setFloatUniform("chromaticAberration", 0.5f)
            face.setRenderEffect(dispersionEffect)
        } else face.setRenderEffect(null)
        val rc = face.beginRecording(pw, ph)
        rc.translate(offX, offY)
        rc.drawRenderNode(raw)
        rc.drawRenderNode(tabs)
        face.endRecording()
        canvas.drawRenderNode(face)
    }

    /** KSU InnerShadow：形状填色后拿偏移 (0, +radius) 的自己抠掉，再模糊，最后裁在形状里。 */
    private fun innerShadow(canvas: Canvas, w: Float, h: Float, r: Float) {
        val radius = 8 * dp * progress
        if (radius <= 0.5f) return
        val pw = (w + 2 * pad).toInt()
        val ph = (h + 2 * pad).toInt()
        inner.setPosition((pill.left - pad).toInt(), (pill.top - pad).toInt(),
            (pill.right + pad).toInt(), (pill.bottom + pad).toInt())
        val rc = inner.beginRecording(pw, ph)
        rc.translate(pad - pill.left, pad - pill.top)
        tint.style = Paint.Style.FILL
        tint.color = Color.argb((progress * 38.25f).toInt(), 0, 0, 0) // 黑 15% × 进度
        rc.drawRoundRect(pill.left, pill.top, pill.right, pill.bottom, r, r, tint)
        rc.translate(0f, radius) // KSU 默认 offset = (0, radius)
        rc.drawRoundRect(pill.left, pill.top, pill.right, pill.bottom, r, r, clear)
        inner.endRecording()
        inner.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL))
        val save = canvas.save()
        canvas.clipPath(pillClip)
        canvas.drawRenderNode(inner)
        canvas.restoreToCount(save)
    }

    /** InteractiveHighlight.kt：整块白 6%·进度，再在触点画一片 12%·进度 的辉光。 */
    private fun interactive(canvas: Canvas) {
        val t = selected?.invoke()?.let { tabRect(it) } ?: return
        val save = canvas.save()
        canvas.clipPath(clip)
        plus.color = Color.argb((touch * 15f).roundToInt(), 255, 255, 255)
        plus.shader = null
        canvas.drawRect(rect, plus)
        runCatching {
            glow.setFloatUniform("size", rect.width(), rect.height())
            glow.setColorUniform("color", Color.argb((touch * 31f).roundToInt(), 255, 255, 255))
            glow.setFloatUniform("radius", min(rect.width(), rect.height()) * 1.2f)
            glow.setFloatUniform("position", t.centerX(), rect.centerY())
        }.onFailure { return }
        plusShader.shader = glow
        canvas.drawRect(rect, plusShader)
        plusShader.shader = null
        canvas.restoreToCount(save)
    }

    // ------------------------------------------------------------ 按压与滑动（DampedDragAnimation.kt）

    /** 手指按下/抬起（Looks.kt 的触摸钩子）。辉光跟手，底栏进度还要看换页签的保持。 */
    fun press(value: Boolean) {
        if (value != finger) {
            finger = value
            spring(touchAnim, touch, if (value) 1f else 0f, TOUCH) { touch = it }
        }
        pressTarget()
    }

    /** 换页签或布局变了：选中目标可能变化而底栏自身未失效，所以每帧来对一次。 */
    fun sync() {
        if (!isBar) { invalidate(); return }
        val tab = selected?.invoke()
        val r = tab?.let { tabRect(it) }
        if (r != null && r.width() > 1f && r.height() > 1f) {
            pill.set(r) // 亮胶囊正好盖住一个页签槽（KSU：胶囊外框 = 页签槽）
            val center = r.centerX()
            if (tab !== current && !anchor.isNaN() && abs(center - anchor) > 0.5f) {
                // KSU animateToValue：先按压，再让胶囊按 ζ=1/k=1000 弹过去，越界 2.5% 才算到位
                travel = pillTravel()
                slideFrom = shift + (anchor - center)
                hold = true
                pressTarget()
                lastAt = 0L
                slide.cancel()
                slide.start()
            }
            current = tab
            anchor = center
        }
        invalidate()
    }

    /** ζ=1/k=1000，X ζ=0.6/250，Y ζ=0.7/250 —— 全按 KSU 的 spring 参数。 */
    private fun spring(anim: ValueAnimator, from: Float, to: Float, spec: SpringSpec, apply: (Float) -> Unit) {
        anim.removeAllUpdateListeners()
        anim.removeAllListeners()
        anim.cancel()
        anim.duration = (spec.settle * 1000).toLong().coerceAtLeast(1)
        anim.addUpdateListener {
            apply(from + (to - from) * spec.response(it.animatedFraction * spec.settle))
            frame()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                apply(to)
                frame()
            }
        })
        anim.start()
    }

    /** 手指按着或胶囊还在滑动都算按压（KSU 的 release 要等 value 到位）。 */
    private fun pressTarget() {
        val want = finger || hold
        if (want == down) return
        down = want
        spring(progressAnim, progress, if (want) 1f else 0f, PRESS) { progress = it }
        if (isBar) {
            spring(gripXAnim, gripX, if (want) PRESSED else 1f, SCALE_X) { gripX = it }
            spring(gripYAnim, gripY, if (want) PRESSED else 1f, SCALE_Y) { gripY = it }
        }
    }

    /** KSU：整条底栏 1 + 16dp/宽度 × 进度。 */
    private fun frame() {
        if (isBar) {
            val s = 1f + 16 * dp / host.width.coerceAtLeast(1) * progress
            host.scaleX = s
            host.scaleY = s
        }
        onPress?.invoke(progress)
        invalidate()
    }

    /** KSU 的 VelocityTracker：滑动时按帧取速度，再按首尾距离归一化；之后 ζ=0.5/k=300 衰减到零。 */
    private fun sample() {
        val now = SystemClock.uptimeMillis()
        val x = anchor + shift
        if (lastAt != 0L) {
            val dt = (now - lastAt).coerceAtLeast(1) / 1000f
            val v = abs(x - lastX) / dt / travel.coerceAtLeast(1f)
            speedFrom = if (speedFrom == 0f) v else speedFrom + (v - speedFrom) * 0.5f
            speedAt = now
            if (!ticker.isRunning) ticker.start()
        }
        lastAt = now
        lastX = x
    }

    private fun tick(anim: Animator) {
        val t = (SystemClock.uptimeMillis() - speedAt) / 1000f
        if (speedFrom == 0f || t >= VELOCITY.settle) {
            speed = 0f
            speedFrom = 0f
            if (!host.post { runCatching { ticker.cancel() } }) runCatching { ticker.cancel() }
        } else {
            speed = speedFrom * (1f - VELOCITY.response(t))
        }
        invalidate()
    }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        if (!isBar) press(state.contains(android.R.attr.state_pressed))
        return true
    }

    internal fun invalidate() {
        invalidateSelf()
        followers.forEach { it.invalidateSelf() }
    }

    /** 页签在宿主本地坐标里的矩形（不受宿主缩放影响，胶囊定位用它）。 */
    private fun tabRect(tab: View): RectF? {
        if (tab.width == 0 || tab.height == 0) return null
        var x = 0f
        var y = 0f
        var c: View? = tab
        while (c != null && c !== host) {
            val p = c.parent as? View
            x += c.left + c.translationX - (p?.scrollX ?: 0)
            y += c.top + c.translationY - (p?.scrollY ?: 0)
            c = p
        }
        tabRect.set(x, y, x + tab.width, y + tab.height)
        return tabRect
    }

    /** 首尾页签中心的距离：速度按它归一化（KSU 的 valueRange span）。 */
    private fun pillTravel(): Float {
        val strip = selected?.invoke()?.parent as? ViewGroup
            ?: return host.width.coerceAtLeast(1).toFloat()
        var first = Float.NaN
        var last = Float.NaN
        for (i in 0 until strip.childCount) {
            val v = strip.getChildAt(i)
            if (v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) continue
            val r = tabRect(v) ?: continue
            if (first.isNaN()) first = r.centerX()
            last = r.centerX()
        }
        return if (first.isNaN() || last.isNaN() || last - first < 1f) host.width.coerceAtLeast(1).toFloat()
        else last - first
    }

    private fun lensUniforms(s: RuntimeShader, w: Float, h: Float, r: Float,
                             height: Float, amount: Float, depth: Float) {
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())
        s.setFloatUniform("cornerRadii", r, r, r, r)
        s.setFloatUniform("refractionHeight", height)
        s.setFloatUniform("refractionAmount", amount)
        s.setFloatUniform("depthEffect", depth)
    }

    private fun shape(r: Float) {
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
    }

    private fun pillPath(r: Float) {
        pillClip.reset()
        pillClip.addRoundRect(pill, r, r, Path.Direction.CW)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// ---------------------------------------------------------------- 光照与高光模型

private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f
private const val BLUR_RADIUS_TO_SIGMA = 0.45f // miuix BlurEffect：sigma = 半径 × 0.45
private const val RELEASE_FRACTION = 0.025f // KSU release()：value 到 2.5% 才算到位

private class Light(val x: Float, val y: Float, val z: Float, val intensity: Float)

private class Bloom(val color: Int, val innerBlur: Float, val primary: Light, val secondary: Light, val dual: Boolean)

/** Compose spring 的解析解（质量 = 1，ω = √stiffness）。 */
private class SpringSpec(private val zeta: Float, stiffness: Float) {
    private val w = sqrt(stiffness)

    fun response(t: Float): Float = if (zeta >= 1f) {
        1f - (1f + w * t) * exp(-w * t)
    } else {
        val wd = w * sqrt(1f - zeta * zeta)
        1f - exp(-zeta * w * t) * (cos(wd * t) + zeta * w / wd * sin(wd * t))
    }

    val settle: Float get() = if (zeta >= 1f) 6.5f / w else 5f / (zeta * w)
}

private val PRESS = SpringSpec(1f, 1000f) // KSU pressProgressAnimationSpec
private val SCALE_X = SpringSpec(0.6f, 250f)
private val SCALE_Y = SpringSpec(0.7f, 250f)
private val SLIDE = SpringSpec(1f, 1000f) // KSU valueAnimationSpec
private val VELOCITY = SpringSpec(0.5f, 300f) // KSU velocityAnimationSpec
private val TOUCH = SpringSpec(0.5f, 300f) // KSU InteractiveHighlight 自己的进度
private const val PRESSED = 78f / 56f // KSU pressedScale

/**
 * 设备重力方向（KSU 的高光跟着设备转）。miuix rememberDeviceTilt 的等价物：
 * TYPE_GAME_ROTATION_VECTOR（没有就 TYPE_ROTATION_VECTOR）、SENSOR_DELAY_GAME、指数平滑 0.15；
 * KSU 再把角度量化到 3°，|g| ≤ 0.1（约 6°）就固定 -π/2（光从上方来）。
 */
private object Tilt : SensorEventListener {
    private val watchers: MutableSet<Glass> = Collections.newSetFromMap(WeakHashMap())
    private var manager: SensorManager? = null
    private var users = 0
    private var ready = false
    private var gx = 0f
    private var gy = 0f
    private val matrix = FloatArray(9)

    var angle = -PI.toFloat() / 2f
        private set

    fun watch(glass: Glass) {
        watchers += glass
    }

    fun acquire(context: Context) {
        users++
        if (manager != null) return
        runCatching {
            val m = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val sensor = m.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
            m.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            manager = m
        }.onFailure { log("取不到重力传感器，高光固定从上方来", it) }
    }

    fun release() {
        users--
        if (users > 0) return
        runCatching { manager?.unregisterListener(this) }
        manager = null
        ready = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        runCatching {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            val x = -matrix[6] // 设备坐标系里的重力（miuix：gravity = -(R[2][0], R[2][1])）
            val y = -matrix[7]
            if (!ready) { gx = x; gy = y; ready = true } else { gx += (x - gx) * 0.15f; gy += (y - gy) * 0.15f }
            val next = if (gx * gx + gy * gy > 0.01f) (atan2(gy, gx) / STEP).roundToInt() * STEP
            else -PI.toFloat() / 2f
            if (next == angle) return
            angle = next
            watchers.forEach { it.invalidate() }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private const val STEP = (3.0 * PI / 180.0).toFloat()
}

/** 圆角上限为 [max] 的胶囊轮廓（不传就是全圆），配合 clipToOutline 用。 */
fun capsule(max: Float = Float.MAX_VALUE) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, o: Outline) = o.setRoundRect(0, 0, view.width, view.height, min(max, view.height / 2f))
}

private val isNight by lazy { runCatching { cls("com.tencent.mobileqq.utils.QQTheme").getMethod("isNowThemeIsNight") }.getOrNull() }

/** Monet 混进中性色，保留中性色的透明度。 */
private fun wash(c: Int, accent: Int?): Int {
    if (accent == null) return c
    fun mix(a: Int, b: Int) = (a + ((b - a) * 0.22f).toInt()).coerceIn(0, 255)
    return (c and 0xFF000000.toInt()) or (mix(Color.red(c), Color.red(accent)) shl 16) or
        (mix(Color.green(c), Color.green(accent)) shl 8) or mix(Color.blue(c), Color.blue(accent))
}

fun monet(night: Boolean, alpha: Int): Int? =
    if (!on("Monet取色")) null else runCatching {
        val id = if (night) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
        Resources.getSystem().getColor(id, null) and 0x00FFFFFF or (alpha shl 24)
    }.getOrNull()

fun night(): Boolean = runCatching { isNight?.invoke(null) as? Boolean }.getOrNull()
    ?: (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)

// ---------------------------------------------------------------- AGSL 着色器

/** Lens.kt 的圆角矩形 SDF（四角半径版）。 */
private const val SDF = """
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

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}
"""

private const val LENS_HEAD = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
"""

/** Lens.kt 的 ROUNDED_RECT_REFRACTION_SHADER；形状外那几个像素给透明，免得 sqrt 出 NaN。 */
private const val LENS = LENS_HEAD + SDF + """
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (sd > 0.0) return half4(0.0);
    if (-sd >= refractionHeight) return content.eval(coord);
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
"""

/** Lens.kt 的色散版（亮胶囊按压时用）：七段采样错开，chromaticAberration 控散开量。 */
private const val LENS_DISPERSION = LENS_HEAD + """
uniform float chromaticAberration;
""" + SDF + """
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (sd > 0.0) return half4(0.0);
    if (-sd >= refractionHeight) return content.eval(coord);
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

/** miuix buildBloomStrokeShader：圆角 SDF 外圈 + 半球法线 + 方向光，Plus 叠上去。 */
private fun bloomShader(dual: Boolean): String {
    val axis = if (dual) "" else "\nuniform float2 axis1;\nuniform float2 axis2;\n"
    val lights = if (dual) """
    float l1 = dot(n.xy, lightDir1.xy);
    rgb += half(l1 * l1 * lightIntensity1) * lightColor1.rgb;
    float l2 = dot(n.xy, lightDir2.xy);
    rgb += half(l2 * l2 * lightIntensity2) * lightColor2.rgb;
""" else """
    float falloff1 = max(dot(float3(axis1, 0.0), n), 0.0);
    float light1 = clamp(dot(n, lightDir1) * falloff1, 0.0, 1.0);
    rgb += half(light1 * light1 * lightIntensity1) * lightColor1.rgb;

    float falloff2 = max(dot(float3(axis2, 0.0), n), 0.0);
    float light2 = clamp(dot(n, lightDir2) * falloff2, 0.0, 1.0);
    rgb += half(light2 * light2 * lightIntensity2) * lightColor2.rgb;
"""
    return """
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
$axis
float pickRadius(float2 fragCoord, float4 radii) {
    float2 up = fragCoord.y < halfView.y ? radii.xy : radii.zw;
    return fragCoord.x < halfView.x ? up.x : up.y;
}

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

    half3 rgb = strokeColor.rgb * half(strokeAlphaMul * strokeAlpha * strokeAlpha);

    float3 n = getNormal(fragCoord, sdf, R);
$lights
    return half4(rgb * half(highlightAlpha), 1.0) * outMask;
}
"""
}

/** InteractiveHighlight.kt 的径向辉光：选中页签中心亮、往外散开。 */
private const val GLOW = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}
"""
