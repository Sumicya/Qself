// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * TG 式输入栏：一行 [表情] [输入框] [相册] [+] [麦克风 ⇄ 发送]。
 *
 * QQ 输入框下面那条图标带（PanelIconLinearLayout）藏起来，但它的按钮还活着：新行里的按钮是
 * 它们的镜子 —— 同一个 drawable，所以表情 ⇄ 键盘的状态跟着走 —— 点下去调原按钮的
 * performClick()，每个面板还是 QQ 自己的行为。输入框本身只是被挪进这一行。
 */
private val rows = WeakHashMap<View, Row>()

private const val ROW = "qself-row"

fun tgInput(strip: ViewGroup) {
    rows[strip]?.let { it.sync(); return }
    val scope = strip.parent as? ViewGroup ?: return
    val edit = find(scope) { it is TextView && (idName(it) == "input" || it.javaClass.simpleName.contains("EditText")) } as? TextView ?: return
    val box = edit.parent as? ViewGroup ?: return
    val host = box.parent as? ViewGroup ?: return
    if (host.tag == ROW) return // 热重载前的上一代已经排好了这一行
    rows[strip] = Row(strip, edit, box, host, find(box) { idName(it) == "send_btn" }).also { it.sync() }
}

private fun find(v: View, pred: (View) -> Boolean): View? {
    if (pred(v)) return v
    if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), pred)?.let { return it }
    return null
}

private fun idName(v: View): String? =
    if (v.id == View.NO_ID) null else runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()

/** 我们这行里的按钮：显示跟 QQ 原按钮一样的图，点击转交给它。 */
private class Mirror(val from: ImageView, val view: ImageView) {
    private var shown: Any? = null

    fun sync() {
        val d = from.drawable
        if (d !== shown) {
            shown = d
            view.setImageDrawable(d?.constantState?.newDrawable(from.resources)?.mutate() ?: d)
            view.imageTintList = from.imageTintList
        }
    }
}

private class Row(val strip: ViewGroup, val edit: TextView, val box: ViewGroup, val host: ViewGroup, val send: View?) {
    private val row = LinearLayout(strip.context)
    private val mirrors = ArrayList<Mirror>(4)
    private val squeezed = HashMap<View, Int>()
    private var sendVisibility: Int? = null
    private val mic: Mirror?

    init {
        val icons = (0 until strip.childCount).mapNotNull { i ->
            find(strip.getChildAt(i)) { it is ImageView && it.contentDescription != null } as? ImageView
        }
        fun icon(vararg keys: String) = icons.firstOrNull { i -> keys.any { i.contentDescription.toString().contains(it) } }

        val ctx = strip.context
        val dp = strip.dp
        val ripple = TypedValue().let {
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)) it.resourceId else 0
        }
        fun mirror(from: ImageView): Mirror {
            val view = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = from.contentDescription
                val p = (9 * dp).toInt()
                setPadding(p, p, p, p)
                if (ripple != 0) setBackgroundResource(ripple)
                setOnClickListener { from.performClick() }
                setOnLongClickListener { from.performLongClick() }
                layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
            }
            return Mirror(from, view).also { it.sync(); mirrors += it }
        }

        val emoji = icon("表情")?.let(::mirror)
        val album = icon("相册", "图片")?.let(::mirror)
        val more = icon("更多", "加号")?.let(::mirror)
        mic = icon("语音")?.let(::mirror)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.tag = ROW
        strip.visibility = View.GONE

        // 输入框搬进新行：表情 | 输入框 | 相册 | + | 麦克风/发送。
        val index = host.indexOfChild(box)
        val boxLp = box.layoutParams
        host.removeView(box)
        emoji?.view?.let { (it.layoutParams as LinearLayout.LayoutParams).marginStart = (6 * dp).toInt(); row.addView(it) }
        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        album?.view?.let(row::addView)
        more?.view?.let(row::addView)
        mic?.view?.let { (it.layoutParams as LinearLayout.LayoutParams).marginEnd = (6 * dp).toInt(); row.addView(it) }
        host.addView(row, index.coerceIn(0, host.childCount), boxLp)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = swap()
        })
    }

    fun sync() {
        mirrors.forEach { it.sync() }
        swap()
        collapse()
    }

    /** 图标带没了，QQ 还留着那块空位。里面什么都没有的兄弟槽位压到 0 高；QQ 一往里放东西它自己长回来。 */
    private fun collapse() {
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child === row || child !is ViewGroup) continue
            val lp = child.layoutParams ?: continue
            if (blank(child)) {
                if (child !in squeezed && child.height > 0) {
                    squeezed[child] = lp.height
                    lp.height = 0
                    child.layoutParams = lp
                }
            } else {
                squeezed.remove(child)?.let { lp.height = it; child.layoutParams = lp }
            }
        }
    }

    private fun blank(v: View): Boolean = when {
        v.visibility == View.GONE -> true
        v is ViewGroup -> (0 until v.childCount).all { blank(v.getChildAt(it)) }
        else -> false
    }

    /** 空输入框显麦克风，一打字就换成 QQ 自己的发送按钮。 */
    private fun swap() {
        val m = mic ?: return
        val s = send ?: return
        if (edit.text.isNullOrEmpty()) {
            if (sendVisibility == null) sendVisibility = s.visibility
            if (s.visibility != View.GONE) s.visibility = View.GONE
            if (m.view.visibility != View.VISIBLE) m.view.visibility = View.VISIBLE
        } else {
            sendVisibility?.let { if (s.visibility != it) s.visibility = it }
            sendVisibility = null
            if (m.view.visibility != View.GONE) m.view.visibility = View.GONE
        }
    }
}
