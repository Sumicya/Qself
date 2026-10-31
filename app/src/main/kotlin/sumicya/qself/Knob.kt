// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Outline
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt
import kotlin.math.min

private const val KNOB = "qself-knob"
private const val SHEET = "qself-sheet"
private const val TAG_SHEET_CLOSE = 0x5153434C
private val knobs: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
private val sheets: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
private fun easing() = PathInterpolator(0.2f, 0f, 0f, 1f)

fun motionDuration(milliseconds: Long): Long = if (ValueAnimator.areAnimatorsEnabled()) milliseconds else 0L

private fun safeTopText(shiftDp: Int) =
    "顶栏再下移：${shiftDp}dp（顶栏固定停在状态栏／摄像头下沿，这里只往下让，不会顶到 y=0）"

/** 只用于模块自己创建的按钮，不替换 QQ 原控件的触摸监听器。 */
private fun View.pressMotion(kernel: Glass? = null) {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (kernel != null) kernel.kernelTouchDown(event.x, event.y) else if (event.actionMasked == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.96f).scaleY(0.96f)
                .setDuration(motionDuration(80)).setInterpolator(easing()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (kernel != null) kernel.kernelTouchEnd() else view.animate().scaleX(1f).scaleY(1f)
                .setDuration(motionDuration(150)).setInterpolator(easing()).start()
        }
        false
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            view.animate().cancel()
            if (kernel == null) (view.background as? Glass)?.dispose()
            // Kernel Glass 自己的 attach listener 停帧/释放速度跟踪器，重新挂载时可再次启动。
        }
    })
}

/** 间距以静止可见玻璃边缘计，不把最大的动画包络当作静态空白。 */
internal data class KnobPlacement(val width: Int, val height: Int, val bottom: Int, val end: Int, val beside: Boolean)
internal fun knobPlacement(decorWidth: Int, decorHeight: Int, left: Float, top: Float,
    right: Float, bottom: Float, density: Float, rtl: Boolean): KnobPlacement {
    val inset = (8*density).toInt()
    val visibleWidth = (76*density).toInt()
    val visibleHeight = (64*density).toInt()
    val width = visibleWidth+2*inset
    val height = visibleHeight+2*inset
    val gap = (12*density).toInt()
    val edge = (8*density).toInt()
    val room = if (rtl) left else decorWidth-right
    val beside = room >= gap+visibleWidth+inset+edge
    val end = if (beside) (room-gap-visibleWidth-inset).roundToInt() else edge
    val marginBottom = if (beside) (decorHeight-(top+bottom)/2-height/2f).roundToInt()
        else (decorHeight-top+gap-inset).roundToInt()
    return KnobPlacement(width,height,marginBottom,end,beside)
}

fun knob(bar: View) {
    val decor = bar.rootView as? ViewGroup ?: return
    val old = decor.findViewWithTag<View>(KNOB)
    if (bar.height == 0) return
    if (!bar.isShown) { old?.visibility = View.GONE; return }
    val dp = bar.dp
    // 可见 76×64dp，与静止底栏同高；物理布局保留四周 8dp 绘制空间。
    val inset = (8 * dp).toInt()
    val transform = Matrix()
    bar.transformMatrixToGlobal(transform); decor.transformMatrixToLocal(transform)
    val rect = RectF(0f,0f,bar.width.toFloat(),bar.height.toFloat()).also(transform::mapRect)
    val placement = knobPlacement(decor.width,decor.height,rect.left,rect.top,rect.right,rect.bottom,
        dp,decor.layoutDirection == View.LAYOUT_DIRECTION_RTL)
    val width = placement.width; val height = placement.height
    val bottom = placement.bottom; val end = placement.end
    if (old != null) {
        old.visibility = View.VISIBLE
        val shadow = if (night()) 0x33000000 else 0x1A000000
        old.outlineAmbientShadowColor = shadow
        old.outlineSpotShadowColor = shadow
        (old as? ImageView)?.let { iv ->
            iv.imageTintList = ColorStateList.valueOf(
                monet(night(), 0xFF) ?: if (night()) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
            )
            iv.drawable?.invalidateSelf()
        }
        val lp = old.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.width != width || lp.height != height || lp.bottomMargin != bottom || lp.marginEnd != end) {
            lp.width = width; lp.height = height; lp.bottomMargin = bottom; lp.marginEnd = end
            old.layoutParams = lp
        }
        return
    }
    val button = ImageView(bar.context).apply {
        tag = KNOB
        contentDescription = "Qself 设置"
        isFocusable = true
        setImageDrawable(MenuGlyph(dp))
        imageTintList = ColorStateList.valueOf(monet(night(), 0xFF)
            ?: if (night()) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt())
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val glyphSize = (24*dp).toInt()
        val horizontal = (width-glyphSize)/2; val vertical = (height-glyphSize)/2
        setPadding(horizontal,vertical,horizontal,vertical)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(inset,inset,view.width-inset,view.height-inset,32*dp)
            }
        }
        clipToOutline = false; elevation = 10 * dp
        outlineAmbientShadowColor = if (night()) 0x33000000 else 0x1A000000
        outlineSpotShadowColor = outlineAmbientShadowColor
        val glass = Glass(this, kernelButton = true)
        background = InsetDrawable(glass,inset)
        setOnClickListener { sheet(it) }
        pressMotion(glass)
        alpha = 0f; scaleX = 0.9f; scaleY = 0.9f
    }
    knobs.add(button)
    decor.addView(button, FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.BOTTOM).apply {
        marginEnd = end; bottomMargin = bottom
    })
    button.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(motionDuration(180)).setInterpolator(easing()).start()
}

fun borderlessRipple(view: View): Drawable? = TypedValue().let {
    if (view.context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true))
        view.context.getDrawable(it.resourceId) else null
}

/** 紧凑面板上限 360×520dp；屏幕/键盘边缘仍留出 16dp。 */
internal fun menuSize(width: Int, height: Int, density: Float): Pair<Int, Int> {
    val old = min((height * 0.62f).toInt().coerceAtLeast(0), (440 * density).toInt())
    return min((width - 32 * density).toInt().coerceAtLeast(0), (360 * density).toInt()) to
        min(old + (80 * density).toInt(), (height - 32 * density).toInt().coerceAtLeast(0))
}

/** 坐标均为去除系统栏/键盘后的安全区；狭矮窗口允许覆盖入口，但不越界。 */
internal data class MenuPlacement(val left: Int, val top: Int, val width: Int, val height: Int)
internal fun menuPlacement(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int,
    density: Float): MenuPlacement {
    val (w, limit) = menuSize(width, height, density)
    val margin = (16 * density).toInt()
    val gap = (12 * density).toInt()
    val above = (top - gap - margin).coerceAtLeast(0)
    val below = (height - bottom - gap - margin).coerceAtLeast(0)
    val room = maxOf(above, below)
    val h = if (room >= (240 * density).toInt()) min(limit, room) else limit
    val x = ((left + right - w) / 2).coerceIn(min(margin, width.coerceAtLeast(0)), (width - w - margin).coerceAtLeast(min(margin, width.coerceAtLeast(0))))
    val y = (if (above >= below) top - gap - h else bottom + gap)
        .coerceIn(min(margin, height.coerceAtLeast(0)), (height - h - margin).coerceAtLeast(min(margin, height.coerceAtLeast(0))))
    return MenuPlacement(x, y, w, h)
}

/** 明确的三线菜单，替换 Android 自带的小扳手图标；支持深浅色自动取色与 setTintList。 */
private class MenuGlyph(dp: Float) : Drawable() {
    private val intrinsic = (24 * dp).toInt()
    private var tint: ColorStateList? = null
    override fun getIntrinsicWidth() = intrinsic
    override fun getIntrinsicHeight() = intrinsic
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -1; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND
    }
    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        invalidateSelf()
    }
    override fun draw(canvas: Canvas) {
        val baseColor = tint?.defaultColor
            ?: monet(night(), 0xFF)
            ?: if (night()) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
        val prevAlpha = paint.alpha
        paint.color = baseColor
        paint.alpha = prevAlpha
        val save = canvas.save()
        val size = min(bounds.width(),bounds.height()).toFloat()
        canvas.translate(bounds.exactCenterX()-size/2,bounds.exactCenterY()-size/2)
        canvas.scale(size/24f,size/24f)
        for (y in listOf(6f, 12f, 18f)) canvas.drawLine(4f, y, 20f, y, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private class Mark(private val dp: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f * dp
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    private var on = false
    private var broken = false
    private var initialized = false
    private var progress = 1f
    private var color = 0xFF8E8E93.toInt()
    private var animator: ValueAnimator? = null
    fun state(on: Boolean, broken: Boolean) {
        if (initialized && this.on == on && this.broken == broken) return
        animator?.cancel()
        this.on = on; this.broken = broken
        val target = when { broken -> 0xFFFF453A.toInt(); on -> 0xFF30D158.toInt(); else -> 0xFF8E8E93.toInt() }
        val from = color
        if (initialized && ValueAnimator.areAnimatorsEnabled()) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 160; interpolator = easing()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    color = ArgbEvaluator().evaluate(progress, from, target) as Int
                    invalidateSelf()
                }
                start()
            }
        } else { color = target; progress = 1f; invalidateSelf() }
        initialized = true
    }
    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
        val unit = min(bounds.width(), bounds.height()) * 0.30f
        path.reset(); segment.reset()
        when {
            broken -> {
                path.moveTo(cx - unit, cy - unit); path.lineTo(cx + unit, cy + unit)
                path.moveTo(cx + unit, cy - unit); path.lineTo(cx - unit, cy + unit)
            }
            on -> {
                path.moveTo(cx - unit, cy + unit * 0.05f)
                path.lineTo(cx - unit * 0.2f, cy + unit * 0.75f)
                path.lineTo(cx + unit, cy - unit * 0.65f)
            }
            else -> { path.moveTo(cx - unit, cy); path.lineTo(cx + unit, cy) }
        }
        paint.color = color
        // 叉有两条 contour，直接画；钩与横杠按进度描出。
        if (broken || progress >= 1f) canvas.drawPath(path, paint)
        else {
            measure.setPath(path, false)
            measure.getSegment(0f, measure.length * progress, segment, true)
            canvas.drawPath(segment, paint)
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 面板内只用轻量状态填色，不逐项重复采样玻璃或叠加水波纹/缩放；危险动作使用警示底色。 */
internal fun dangerousMenuItem(tag: String?, title: String?): Boolean =
    tag == "DelMenuItem" || tag == "RevokeMenuItem" || title == "删除" || title == "撤回" || title == "结束进程"

internal data class MenuItemBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal fun hitMenuItemIndex(x: Float, y: Float, rects: List<MenuItemBounds>): Int =
    if (!x.isFinite() || !y.isFinite()) -1
    else rects.indexOfFirst { x >= it.left && x < it.right && y >= it.top && y < it.bottom }

private fun menuPress(dp: Float, danger: Boolean = false, radii: FloatArray? = null) = android.graphics.drawable.StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.GradientDrawable().apply {
        if (radii != null && radii.size == 8) cornerRadii = radii else cornerRadius = 12 * dp
        setColor(if (danger) (if (night()) 0x2EFF453A else 0x22D70015) else (if (night()) 0x18FFFFFF else 0x10000000))
    })
}

private class MenuTouchTracker(private val root: View, private val canScroll: () -> Boolean = { false }) : View.OnTouchListener {
    private val tracked = ArrayList<java.lang.ref.WeakReference<View>>()
    private val active = ArrayList<View>()
    private val rects = ArrayList<MenuItemBounds>()
    private val matrix = Matrix()
    private val point = FloatArray(2)
    private var downRawX = 0f
    private var downRawY = 0f
    private var cancelled = false
    private var scrolling = false

    fun track(item: View) {
        if (tracked.none { it.get() === item }) tracked.add(java.lang.ref.WeakReference(item))
        item.setOnTouchListener(this)
    }

    fun clearItems() {
        tracked.mapNotNull { it.get() }.forEach { it.isPressed = false; it.setOnTouchListener(null) }
        tracked.clear(); active.clear(); rects.clear()
    }

    fun dispose() {
        clearItems()
        root.setOnTouchListener(null)
    }

    private fun snapshot() {
        active.clear(); rects.clear()
        val iterator = tracked.iterator()
        while (iterator.hasNext()) {
            val v = iterator.next().get()
            if (v == null) { iterator.remove(); continue }
            if (!v.isAttachedToWindow || v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) continue
            matrix.reset(); v.transformMatrixToGlobal(matrix); root.transformMatrixToLocal(matrix)
            val bounds = RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())
            matrix.mapRect(bounds)
            active.add(v); rects.add(MenuItemBounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
        }
    }

    private fun localPoint(source: View, event: MotionEvent) {
        matrix.reset(); source.transformMatrixToGlobal(matrix); root.transformMatrixToLocal(matrix)
        point[0] = event.x; point[1] = event.y
        matrix.mapPoints(point)
    }

    private fun highlight(hit: Int) {
        active.forEachIndexed { index, view -> view.isPressed = index == hit }
    }

    override fun onTouch(source: View, event: MotionEvent): Boolean {
        val slop = android.view.ViewConfiguration.get(source.context).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX; downRawY = event.rawY
                cancelled = false; scrolling = false
                snapshot(); localPoint(source, event)
                val hit = hitMenuItemIndex(point[0], point[1], rects)
                highlight(hit)
                return hit >= 0 || source === root
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelled = true; highlight(-1); return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) { cancelled = true; highlight(-1); return true }
                if (canScroll() && !scrolling && verticalBarDrag(event.rawX - downRawX, event.rawY - downRawY, slop)) {
                    scrolling = true; highlight(-1)
                    source.parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                if (!cancelled && !scrolling) {
                    if (!canScroll()) source.parent?.requestDisallowInterceptTouchEvent(true)
                    localPoint(source, event)
                    highlight(hitMenuItemIndex(point[0], point[1], rects))
                }
                return !scrolling
            }
            MotionEvent.ACTION_UP -> {
                localPoint(source, event)
                val hit = if (!cancelled && !scrolling) hitMenuItemIndex(point[0], point[1], rects) else -1
                highlight(-1)
                if (hit in active.indices && active[hit].getTag(0x51534447) == true) lastSelfRecallMs = System.currentTimeMillis()
                runCatching { if (hit in active.indices) active[hit].performClick() }.onFailure { log("菜单项点击失败", it) }
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                cancelled = true; highlight(-1); return true
            }
        }
        return false
    }
}

private val nativeMenus = java.util.WeakHashMap<View, MenuTouchTracker>()
fun nativeMessageMenu() {
    val menuClass = cls("com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout")
    val itemClass = cls("com.tencent.qqnt.aio.menu.ui.d")
    val itemType = itemClass.getDeclaredMethod("e").apply { isAccessible = true }
    val itemTitle = itemClass.getDeclaredMethod("f").apply { isAccessible = true }
    val rowHeightField = menuClass.getDeclaredField("C").apply { isAccessible = true }
    val colCountField = menuClass.getDeclaredField("H").apply { isAccessible = true }
    val scrollField = menuClass.getDeclaredField("Q").apply { isAccessible = true }
    val listField = menuClass.getDeclaredField("R").apply { isAccessible = true }
    val blurField = menuClass.getDeclaredField("S").apply { isAccessible = true }
    val sourceField = menuClass.getDeclaredField("T").apply { isAccessible = true }
    val radiusField = menuClass.getDeclaredField("W").apply { isAccessible = true }
    val expanded = menuClass.getDeclaredField("a0").apply { isAccessible = true }
    val createItem = menuClass.getDeclaredMethod("p", Integer.TYPE, itemClass, java.lang.Boolean.TYPE, FloatArray::class.java)
        .apply { isAccessible = true }
    val createExpand = menuClass.getDeclaredMethod("l", Integer.TYPE).apply { isAccessible = true }
    val buildMenu = menuClass.getDeclaredMethod("H").apply { isAccessible = true }
    val showMenu = menuClass.getDeclaredMethod("G")
    fun trackerFor(menu: View) = nativeMenus.getOrPut(menu) {
        MenuTouchTracker(menu) { runCatching { expanded.getBoolean(menu) }.getOrDefault(false) }
    }
    hook(buildMenu, gated = false) { chain ->
        val menu = chain.thisObject as? View
        menu?.let {
            nativeMenus[it]?.clearItems()
            runCatching {
                colCountField.setInt(it, 24)
                rowHeightField.setInt(it, (220 * it.dp).roundToInt())
                radiusField.setFloat(it, 12 * it.dp)
            }
        }
        chain.proceed().also {
            val host = menu ?: return@also
            val dp = host.dp
            runCatching {
                colCountField.setInt(host, 5)
                rowHeightField.setInt(host, (220 * dp).roundToInt())
                expanded.setBoolean(host, true)
                (blurField.get(host) as? View)?.let { s -> s.alpha = 0f; s.scaleX = 0f; s.scaleY = 0f; s.visibility = View.GONE }
                // 字段 T 是聊天列表 mSourceView（RecyclerView），严禁设为 GONE，否则关闭菜单后整个聊天空白。
                (sourceField.get(host) as? View)?.let { src -> if (src.visibility == View.GONE) src.visibility = View.VISIBLE }
                if (host is ViewGroup) {
                    for (i in 0 until host.childCount) {
                        val ch = host.getChildAt(i)
                        if (ch != null && ch.javaClass == View::class.java) ch.visibility = View.GONE
                    }
                }
                val scroll = scrollField.get(host) as? ScrollView
                val list = listField.get(host) as? LinearLayout
                if (scroll != null && list != null) {
                    val items = ArrayList<LinearLayout>()
                    fun collect(group: ViewGroup) {
                        for (i in 0 until group.childCount) {
                            when (val ch = group.getChildAt(i)) {
                                is LinearLayout -> if (ch.getTag(0x51534D4E) == true) items.add(ch) else collect(ch)
                                is ViewGroup -> collect(ch)
                            }
                        }
                    }
                    collect(list)
                    if (items.isNotEmpty()) {
                        list.removeAllViews()
                        list.orientation = LinearLayout.VERTICAL
                        val pad = (4 * dp).roundToInt()
                        list.setPadding(pad, pad, pad, pad)
                        list.minimumWidth = (196 * dp).roundToInt()
                        val rowH = (48 * dp).roundToInt()
                        val padX = (18 * dp).roundToInt()
                        for (cell in items) {
                            (cell.parent as? ViewGroup)?.removeView(cell)
                            // 原生 q()->r()/s()/t() 会在 p() 返回后覆盖 setPadding(0, D=16dp, 0, D=16dp)，在此归零纵向内边距防止文字下部被截断。
                            cell.setPadding(padX, 0, padX, 0)
                            cell.gravity = Gravity.CENTER_VERTICAL
                            list.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH))
                        }
                    }
                    scroll.background = Glass(scroll, 12 * dp, kernelSurface = true)
                    scroll.outlineProvider = capsule(12 * dp)
                    scroll.clipToOutline = true
                    (scroll.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let { lp ->
                        lp.width = (208 * dp).roundToInt()
                        scroll.layoutParams = lp
                    }
                }
            }
        }
    }
    hook(createItem, gated = false) { chain ->
        val out = chain.proceed()
        val menu = chain.thisObject as? View ?: return@hook out
        val cell = out as? View ?: return@hook out
        val dp = cell.dp
        val item = chain.getArg(1)
        val tag = runCatching { itemType.invoke(item) as? String }.getOrNull()
        val title = runCatching { itemTitle.invoke(item) as? String }.getOrNull()
        val danger = dangerousMenuItem(tag, title)
        val ink = if (danger) (if (night()) 0xFFFF5A4F.toInt() else 0xFFD70015.toInt())
            else (if (night()) 0xFFF5F7FA.toInt() else 0xFF1C1C1E.toInt())
        if (cell is LinearLayout) {
            cell.setTag(0x51534D4E, true)
            cell.setTag(0x51534447, danger)
            cell.orientation = LinearLayout.HORIZONTAL
            cell.gravity = Gravity.CENTER_VERTICAL
            cell.minimumHeight = (48 * dp).roundToInt()
            cell.minimumWidth = (188 * dp).roundToInt()
            val padX = (18 * dp).roundToInt()
            cell.setPadding(padX, 0, padX, 0)
            for (i in 0 until cell.childCount) {
                when (val child = cell.getChildAt(i)) {
                    is ImageView -> {
                        child.imageTintList = ColorStateList.valueOf(ink)
                        val iconSize = (20 * dp).roundToInt()
                        child.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                            rightMargin = (12 * dp).roundToInt()
                            gravity = Gravity.CENTER_VERTICAL
                        }
                    }
                    is TextView -> {
                        if (!title.isNullOrEmpty()) child.text = title
                        child.setTextColor(ink)
                        child.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        child.includeFontPadding = false
                        child.maxLines = 1
                        child.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        child.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            gravity = Gravity.CENTER_VERTICAL
                        }
                    }
                }
            }
        }
        val itemRadii = FloatArray(8) { 6 * dp }
        cell.background = menuPress(dp, danger = danger, radii = itemRadii)
        trackerFor(menu).track(cell)
        out
    }
    hook(createExpand, gated = false) { chain ->
        val out = chain.proceed()
        val menu = chain.thisObject as? View ?: return@hook out
        (out as? View)?.let { trackerFor(menu).track(it) }
        out
    }
    hook(showMenu, gated = false) { chain ->
        val out = chain.proceed()
        val menu = chain.thisObject as? View ?: return@hook out
        val tracker = trackerFor(menu)
        menu.setOnTouchListener(tracker)
        (runCatching { scrollField.get(menu) as? View }.getOrNull())?.setOnTouchListener(tracker)
        out
    }
}

/**
 * GPL-3 §5(d)：交互程序要有可点的「关于」入口，里面给出版权、无担保声明与许可全文入口。
 * 许可全文随包放在 assets（与仓根 LICENSE 逐字节相同的副本），不另外写一份摘要。
 */
private fun aboutQself(ctx: android.content.Context) {
    val dp = ctx.resources.displayMetrics.density
    val body = TextView(ctx).apply {
        text = buildString {
            append("Qself ").append(BuildConfig.VERSION_NAME).append("\n\n")
            append("Copyright (C) 2026 Sumicya\n")
            append("许可：GNU General Public License 第 3 版或更高版本（GPL-3.0-or-later）\n\n")
            append("本程序按现状提供，不附带任何担保，包括但不限于适销性与特定用途适用性的担保。")
            append("完整条款以 GPL-3.0 全文为准；点「许可全文」查看。\n\n")
            append("源码：https://github.com/Sumicya/Qself")
        }
        textSize = 13f
        setTextColor(if (night()) 0xFFE8EEF7.toInt() else 0xFF1C1C1E.toInt())
        setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (6 * dp).toInt())
        setTextIsSelectable(true)
    }
    AlertDialog.Builder(ctx)
        .setTitle("关于 Qself")
        .setView(body)
        .setPositiveButton("许可全文") { _, _ -> showLicense(ctx) }
        .setNegativeButton("好", null)
        .show()
}

/** 许可全文：直接读随包的 GPL-3.0.txt，读不到就照实说读不到，不伪造正文。 */
private fun showLicense(ctx: android.content.Context) {
    val text = runCatching {
        ctx.assets.open("GPL-3.0.txt").bufferedReader().use { it.readText() }
    }.getOrElse { "许可全文读取失败：" + (it.message ?: "包内缺少 assets 里的 GPL 全文") }
    val page = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(24, 16, 24, 16)
        setTextIsSelectable(true)
    }
    AlertDialog.Builder(ctx)
        .setTitle("GPL-3.0 全文")
        .setView(ScrollView(ctx).apply { addView(page) })
        .setPositiveButton("好", null)
        .show()
}

private fun sheet(anchor: View) {
    val decor = anchor.rootView as? ViewGroup ?: return
    val existing = decor.findViewWithTag<View>(SHEET)
    if (existing != null) {
        @Suppress("UNCHECKED_CAST")
        val closeFn = existing.getTag(TAG_SHEET_CLOSE) as? (() -> Unit)
        if (closeFn != null) closeFn() else (existing.parent as? ViewGroup)?.removeView(existing)
        return
    }
    val prefs = store() ?: return
    val ctx = anchor.context
    val dp = anchor.dp
    val ink = if (night()) 0xFFF5F7FA.toInt() else 0xFF1C1C1E.toInt()
    val sub = if (night()) 0xFFB8BEC8.toInt() else 0xFF6E6E73.toInt()
    val dim = FrameLayout(ctx).apply { tag = SHEET; alpha = 0f }
    // 面板只提供玻璃材质；按压反馈由各项自己的状态背景负责，不能驱动整卡高亮。
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; isClickable = true
        val radius = 22 * dp
        background = Glass(this, radius, kernelSurface = true); outlineProvider = capsule(radius); clipToOutline = true
        elevation = 8 * dp
        setPadding((10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        scaleX = 0.94f; scaleY = 0.94f
    }
    var closing = false
    fun close() {
        if (closing) return
        closing = true
        dim.tag = null
        card.animate().cancel(); dim.animate().cancel()
        card.animate().scaleX(0.94f).scaleY(0.94f)
            .setDuration(motionDuration(140)).setInterpolator(easing()).start()
        dim.animate().alpha(0f).setDuration(motionDuration(140)).withEndAction {
            (dim.parent as? ViewGroup)?.removeView(dim)
            anchor.requestFocus()
        }.start()
    }
    dim.setTag(TAG_SHEET_CLOSE, ::close)
    val paints = ArrayList<() -> Unit>()
    fun cell(feature: Feature): View {
        val mark = Mark(dp)
        val title = TextView(ctx).apply { text = feature.name; textSize = 14f; maxLines = 2 }
        val desc = TextView(ctx).apply { textSize = 12f; setTextColor(sub) }
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (64 * dp).toInt()
            setPadding((6 * dp).toInt(), (7 * dp).toInt(), (6 * dp).toInt(), (7 * dp).toInt())
            contentDescription = feature.name + "，" + feature.desc
            addView(View(ctx).apply { background = mark }, LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply {
                marginEnd = (6 * dp).toInt()
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; addView(title); addView(desc)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            background = menuPress(dp)
        }
        val paint: () -> Unit = {
            val broken = feature.name in failed
            val enabled = prefs.getBoolean(feature.name, feature.default)
            mark.state(!broken && enabled, broken)
            val state = if (broken) "安装失败，点按查看原因" else if (enabled) "配置已开启" else "配置已关闭"
            desc.text = feature.desc + "\n" + state
            cell.contentDescription = feature.name + "，" + feature.desc + "，" + state
            cell.isActivated = enabled && !broken
            title.setTextColor(if (broken) sub else ink)
            cell.isSelected = !broken && prefs.getBoolean(feature.name, feature.default)
        }
        paints.add(paint); paint()
        cell.setOnClickListener {
            if (feature.name in failed) {
                AlertDialog.Builder(ctx).setTitle(feature.name + "：安装失败")
                    .setMessage(failures[feature.name] ?: "请查看 Qself 日志")
                    .setPositiveButton("好", null).show()
            } else {
                prefs.edit().putBoolean(feature.name, !prefs.getBoolean(feature.name, feature.default)).apply()
                paints.forEach { it() }; refreshLooks(); card.invalidate()
            }
        }
        return cell
    }
    val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val sheetTracker = MenuTouchTracker(grid) { true }
    // 开关按分组摆：30 个开关平铺一列等于没有顺序，找不着也记不住。
    fun groupHeading(text: String) = TextView(ctx).apply {
        this.text = text; textSize = 15f; setTextColor(ink); gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (16 * dp).toInt(), 0, (6 * dp).toInt())
    }
    for ((group, list) in featuresByGroup()) {
        grid.addView(groupHeading(group))
        list.forEach {
            grid.addView(cell(it).also(sheetTracker::track),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    val heading = TextView(ctx).apply {
        text = "Qself ${BuildConfig.VERSION_NAME}"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(ink)
        minimumHeight = (40 * dp).toInt()
        setPadding(0, 0, 0, (8 * dp).toInt())
    }
    card.addView(heading)
    grid.addView(TextView(ctx).apply {
        text = "勾选仅表示配置开启，不代表效果已验证。部分功能需重进页面或重启 QQ（结束进程只结束当前进程）；恢复默认也遵循此规则。"
        textSize = 12f; setTextColor(sub)
        setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), (8 * dp).toInt())
    }, 0)
    card.addView(ScrollView(ctx).apply { isFillViewport = false; addView(grid) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    fun button(label: String, body: () -> Unit) = TextView(ctx).apply {
        val danger = dangerousMenuItem(null, label)
        val dangerInk = if (night()) 0xFFFF453A.toInt() else 0xFFD70015.toInt()
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(if (danger) dangerInk else ink)
        minimumHeight = (48 * dp).toInt()
        setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
        background = if (danger) menuPress(dp, danger = true) else menuPress(dp)
        setOnClickListener { body() }
        sheetTracker.track(this)
    }
    grid.addView(TextView(ctx).apply {
        text = "消息外观"; textSize = 15f; setTextColor(ink)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (16 * dp).toInt(), 0, (6 * dp).toInt())
    })
    val cornerLabel = TextView(ctx).apply {
        textSize = 13f; setTextColor(ink)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
    }
    grid.addView(TextView(ctx).apply {
        text = "消息（头像 / 气泡）"; textSize = 13.5f; setTextColor(sub)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (14 * dp).toInt(), 0, (2 * dp).toInt())
    })
    grid.addView(cornerLabel)
    val radiusSeek = android.widget.SeekBar(ctx).apply {
        max = 50; progress = (prefs.getInt(AVATAR_RADIUS, 20)).coerceIn(0, 50)
        cornerLabel.text = "头像圆角：$progress%（0% 为直角，50% 为圆形）"
        contentDescription = "头像圆角百分比"
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, user: Boolean) {
                cornerLabel.text = "头像圆角：$value%（0% 为直角，50% 为圆形）"
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                prefs.edit().putInt(AVATAR_RADIUS, bar.progress).apply(); refreshMessageStyle()
            }
        })
    }
    grid.addView(radiusSeek)
    val blurLabel = TextView(ctx).apply {
        textSize = 13f; setTextColor(ink)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
    }
    grid.addView(TextView(ctx).apply {
        text = "玻璃"; textSize = 13.5f; setTextColor(sub)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (14 * dp).toInt(), 0, (2 * dp).toInt())
    })
    grid.addView(blurLabel)
    val blurSeek = android.widget.SeekBar(ctx).apply {
        max = 15; progress = (prefs.getInt(GLASS_BLUR, 1)).coerceIn(0, 15)
        blurLabel.text = "玻璃模糊：${progress}dp（默认 1dp）"
        contentDescription = "玻璃模糊半径"
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, user: Boolean) {
                blurLabel.text = "玻璃模糊：${value}dp（默认 1dp）"
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                prefs.edit().putInt(GLASS_BLUR, bar.progress).apply()
                refreshLooks(); card.invalidate()
            }
        })
    }
    grid.addView(blurSeek)
    val bubbleCornerLabel = TextView(ctx).apply {
        textSize = 13f; setTextColor(ink)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
    }
    grid.addView(bubbleCornerLabel)
    val bubbleCornerSeek = android.widget.SeekBar(ctx).apply {
        max = 32; progress = (prefs.getInt(BUBBLE_CORNER, 17)).coerceIn(0, 32)
        bubbleCornerLabel.text = "气泡圆角：${progress}dp（默认 17dp）"
        contentDescription = "气泡圆角半径"
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, user: Boolean) {
                bubbleCornerLabel.text = "气泡圆角：${value}dp（默认 17dp）"
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                prefs.edit().putInt(BUBBLE_CORNER, bar.progress).apply()
                refreshChatLayout()
            }
        })
    }
    grid.addView(bubbleCornerSeek)
    val bubblePadLabel = TextView(ctx).apply {
        textSize = 13f; setTextColor(ink)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
    }
    grid.addView(bubblePadLabel)
    val bubblePadSeek = android.widget.SeekBar(ctx).apply {
        max = 16; progress = (prefs.getInt(BUBBLE_PADDING, 5)).coerceIn(0, 16)
        bubblePadLabel.text = "气泡边距：${progress}dp（默认 5dp）"
        contentDescription = "气泡四周内边距"
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, user: Boolean) {
                bubblePadLabel.text = "气泡边距：${value}dp（默认 5dp）"
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                prefs.edit().putInt(BUBBLE_PADDING, bar.progress).apply()
                refreshChatLayout()
            }
        })
    }
    grid.addView(bubblePadSeek)
    grid.addView(TextView(ctx).apply {
        text = "顶栏"; textSize = 13.5f; setTextColor(sub)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (14 * dp).toInt(), 0, (2 * dp).toInt())
    })
    val safeShiftLabel = TextView(ctx).apply {
        textSize = 13f; setTextColor(ink)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
    }
    grid.addView(safeShiftLabel)
    val safeShiftSeek = android.widget.SeekBar(ctx).apply {
        max = 48; progress = prefs.getInt(SAFE_TOP_SHIFT, 0).coerceIn(0, 48)
        safeShiftLabel.text = safeTopText(progress)
        contentDescription = "顶栏再下移"
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, user: Boolean) {
                safeShiftLabel.text = safeTopText(value)
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                prefs.edit().putInt(SAFE_TOP_SHIFT, bar.progress).apply()
                refreshLooks(); card.invalidate()
            }
        })
    }
    grid.addView(safeShiftSeek)
    grid.addView(TextView(ctx).apply {
        text = "时间格式"; textSize = 13.5f; setTextColor(sub)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (14 * dp).toInt(), 0, (2 * dp).toInt())
    })
    val format = android.widget.EditText(ctx).apply {
        setText(prefs.getString(TIME_PATTERN, "HH:mm")); textSize = 14f; setTextColor(ink)
        hint = "时间格式，例如 MM-dd HH:mm:ss"; setHintTextColor(sub); isSingleLine = true
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        contentDescription = "消息时间格式"
    }
    grid.addView(format)
    grid.addView(TextView(ctx).apply {
        text = TIME_FORMAT_GUIDE
        textSize = 11.5f; setTextColor(sub)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
    })
    grid.addView(button("保存时间格式") {
        val value = format.text.toString()
        if (!validTimePattern(value)) format.error = "格式无效，例如 HH:mm 或 MM-dd HH:mm:ss"
        else { prefs.edit().putString(TIME_PATTERN, value).apply(); refreshMessageStyle(); format.error = null }
    })
    val activity = generateSequence(ctx) { (it as? ContextWrapper)?.baseContext?.takeIf { base -> base !== it } }
        .filterIsInstance<Activity>().firstOrNull()
    grid.addView(LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.END
        addView(TextView(ctx).apply {
            text = "每点一次就与上次的 qself-full-log.txt 合并去重，并先清掉 Download 里的历史编号副本，所以永远只留一个文件。"
            textSize = 11.5f; setTextColor(sub)
            gravity = Gravity.END
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        })
        addView(button("导出全量日志（合并去重）") {
            exportFullQqLog(ctx, activity)
        })
        addView(button("恢复默认") {
            val edit = prefs.edit()
            features.forEach { edit.putBoolean(it.name, it.default) }
            edit.putString(TIME_PATTERN, "HH:mm").putInt(AVATAR_RADIUS, 20).putInt(GLASS_BLUR, 1)
                .putInt(BUBBLE_CORNER, 17).putInt(BUBBLE_PADDING, 5)
                .putInt(SAFE_TOP_SHIFT, 0)
            edit.apply(); format.setText("HH:mm"); radiusSeek.progress = 20; blurSeek.progress = 1
            bubbleCornerSeek.progress = 17; bubblePadSeek.progress = 5
            safeShiftSeek.progress = 0
            safeShiftLabel.text = safeTopText(0)
            paints.forEach { it() }; refreshLooks(); card.invalidate()
        })
        addView(button("关于 Qself") { aboutQself(ctx) })
        addView(button("结束进程") {
            android.os.Process.killProcess(android.os.Process.myPid())
        })
        addView(button("好", ::close))
    })
    dim.addView(card, FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.LEFT))
    fun place() {
        val insets = decor.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
        val safeLeft = insets?.left ?: 0; val safeTop = insets?.top ?: 0
        val matrix = Matrix()
        anchor.transformMatrixToGlobal(matrix); decor.transformMatrixToLocal(matrix)
        val bounds = RectF(0f, 0f, anchor.width.toFloat(), anchor.height.toFloat())
        matrix.mapRect(bounds)
        val p = menuPlacement(decor.width - safeLeft - (insets?.right ?: 0),
            decor.height - safeTop - (insets?.bottom ?: 0),
            bounds.left.toInt() - safeLeft, bounds.top.toInt() - safeTop,
            bounds.right.toInt() - safeLeft, bounds.bottom.toInt() - safeTop, dp)
        val lp = card.layoutParams as FrameLayout.LayoutParams
        if (lp.width != p.width || lp.height != p.height || lp.leftMargin != p.left + safeLeft || lp.topMargin != p.top + safeTop) {
            lp.width = p.width; lp.height = p.height
            lp.leftMargin = p.left + safeLeft; lp.topMargin = p.top + safeTop
            card.layoutParams = lp
        }
        card.pivotX = (bounds.centerX() - p.left - safeLeft).coerceIn(0f, p.width.toFloat())
        card.pivotY = (bounds.centerY() - p.top - safeTop).coerceIn(0f, p.height.toFloat())
    }
    val layout = android.view.ViewTreeObserver.OnGlobalLayoutListener { place() }
    dim.setOnClickListener { close() }
    dim.isFocusableInTouchMode = true
    dim.setOnKeyListener { _, key, event ->
        if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { close(); true } else false
    }
    val back = OnBackInvokedCallback { close() }
    dim.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            activity?.onBackInvokedDispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, back)
        }
        override fun onViewDetachedFromWindow(view: View) {
            activity?.onBackInvokedDispatcher?.unregisterOnBackInvokedCallback(back)
            card.animate().cancel(); dim.animate().cancel()
            if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnGlobalLayoutListener(layout)
            (card.background as? Glass)?.dispose()
            sheets.remove(dim)
        }
    })
    sheets.add(dim)
    decor.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    anchor.bringToFront()
    decor.viewTreeObserver.addOnGlobalLayoutListener(layout)
    place()
    dim.requestFocus()
    dim.animate().alpha(1f).setDuration(motionDuration(180)).setInterpolator(easing()).start()
    card.animate().scaleX(1f).scaleY(1f).translationY(0f)
        .setDuration(motionDuration(200)).setInterpolator(easing()).start()
}

fun resetMenus() {
    (sheets.toList() + knobs.toList()).forEach { view ->
        view.animate().cancel()
        (view.parent as? ViewGroup)?.removeView(view)
    }
    sheets.clear(); knobs.clear()
    nativeMenus.values.toList().forEach { it.dispose() }; nativeMenus.clear()
}
