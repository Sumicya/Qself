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
private val knobs: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
private val sheets: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
private fun easing() = PathInterpolator(0.2f, 0f, 0f, 1f)

fun motionDuration(milliseconds: Long): Long = if (ValueAnimator.areAnimatorsEnabled()) milliseconds else 0L

/** 只用于模块自己创建的按钮，不替换 QQ 原控件的触摸监听器。 */
private fun View.pressMotion(kernel: Glass? = null) {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (kernel != null) kernel.kernelTouchDown(event.x) else view.animate().scaleX(0.96f).scaleY(0.96f)
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
        clipToOutline = false; elevation = 6 * dp
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

/** 明确的三线菜单，替换 Android 自带的小扳手图标。 */
private class MenuGlyph(dp: Float) : Drawable() {
    private val intrinsic = (24 * dp).toInt()
    override fun getIntrinsicWidth() = intrinsic
    override fun getIntrinsicHeight() = intrinsic
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -1; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND
    }
    override fun draw(canvas: Canvas) {
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

/** 面板内只用轻量状态填色，不逐项重复采样玻璃或叠加水波纹/缩放。 */
private fun menuPress(dp: Float) = android.graphics.drawable.StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = 12 * dp; setColor(if (night()) 0x18FFFFFF else 0x10000000)
    })
}

private fun sheet(anchor: View) {
    val decor = anchor.rootView as? ViewGroup ?: return
    if (decor.findViewWithTag<View>(SHEET) != null) return
    val prefs = store() ?: return
    val ctx = anchor.context
    val dp = anchor.dp
    val ink = if (night()) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
    val sub = if (night()) 0xFF98989E.toInt() else 0xFF6E6E73.toInt()
    val dim = FrameLayout(ctx).apply { tag = SHEET; alpha = 0f }
    val card = object : LinearLayout(ctx) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val glass = background as? Glass
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> glass?.kernelTouchDown(event.x, event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> glass?.kernelTouchEnd()
            }
            return super.dispatchTouchEvent(event)
        }
    }.apply {
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
        card.animate().cancel(); dim.animate().cancel()
        card.animate().scaleX(0.94f).scaleY(0.94f)
            .setDuration(motionDuration(140)).setInterpolator(easing()).start()
        dim.animate().alpha(0f).setDuration(motionDuration(140)).withEndAction {
            (dim.parent as? ViewGroup)?.removeView(dim)
            anchor.requestFocus()
        }.start()
    }
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
    features.forEach { grid.addView(cell(it), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    val heading = TextView(ctx).apply {
        text = "Qself ${BuildConfig.VERSION_NAME}"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(ink)
        minimumHeight = (40 * dp).toInt()
        setPadding(0, 0, 0, (8 * dp).toInt())
    }
    card.addView(heading)
    grid.addView(TextView(ctx).apply {
        text = "勾选仅表示配置开启，不代表效果已验证。部分功能需重进页面或重启 QQ；恢复默认也遵循此规则。"
        textSize = 12f; setTextColor(sub)
        setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), (8 * dp).toInt())
    }, 0)
    card.addView(ScrollView(ctx).apply { isFillViewport = false; addView(grid) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    fun button(label: String, body: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(ink)
        minimumHeight = (48 * dp).toInt()
        setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
        background = menuPress(dp)
        setOnClickListener { body() }
    }
    grid.addView(TextView(ctx).apply {
        text = "消息外观"; textSize = 15f; setTextColor(ink)
        setPadding((6 * dp).toInt(), (16 * dp).toInt(), 0, (8 * dp).toInt())
    })
    val format = android.widget.EditText(ctx).apply {
        setText(prefs.getString(TIME_PATTERN, "HH:mm")); textSize = 14f; setTextColor(ink)
        hint = "时间格式，例如 MM-dd HH:mm:ss"; setHintTextColor(sub); isSingleLine = true
        filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        contentDescription = "消息内时间格式"
    }
    grid.addView(format)
    grid.addView(button("保存时间格式") {
        val value = format.text.toString()
        if (!validTimePattern(value)) format.error = "格式无效，例如 HH:mm 或 MM-dd HH:mm:ss"
        else { prefs.edit().putString(TIME_PATTERN, value).apply(); refreshMessageStyle(); format.error = null }
    })
    val cornerLabel = TextView(ctx).apply { textSize = 13f; setTextColor(ink) }
    grid.addView(cornerLabel)
    val radiusSeek = android.widget.SeekBar(ctx).apply {
        max = 50; progress = (prefs.getInt(AVATAR_RADIUS, 20)).coerceIn(0, 50)
        cornerLabel.text = "头像圆角：$progress%（0 为直角，50 为圆形）"
        contentDescription = "头像圆角百分比"
        setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, user: Boolean) {
                cornerLabel.text = "头像圆角：$value%（0 为直角，50 为圆形）"
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar) {
                prefs.edit().putInt(AVATAR_RADIUS, bar.progress).apply(); refreshMessageStyle()
            }
        })
    }
    grid.addView(radiusSeek)
    grid.addView(LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.END
        addView(button("恢复默认") {
            val edit = prefs.edit()
            features.forEach { edit.putBoolean(it.name, it.default) }
            edit.putString(TIME_PATTERN, "HH:mm").putInt(AVATAR_RADIUS, 20)
            edit.apply(); format.setText("HH:mm"); radiusSeek.progress = 20
            paints.forEach { it() }; refreshLooks(); card.invalidate()
        })
        addView(button("结束进程") {
            AlertDialog.Builder(ctx).setTitle("结束 QQ 当前进程？")
                .setMessage("之后请重新打开 QQ。此操作只结束当前进程，不保证其他 QQ 进程同时退出。")
                .setNegativeButton("取消", null)
                .setPositiveButton("结束") { _, _ -> android.os.Process.killProcess(android.os.Process.myPid()) }.show()
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
    val activity = generateSequence(ctx) { (it as? ContextWrapper)?.baseContext?.takeIf { base -> base !== it } }
        .filterIsInstance<Activity>().firstOrNull()
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
}
