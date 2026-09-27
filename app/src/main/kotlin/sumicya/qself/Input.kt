// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Telegram（iOS 26）式输入栏：一颗悬浮的玻璃胶囊 [表情 · QQ 的输入框 · +]，右边一颗独立的玻璃圆钮（麦克风）；
 * 一打字 QQ 自己的「发送」块在框里亮出来、麦克风收起。输入栏的底色去掉，胶囊直接浮在聊天背景上。
 *
 * QQ 输入框下面那条图标带（PanelIconLinearLayout）藏起来，但它的按钮还活着：新行里的按钮是
 * 它们的镜子 —— 同一个 drawable，所以表情 ⇄ 键盘的状态跟着走 —— 点下去调原按钮的
 * performClick()，每个面板还是 QQ 自己的行为（相册在 + 里，TG 也没有单独的相册钮）。
 */
private val rows = WeakHashMap<View, Row>()

private const val ROW = "qself-row"

fun tgInput(strip: ViewGroup) {
    rows[strip]?.let { it.sync(); return }
    val scope = strip.parent as? ViewGroup ?: return
    val edit = find(scope) { it is TextView && (idName(it) == "input" || it.javaClass.simpleName.contains("EditText")) } as? TextView ?: return
    val box = edit.parent as? ViewGroup ?: return
    // 只在输入框的直接父容器原位替换；公共祖先可能不是 box 的 parent，removeView 会静默失败。
    val host = box.parent as? ViewGroup ?: return
    if (generateSequence(box as View) { it.parent as? View }.any { it.tag == ROW || it === strip }) return
    // 热重载前已排过的行不再重包；strip 包住 box 时隐藏它会连输入框一起藏掉。
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
        fun mirror(from: ImageView): Mirror {
            val view = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = from.contentDescription
                val p = (9 * dp).toInt()
                setPadding(p, p, p, p)
                background = borderlessRipple(this)
                setOnClickListener { from.performClick() }
                setOnLongClickListener { from.performLongClick() }
                layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
            }
            return Mirror(from, view).also { it.sync(); mirrors += it }
        }

        val emoji = icon("表情")?.let(::mirror)
        val more = icon("更多", "加号")?.let(::mirror)
        mic = icon("语音")?.let(::mirror)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.tag = ROW
        strip.visibility = View.GONE

        // 玻璃胶囊就是输入框：[表情][QQ 的框（去掉自己的底色）][+]；多行时圆角封顶 22dp。
        val field = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            outlineProvider = capsule(22 * dp)
            clipToOutline = true
            background = Glass(this, 22 * dp)
            isClickable = true // 空的地方按下去也有按压反馈（玻璃凹+柔光）；按钮在子视图里照旧先接触摸
            setPadding((2 * dp).toInt(), 0, (2 * dp).toInt(), 0)
        }
        val index = host.indexOfChild(box)
        val boxLp = box.layoutParams.also { if (it.height > 0) it.height += (12 * dp).toInt() } // 胶囊上下各留 6dp
        host.removeView(box)
        edit.background = null
        box.background = null // 框自己那层圆角底还留着就会在玻璃胶囊里挡一道
        emoji?.view?.let(field::addView)
        field.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        more?.view?.let(field::addView)
        row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins((8 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        })
        mic?.view?.let {
            it.outlineProvider = capsule()
            it.clipToOutline = true
            it.background = Glass(it)
            (it.layoutParams as LinearLayout.LayoutParams).marginEnd = (8 * dp).toInt()
            row.addView(it)
        }
        host.addView(row, index.coerceIn(0, host.childCount), boxLp)
        // 输入栏自己的底色去掉，胶囊才是浮在聊天背景上的。
        generateSequence(host as View) { it.parent as? View }.take(2).forEach { it.background = null }

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = swap()
        })
    }

    fun sync() {
        mirrors.forEach { it.sync() }
        swap()
        star()
        collapse()
    }

    /** QQ 塞在输入框右端的 AI 星星之类的图标钮：框里除了「发送」只留文字。 */
    private fun star() {
        fun go(v: View) {
            if (v === send || v === edit) return
            if (v is ImageView) { if (v.visibility != View.GONE) v.visibility = View.GONE } else if (v is ViewGroup) for (i in 0 until v.childCount) go(v.getChildAt(i))
        }
        go(box)
    }

    /** 图标带没了，QQ 还留着那块空位。里面什么都没有的兄弟槽位压到 0 高；QQ 一往里放东西它自己长回来。 */
    private fun collapse() {
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child === row || child !is ViewGroup ||
                generateSequence(strip as View) { it.parent as? View }.none { it === child }) continue
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
