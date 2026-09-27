// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 开关：主页那颗玻璃圆钮 —— 胶囊右边放得下就跟胶囊并排（iOS 那颗搜索钮的位置），放不下就挪到胶囊右上方。
 * 点开是系统 AlertDialog 的多选框。
 * 状态存在 QQ 自己的 SharedPreferences「qself」里，钩子每次被调用时都查一遍，所以勾选即时生效；
 * 已经改过的视图（浮起来的底栏、排好的输入行）要重启 QQ 才复原。
 */
private const val KNOB = "qself-knob"

fun knob(bar: View) {
    val decor = bar.rootView as? ViewGroup ?: return
    val dp = bar.dp
    val size = (44 * dp).toInt()
    val at = IntArray(2).also(bar::getLocationInWindow)
    val room = decor.width - at[0] - bar.width // 胶囊右边剩多少
    val beside = room >= (12 + 44 + 8) * dp
    val lift = if (beside) decor.height - at[1] - bar.height + (bar.height - size) / 2 else decor.height - at[1] + (12 * dp).toInt()
    val end = if (beside) room - (12 * dp).toInt() - size else (16 * dp).toInt()
    decor.findViewWithTag<View>(KNOB)?.let { old ->
        (old.layoutParams as? ViewGroup.MarginLayoutParams)?.takeIf { it.bottomMargin != lift || it.marginEnd != end }
            ?.let { it.bottomMargin = lift; it.marginEnd = end; old.layoutParams = it }
        return
    }
    val knob = ImageView(bar.context).apply {
        tag = KNOB
        contentDescription = "Qself"
        setImageResource(android.R.drawable.ic_menu_preferences)
        imageTintList = ColorStateList.valueOf(
            monet(night(), 0xFF) ?: if (night()) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt())
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = (10 * dp).toInt()
        setPadding(p, p, p, p)
        outlineProvider = capsule()
        clipToOutline = true
        elevation = 6 * dp
        background = Glass(this)
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
    val names = features.map { it.first }.toTypedArray()
    val state = BooleanArray(names.size) { store.getBoolean(names[it], true) }
    val theme = if (night()) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    AlertDialog.Builder(anchor.context, theme)
        .setTitle("Qself ${BuildConfig.VERSION_NAME}")
        .setMultiChoiceItems(names, state) { _, i, checked -> store.edit().putBoolean(names[i], checked).apply() }
        .setNeutralButton("重启 QQ") { _, _ -> android.os.Process.killProcess(android.os.Process.myPid()) }
        .setPositiveButton("好", null)
        .show()
}
