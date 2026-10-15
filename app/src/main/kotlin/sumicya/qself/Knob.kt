// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
import kotlin.math.min

private const val KNOB = "qself-knob"
private const val SHEET = "qself-sheet"
private val knobs: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
private val sheets: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
private fun easing() = PathInterpolator(0.2f, 0f, 0f, 1f)

fun motionDuration(milliseconds: Long): Long = if (ValueAnimator.areAnimatorsEnabled()) milliseconds else 0L

/** 只用于模块自己创建的按钮，不替换 QQ 原控件的触摸监听器。 */
private fun View.pressMotion() {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f)
                .setDuration(motionDuration(80)).setInterpolator(easing()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                .setDuration(motionDuration(150)).setInterpolator(easing()).start()
        }
        false
    }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            view.animate().cancel()
            (view.background as? Glass)?.dispose()
        }
    })
}

fun knob(bar: View) {
    val decor = bar.rootView as? ViewGroup ?: return
    val old = decor.findViewWithTag<View>(KNOB)
    if (bar.height == 0) return
    if (!bar.isShown) { old?.visibility = View.GONE; return }
    val dp = bar.dp
    val size = (52 * dp).toInt()
    val at = IntArray(2).also(bar::getLocationInWindow)
    val room = decor.width - at[0] - bar.width
    val beside = room >= (8 * dp).toInt() + size
    val bottom = if (beside) decor.height - at[1] - (bar.height + size) / 2
        else decor.height - at[1] + (8 * dp).toInt()
    val end = if (beside) room - (8 * dp).toInt() - size else (8 * dp).toInt()
    if (old != null) {
        old.visibility = View.VISIBLE
        val lp = old.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.width != size || lp.bottomMargin != bottom || lp.marginEnd != end) {
            lp.width = size; lp.height = size; lp.bottomMargin = bottom; lp.marginEnd = end
            old.layoutParams = lp
        }
        return
    }
    val button = ImageView(bar.context).apply {
        tag = KNOB
        contentDescription = "Qself 设置"
        isFocusable = true
        setImageDrawable(MenuGlyph())
        imageTintList = ColorStateList.valueOf(monet(night(), 0xFF)
            ?: if (night()) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt())
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val padding = (12 * dp).toInt()
        setPadding(padding, padding, padding, padding)
        outlineProvider = capsule(); clipToOutline = true; elevation = 6 * dp
        background = Glass(this)
        foreground = borderlessRipple(this)
        setOnClickListener { sheet(it) }
        pressMotion()
        alpha = 0f; scaleX = 0.9f; scaleY = 0.9f
    }
    knobs.add(button)
    decor.addView(button, FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.BOTTOM).apply {
        marginEnd = end; bottomMargin = bottom
    })
    button.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(motionDuration(180)).setInterpolator(easing()).start()
}

fun borderlessRipple(view: View): Drawable? = TypedValue().let {
    if (view.context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true))
        view.context.getDrawable(it.resourceId) else null
}

/** 宽度不变，只向上下各扩约 40dp；屏幕/键盘边缘仍留出 16dp。 */
internal fun menuSize(width: Int, height: Int, density: Float): Pair<Int, Int> {
    val old = min((height * 0.62f).toInt().coerceAtLeast(0), (440 * density).toInt())
    return min((width - 32 * density).toInt().coerceAtLeast(0), (360 * density).toInt()) to
        min(old + (80 * density).toInt(), (height - 32 * density).toInt().coerceAtLeast(0))
}

/** 明确的三线菜单，替换 Android 自带的小扳手图标。 */
private class MenuGlyph : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = -1; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND
    }
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
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

private fun sheet(anchor: View) {
    val decor = anchor.rootView as? ViewGroup ?: return
    if (decor.findViewWithTag<View>(SHEET) != null) return
    val prefs = store() ?: return
    val ctx = anchor.context
    val dp = anchor.dp
    val ink = if (night()) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
    val sub = if (night()) 0xFF98989E.toInt() else 0xFF6E6E73.toInt()
    val dim = FrameLayout(ctx).apply { tag = SHEET; setBackgroundColor(0x66000000); alpha = 0f }
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; isClickable = true
        val radius = 22 * dp
        background = Glass(this, radius); outlineProvider = capsule(radius); clipToOutline = true
        elevation = 8 * dp
        setPadding((10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        scaleX = 0.96f; scaleY = 0.96f; translationY = 16 * dp
    }
    var closing = false
    fun close() {
        if (closing) return
        closing = true
        card.animate().cancel(); dim.animate().cancel()
        card.animate().scaleX(0.98f).scaleY(0.98f).translationY(card.translationY + 12 * dp)
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
        val desc = TextView(ctx).apply { text = feature.desc; textSize = 11f; setTextColor(sub); maxLines = 2 }
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
            foreground = borderlessRipple(this)
            pressMotion()
        }
        val paint: () -> Unit = {
            val broken = feature.name in failed
            mark.state(!broken && prefs.getBoolean(feature.name, feature.default), broken)
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
                paint(); refreshLooks()
            }
        }
        return cell
    }
    val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    for (i in features.indices step 2) grid.addView(LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(cell(features[i]), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(if (i + 1 < features.size) cell(features[i + 1]) else View(ctx),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    })
    val heading = TextView(ctx).apply {
        text = "Qself ${BuildConfig.VERSION_NAME}"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(ink)
        minimumHeight = (40 * dp).toInt()
        setPadding(0, 0, 0, (8 * dp).toInt())
    }
    card.addView(heading)
    card.addView(ScrollView(ctx).apply { isFillViewport = false; addView(grid) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    fun button(label: String, body: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(ink)
        minimumHeight = (48 * dp).toInt()
        setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
        foreground = borderlessRipple(this); setOnClickListener { body() }; pressMotion()
    }
    card.addView(LinearLayout(ctx).apply {
        gravity = Gravity.END
        addView(button("恢复默认") {
            val edit = prefs.edit()
            features.forEach { edit.putBoolean(it.name, it.default) }
            edit.apply(); paints.forEach { it() }; refreshLooks()
        })
        addView(button("重启 QQ") { android.os.Process.killProcess(android.os.Process.myPid()) })
        addView(button("好", ::close))
    })
    val insets = decor.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
    val size = menuSize(decor.width - (insets?.left ?: 0) - (insets?.right ?: 0),
        decor.height - (insets?.top ?: 0) - (insets?.bottom ?: 0), dp)
    dim.addView(card, FrameLayout.LayoutParams(size.first, size.second, Gravity.CENTER).apply {
        leftMargin = insets?.left ?: 0; rightMargin = insets?.right ?: 0
        topMargin = insets?.top ?: 0; bottomMargin = insets?.bottom ?: 0
    })
    // 只有背景/标题接管拖动，开关和 ScrollView 保留自己的点击及滚动。
    var startX = 0f; var startY = 0f; var baseX = 0f; var baseY = 0f; var moved = false
    val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    val drag = View.OnTouchListener { view, event ->
        if (closing) return@OnTouchListener true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY
                baseX = card.translationX; baseY = card.translationY; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - startX; val dy = event.rawY - startY
                if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) moved = true
                if (moved) {
                    card.animate().cancel(); card.scaleX = 1f; card.scaleY = 1f
                    val edge = 16 * dp
                    val lowX = (insets?.left ?: 0) + edge - card.left
                    val highX = decor.width - (insets?.right ?: 0) - edge - card.right
                    val lowY = (insets?.top ?: 0) + edge - card.top
                    val highY = decor.height - (insets?.bottom ?: 0) - edge - card.bottom
                    card.translationX = (baseX + dx).coerceIn(lowX, maxOf(lowX, highX))
                    card.translationY = (baseY + dy).coerceIn(lowY, maxOf(lowY, highY))
                }
            }
            MotionEvent.ACTION_UP -> if (!moved && view === dim) view.performClick()
            MotionEvent.ACTION_CANCEL -> moved = true
        }
        true
    }
    dim.setOnTouchListener(drag); card.setOnTouchListener(drag); heading.setOnTouchListener(drag)
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
            (card.background as? Glass)?.dispose()
            sheets.remove(dim)
        }
    })
    sheets.add(dim)
    decor.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
