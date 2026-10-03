// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.min

/**
 * 开关入口：主页那颗玻璃圆钮 —— 和胶囊同高、顶底对齐，胶囊右边放得下就跟胶囊并排。
 * 底栏藏起来的页面（频道那些）钮也跟着藏，不留一颗钮飘在别的页面上。
 * 点开是一整块玻璃大卡片：一行两张卡，钩叉是画出来的（✓ 开着、– 没开、✗ 这版 QQ 没装上），
 * 每张卡带一句描述，默认全开。状态存在 QQ 自己的 SharedPreferences「qself」里，
 * 钩子每次被调用时都查一遍，所以切换即时生效；已经改过的视图（浮起来的底栏、排好的输入行）要重启 QQ 才复原。
 *
 * ponytail: 面板不另开窗口 —— 挂在 QQ 的 decor 上，玻璃才录得到身后的画面。
 */
private const val KNOB = "qself-knob"
private const val SHEET = "qself-sheet"

/** 钮归属于哪条底栏：别家底栏来调 knob() 时不动它。 */
private val owners = WeakHashMap<View, View>()

fun knob(bar: View) {
    val decor = bar.rootView as? ViewGroup ?: return
    val old = decor.findViewWithTag<View>(KNOB)
    if (!bar.isShown || bar.height == 0 || decor.width == 0) { // 底栏不在这页上：钮藏起来，别侵入别的页面
        if (old != null && owners[old] === bar) old.visibility = View.GONE
        return
    }
    val dp = bar.dp
    val size = bar.height // 和胶囊同一个尺寸，顶边底边自然齐平
    val at = IntArray(2).also(bar::getLocationInWindow)
    val origin = IntArray(2).also(decor::getLocationInWindow) // decor 不一定贴着窗口原点，坐标统一以 decor 为原点
    val room = decor.width - (at[0] - origin[0]) - bar.width // 胶囊右边剩多少
    val beside = room >= (8 * dp).toInt() + size
    val lift = decor.height - (at[1] - origin[1]) - bar.height // 钮和胶囊同高，这一个数同时管顶和底
    val end = if (beside) room - (8 * dp).toInt() - size else (8 * dp).toInt()
    if (old != null) {
        owners[old] = bar
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
        // 按压反馈只由 Glass 的按压态产生（透镜凹陷 + 柔光），不叠系统涟漪
        setOnClickListener { sheet(it) }
    }
    decor.addView(knob, FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.BOTTOM).apply {
        marginEnd = end
        bottomMargin = lift
    })
    owners[knob] = bar
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

/** 开关面板：挂在 QQ 的 decor 上（不是对话框窗口）—— 玻璃要录身后的画面，得在同一个窗口树里。 */
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
        val title = TextView(ctx).apply { text = f.name; textSize = 15f; setTextColor(ink) }
        val desc = TextView(ctx).apply { text = f.desc; textSize = 11f; setTextColor(sub) }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (10 * dp).toInt()
            setPadding(p, p, p, p)
            addView(View(ctx).apply { background = mark },
                LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply { marginEnd = (10 * dp).toInt() })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(desc)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            foreground = borderlessRipple(this)
        }
        fun paint() {
            val broken = f.name in failed
            mark.state(!broken && store.getBoolean(f.name, true), broken)
            title.setTextColor(if (broken) sub else ink)
        }
        paint()
        if (f.name !in failed) view.setOnClickListener { // ✗ 是这版 QQ 里没装上，点了也没用
            store.edit().putBoolean(f.name, !store.getBoolean(f.name, true)).apply()
            // 输入栏的玻璃当场就能撤掉/贴上，不用等下一次布局事件
            if (f.name == "TG输入栏") refreshLooks(anchor.rootView)
            paint()
        }
        // 消息标签的格式长按可改：占位符 {seq} 服务器序号、{id} 客户端 ID、{time} 时分秒
        if (f.name == "消息ID和时间") view.setOnLongClickListener {
            val input = EditText(ctx).apply {
                setText(store.getString(STAMP_KEY, "{seq} · {time}"))
                hint = "{seq} · {time}"
            }
            val wrap = FrameLayout(ctx).apply {
                val m = (20 * dp).toInt()
                setPadding(m, m / 2, m, 0)
                addView(input)
            }
            AlertDialog.Builder(ctx, if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert
                else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("标签格式")
                .setView(wrap)
                .setPositiveButton("好") { _, _ -> store.edit().putString(STAMP_KEY, input.text.toString()).apply() }
                .setNegativeButton("算了", null)
                .show()
            true
        }
        return view
    }
    for (i in features.indices step 2) { // 一行两张卡
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(cell(features[i]), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (i + 1 < features.size)
            row.addView(cell(features[i + 1]), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        setPadding((14 * dp).toInt(), (18 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        addView(TextView(ctx).apply {
            text = "Qself ${BuildConfig.VERSION_NAME}"
            textSize = 18f
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
            min(decor.width - (32 * dp).toInt(), (520 * dp).toInt()),
            (decor.height - (72 * dp).toInt()), Gravity.CENTER))
    }
    decor.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}
