// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.util.TypedValue
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 开关：主页那颗玻璃圆钮 —— 和胶囊同高、顶底对齐，胶囊右边放得下就跟胶囊并排。
 * 底栏藏起来的页面（频道那些）钮也跟着藏，不留一颗钮飘在别的页面上。
 * 点开是瓦片列表：一行一块圆角磁贴，右边一个状态符号 —— ✓ 开着、– 没开、✗ 这版 QQ 没装上（不让点）。
 * 状态存在 QQ 自己的 SharedPreferences「qself」里，钩子每次被调用时都查一遍，所以切换即时生效；
 * 已经改过的视图（浮起来的底栏、排好的输入行）要重启 QQ 才复原。
 */
private const val KNOB = "qself-knob"

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
    val beside = room >= (12 * dp).toInt() + size
    val lift = decor.height - at[1] - bar.height // 钮和胶囊同高，这一个数同时管顶和底
    val end = if (beside) room - (12 * dp).toInt() - size else (12 * dp).toInt()
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
        foreground = TypedValue().let {
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true))
                context.getDrawable(it.resourceId) else null
        }
        setOnClickListener { dialog(it) }
        viewTreeObserver.addOnScrollChangedListener { invalidate() }
        viewTreeObserver.addOnGlobalLayoutListener { invalidate() }
    }
    decor.addView(knob, FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.BOTTOM).apply {
        marginEnd = end
        bottomMargin = lift
    })
}

private fun dialog(anchor: View) {
    val store = store() ?: return
    val night = night()
    val dp = anchor.dp
    val ink = if (night) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
    val rows = LinearLayout(anchor.context).apply {
        orientation = LinearLayout.VERTICAL
        val v = (8 * dp).toInt()
        setPadding(v, v, v, v)
    }
    for ((name) in features) {
        val mark = TextView(anchor.context).apply { textSize = 17f }
        val row = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val h = (13 * dp).toInt()
            setPadding((16 * dp).toInt(), h, (14 * dp).toInt(), h)
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(if (night) 0x14FFFFFF else 0x0E000000)
            }
            addView(TextView(anchor.context).apply { text = name; textSize = 16f; setTextColor(ink) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mark)
        }
        fun paint() {
            val broken = name in failed
            val on = store.getBoolean(name, true)
            mark.text = if (broken) "✗" else if (on) "✓" else "–"
            mark.setTextColor(if (broken) 0xFFFF453A.toInt() else if (on) 0xFF30D158.toInt() else 0xFF8E8E93.toInt())
        }
        paint()
        if (name !in failed) row.setOnClickListener { // ✗ 是这版 QQ 里没装上，点了也没用
            store.edit().putBoolean(name, !store.getBoolean(name, true)).apply()
            paint()
        }
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (6 * dp).toInt() })
    }
    val title = TextView(anchor.context).apply {
        text = "Qself ${BuildConfig.VERSION_NAME}"
        gravity = Gravity.CENTER
        textSize = 18f
        setTextColor(ink)
        setPadding(0, (18 * dp).toInt(), 0, (6 * dp).toInt())
    }
    AlertDialog.Builder(anchor.context, if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        .setCustomTitle(title)
        .setView(ScrollView(anchor.context).apply { addView(rows) })
        .setNeutralButton("重启 QQ") { _, _ -> android.os.Process.killProcess(android.os.Process.myPid()) }
        .setPositiveButton("好", null)
        .show()
}
