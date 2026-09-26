// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * TG 式输入栏：一行 [表情] [输入框] [相册] [+] [麦克风 ⇄ 发送]。
 *
 * QQ 输入框下面那条图标带（PanelIconLinearLayout）藏起来，但它的按钮还活着：新行里的按钮是
 * 它们的镜子 —— 同一个 drawable，所以表情 ⇄ 键盘的状态跟着走 —— 点下去调原按钮的
 * performClick()，每个面板还是 QQ 自己的行为。输入框本身只是被挪进这一行，关掉开关放回去。
 *
 * 同一块输入区只做一行：宿主变了先把旧行撤掉，绝不让第二行留在屏幕上。
 */
object TgInput : Rule("tg_input_bar") {

    const val ROW_TAG = "qself-tg-input"

    private val rows = Tagged<Row>()

    override fun match(v: View): Boolean {
        if (!v.javaClass.name.endsWith("PanelIconLinearLayout")) return false
        val strip = v
        val scope = strip.parent as? ViewGroup ?: return false
        val edit = find(scope) {
            it is TextView && (idName(it) == "input" || it.javaClass.simpleName.contains("EditText"))
        } as TextView? ?: return false
        val box = edit.parent as? ViewGroup ?: return false
        val host = box.parent as? ViewGroup ?: return false
        // 别的输入区上的行先撤掉，只留这一块宿主的一行。
        rows.each().filter { it.host !== host }.forEach(rows::drop)
        val row = rows[strip] ?: rows.put(Row(strip, edit, box, host, find(box) { idName(it) == "send_btn" }))
        row.sync()
        return false
    }

    override fun uninstall() {
        rows.dropAll()
        super.uninstall()
    }

    private fun find(v: View, pred: (View) -> Boolean): View? {
        if (pred(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), pred)?.let { return it }
        return null
    }

    private fun idName(v: View): String? =
        if (v.id == View.NO_ID) null else runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()

    /** 我们自己那行里的按钮：显示跟 QQ 原按钮一样的图，点击转交给它。 */
    private class Mirror(val from: ImageView, val view: ImageView) {
        private var shown: Drawable? = null

        fun sync() {
            val d = from.drawable
            if (d !== shown) {
                shown = d
                view.setImageDrawable(d?.constantState?.newDrawable(from.resources)?.mutate() ?: d)
                view.imageTintList = from.imageTintList
            }
        }
    }

    private class Row(
        val strip: View,
        val edit: TextView,
        val box: ViewGroup,
        val host: ViewGroup,
        val send: View?,
    ) : Undo {

        override val anchor: View get() = strip

        private val stripVisibility = strip.visibility
        private val index = host.indexOfChild(box)
        private val boxLp = box.layoutParams
        private val row = LinearLayout(strip.context)
        private val mirrors = ArrayList<Mirror>(4)
        private val squeezed = HashMap<View, Int>()
        private var sendVisibility: Int? = null

        private val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = swap()
        }

        private val mic: Mirror?

        init {
            val icons = (0 until strip.childCount).mapNotNull { i ->
                find(strip.getChildAt(i)) { it is ImageView && it.contentDescription != null } as ImageView?
            }
            fun icon(vararg keys: String) =
                icons.firstOrNull { i -> keys.any { i.contentDescription.toString().contains(it) } }

            val ctx = strip.context
            val dp = ctx.resources.displayMetrics.density
            val ripple = TypedValue().let {
                if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)) {
                    it.resourceId
                } else {
                    0
                }
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
                return Mirror(from, view).also { it.sync() }
            }

            val emoji = icon("表情")?.let(::mirror)?.also { mirrors += it }
            val album = icon("相册", "图片")?.let(::mirror)?.also { mirrors += it }
            val more = icon("更多", "加号")?.let(::mirror)?.also { mirrors += it }
            mic = icon("语音")?.let(::mirror)?.also { mirrors += it }

            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.tag = ROW_TAG

            // 图标带藏起来。
            strip.visibility = View.GONE

            // 输入框搬进新行：emoji | box | 相册 | + | 麦克风/发送。
            host.removeView(box)
            emoji?.view?.let {
                (it.layoutParams as LinearLayout.LayoutParams).marginStart = (6 * dp).toInt()
                row.addView(it)
            }
            row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            album?.view?.let(row::addView)
            more?.view?.let(row::addView)
            mic?.view?.let {
                (it.layoutParams as LinearLayout.LayoutParams).marginEnd = (6 * dp).toInt()
                row.addView(it)
            }
            // 这一行有自己的一份 params：玻璃给它留的边距不能漏回 QQ 的输入框。
            val rowLp = (boxLp as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
                ?: LinearLayout.LayoutParams(boxLp)
            host.addView(row, index.coerceIn(0, host.childCount), rowLp)

            edit.addTextChangedListener(watcher)
        }

        fun sync() {
            mirrors.forEach { it.sync() }
            swap()
            collapse()
        }

        /**
         * 图标带没了，QQ 还留着那块空位。任何里面什么都没有的兄弟槽位压到 0 高，
         * QQ 一往里放东西（回复预览之类）它自己就长回来。
         */
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

        override fun undo() {
            edit.removeTextChangedListener(watcher)
            squeezed.forEach { (child, height) ->
                child.layoutParams?.let { it.height = height; child.layoutParams = it }
            }
            squeezed.clear()
            sendVisibility?.let { send?.visibility = it }
            row.removeView(box)
            host.removeView(row)
            host.addView(box, index.coerceIn(0, host.childCount), boxLp)
            strip.visibility = stripVisibility
        }
    }
}
