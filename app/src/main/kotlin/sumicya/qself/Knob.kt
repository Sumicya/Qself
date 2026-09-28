// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * 开关：主页那颗玻璃圆钮 —— 和胶囊同高、顶底对齐，胶囊右边放得下就跟胶囊并排。
 * 底栏藏起来的页面（频道那些）钮也跟着藏，不留一颗钮飘在别的页面上。
 * 点开是一张紧凑的玻璃卡片：两列功能名、钩叉状态（✓ 开着、– 没开、✗ 这版 QQ 没装上），
 * 描述留在无障碍标签里。状态存在 QQ 自己的 SharedPreferences「qself」里，钩子每次被调用时都查一遍，
 * 所以切换即时生效；已经改过的视图（浮起来的底栏、排好的输入行）要重启 QQ 才复原。
 */
private const val KNOB = "qself-knob"
private const val SHEET = "qself-sheet"

fun knob(bar: View) {
    val decor = bar.rootView as? ViewGroup ?: return
    val old = decor.findViewWithTag<View>(KNOB)
    if (bar.height == 0) return
    if (!bar.isShown) { // 底栏不在这页上：钮藏起来，别侵入别的页面
        old?.visibility = View.GONE
        return
    }
    val dp = bar.dp
    val size = bar.height // 和胶囊同一个尺寸，顶边底边自然齐平
    val at = IntArray(2).also(bar::getLocationInWindow)
    val room = decor.width - at[0] - bar.width // 胶囊右边剩多少
    val beside = room >= (8 * dp).toInt() + size
    val lift = decor.height - at[1] - bar.height // 钮和胶囊同高，这一个数同时管顶和底
    val end = if (beside) room - (8 * dp).toInt() - size else (8 * dp).toInt()
    if (old != null) {
        old.visibility = View.VISIBLE
        val lp = old.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.width != size || lp.bottomMargin != lift || lp.marginEnd != end) {
            lp.width = size
            lp.height = size
            lp.bottomMargin = lift
            lp.marginEnd = end
            old.layoutParams = lp
        }
        return
    }
    val knob = ImageView(bar.context).apply {
        tag = KNOB
        contentDescription = "Qself"
        setImageResource(android.R.drawable.ic_menu_preferences)
        imageTintList = ColorStateList.valueOf(
            monet(night(), 0xFF) ?: if (night()) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt())
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = (12 * dp).toInt()
        setPadding(p, p, p, p)
        outlineProvider = capsule()
        clipToOutline = true
        elevation = 6 * dp
        background = Glass(this)
        // 按压反馈：foreground 一层无边界涟漪，background 还是玻璃
        foreground = borderlessRipple(this)
        setOnClickListener { sheet(it) }
    }
    decor.addView(knob, FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.BOTTOM).apply {
        marginEnd = end
        bottomMargin = lift
    })
}

fun borderlessRipple(v: View): Drawable? = TypedValue().let {
    if (v.context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true))
        v.context.getDrawable(it.resourceId) else null
}

/** 画出来的钩 / 杠 / 叉：系统字体里这三个字形各家各样，自己画才稳。 */
private class Mark(dp: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var on = true
    private var broken = false

    fun state(on: Boolean, broken: Boolean) {
        this.on = on
        this.broken = broken
        paint.color = when {
            broken -> 0xFFFF453A.toInt()
            on -> 0xFF30D158.toInt()
            else -> 0xFF8E8E93.toInt()
        }
        invalidateSelf()
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val u = min(b.width(), b.height()) * 0.30f
        path.reset()
        when {
            broken -> { // 叉
                path.moveTo(cx - u, cy - u)
                path.lineTo(cx + u, cy + u)
                path.moveTo(cx + u, cy - u)
                path.lineTo(cx - u, cy + u)
            }
            on -> { // 钩
                path.moveTo(cx - u, cy + u * 0.05f)
                path.lineTo(cx - u * 0.2f, cy + u * 0.75f)
                path.lineTo(cx + u, cy - u * 0.65f)
            }
            else -> { // 杠
                path.moveTo(cx - u, cy)
                path.lineTo(cx + u, cy)
            }
        }
        c.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 开关面板直接挂 decor：点卡片外关闭，不额外建窗口，也不录制 QQ 背景。 */
private fun sheet(anchor: View) {
    val decor = anchor.rootView as? ViewGroup ?: return
    if (decor.findViewWithTag<View>(SHEET) != null) return
    val store = store() ?: return
    val night = night()
    val dp = anchor.dp
    val ctx = anchor.context
    val ink = if (night) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
    val sub = if (night) 0xFF98989E.toInt() else 0xFF6E6E73.toInt()

    val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun cell(f: Feature): View {
        val mark = Mark(dp)
        val title = TextView(ctx).apply {
            text = f.name
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ink)
        }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (7 * dp).toInt()
            setPadding(p, 0, p, 0)
            addView(View(ctx).apply { background = mark },
                LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply { marginEnd = (6 * dp).toInt() })
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            foreground = borderlessRipple(this)
        }
        fun paint() {
            val broken = f.name in failed
            val enabled = store.getBoolean(f.name, true)
            mark.state(!broken && enabled, broken)
            title.setTextColor(if (broken) sub else ink)
            view.contentDescription = "${f.name}，${f.desc}，${if (broken) "不可用" else if (enabled) "已开启" else "已关闭"}"
        }
        paint()
        if (f.name !in failed) view.setOnClickListener { // ✗ 是这版 QQ 里没装上，点了也没用
            store.edit().putBoolean(f.name, !store.getBoolean(f.name, true)).apply()
            paint()
        }
        return view
    }
    for (i in features.indices step 2) { // 一行两张卡
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(cell(features[i]), LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f))
        if (i + 1 < features.size)
            row.addView(cell(features[i + 1]), LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f))
        else row.addView(View(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        grid.addView(row)
    }

    fun button(label: String, body: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        textSize = 15f
        setTextColor(ink)
        gravity = Gravity.CENTER
        val p = (12 * dp).toInt()
        setPadding(p * 2, p, p * 2, p)
        foreground = borderlessRipple(this)
        setOnClickListener { body() }
    }
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val r = 28 * dp
        background = Glass(this, r)
        outlineProvider = capsule(r)
        clipToOutline = true
        elevation = 8 * dp
        setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        addView(TextView(ctx).apply {
            text = "Qself ${BuildConfig.VERSION_NAME}"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ink)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (6 * dp).toInt() })
        addView(ScrollView(ctx).apply { addView(grid) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(button("重启 QQ") { android.os.Process.killProcess(android.os.Process.myPid()) })
            addView(button("好") { decor.findViewWithTag<View>(SHEET)?.let { decor.removeView(it) } })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    val dim = FrameLayout(ctx).apply {
        tag = SHEET
        setBackgroundColor(0x66000000)
        setOnClickListener { decor.removeView(this) } // 点卡片外面关掉
        addView(card, FrameLayout.LayoutParams(
            min(decor.width - (40 * dp).toInt(), (400 * dp).toInt()),
            min((decor.height * 0.62f).toInt(), (((features.size + 1) / 2 * 48 + 104) * dp).toInt()), Gravity.CENTER))
    }
    decor.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}
