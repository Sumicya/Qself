// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 开关入口：主页那颗玻璃圆钮 —— 和胶囊同高、顶底对齐，胶囊右边放得下就跟胶囊并排。
 * 底栏藏起来的页面（频道那些）钮也跟着藏，不留一颗钮飘在别的页面上。
 * 点开是系统的多选对话框，一行一个开关，勾选即写盘。状态存在 QQ 自己的 SharedPreferences「qself」里，
 * 受开关管的钩子每次被调用时都查一遍，所以切换即时生效；已经改过的视图（浮起来的底栏、排好的输入行）要重启 QQ 才复原。
 *
 * ponytail: 面板不自己画 —— 对话框是独立窗口，录不到宿主身后的画面，玻璃留在底栏和圆钮上才有意义；
 * 「这版 QQ 没装上」的 ✗ 看 `logcat -s Qself` 那一行。
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
        viewTreeObserver.addOnScrollChangedListener { invalidate() }
        viewTreeObserver.addOnGlobalLayoutListener { invalidate() }
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

/** 开关面板 = 系统多选对话框：一行一个，勾选即写盘；「重启 QQ」给已经改过的视图用。 */
private fun sheet(anchor: View) {
    val store = store() ?: return
    val names = knobs.toTypedArray()
    val theme = if (night()) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    AlertDialog.Builder(anchor.context, theme)
        .setTitle("Qself ${BuildConfig.VERSION_NAME}")
        .setMultiChoiceItems(names, names.map { store.getBoolean(it, true) }.toBooleanArray()) { _, i, on ->
            store.edit().putBoolean(names[i], on).apply()
        }
        .setNeutralButton("重启 QQ") { _, _ -> android.os.Process.killProcess(android.os.Process.myPid()) }
        .setNegativeButton("好", null)
        .show()
}
