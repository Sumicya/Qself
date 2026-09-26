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
 * Telegram 式的输入栏：一行 [表情] [输入框] [相册] [+ ⇄ 语音]。
 *
 * QQ 输入框下面那条图标带（PanelIconLinearLayout）被藏了，但按钮还活着：新行里的按钮是它们的
 * 镜像 —— 同一个 drawable，所以表情 ⇄ 键盘的状态跟着走 —— 点了就调原按钮的 performClick()，
 * 每个面板还是 QQ 自己的行为。输入框本身只是被挪进这一行，关掉开关就放回去。
 */
object InputBar {

    const val ROW_TAG = "qself-tg-input"

    object Tg : ViewRule("tg_input_bar") {
        private val rows = Decor<Row>()

        override fun match(v: View): Boolean {
            if (!v.javaClass.name.endsWith("PanelIconLinearLayout")) return false
            val row = rows[v] ?: Row.build(v as ViewGroup)?.let(rows::put) ?: return false
            row.sync()
            hits++
            return false
        }

        override fun uninstall() {
            rows.dropAll()
            super.uninstall()
        }
    }

    private fun find(v: View, pred: (View) -> Boolean): View? {
        if (pred(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), pred)?.let { return it }
        return null
    }

    private fun idName(v: View): String? =
        if (v.id == View.NO_ID) null else runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()

    /** 我们自己那一行里的按钮：显示跟 QQ 原按钮一样的图，点击转给它。 */
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
        val bar: ViewGroup,
        val input: ViewGroup,
        val host: ViewGroup,
        val index: Int,
        val inputLp: ViewGroup.LayoutParams,
        val edit: TextView,
        val send: View?,
        val row: LinearLayout,
        val mirrors: List<Mirror>,
        val mic: Mirror?,
    ) : DecorState {

        override val anchor: View get() = bar

        private var sendVisibility: Int? = null
        private val squeezed = HashMap<View, Int>()

        private val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = swap()
        }

        init {
            edit.addTextChangedListener(watcher)
        }

        fun sync() {
            mirrors.forEach { it.sync() }
            swap()
            collapse()
        }

        /**
         * 图标带藏起来后 QQ 还留着那块空位。任何里面什么都没有的兄弟槽位压到 0 高，
         * QQ 一往里放东西（比如回复预览）它自己就长回来。
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

        /** 空输入框显示麦克风，一打字就换成 QQ 自己的发送按钮。 */
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
            squeezed.forEach { (child, height) -> child.layoutParams?.let { it.height = height; child.layoutParams = it } }
            squeezed.clear()
            sendVisibility?.let { send?.visibility = it }
            row.removeView(input)
            host.removeView(row)
            host.addView(input, index.coerceAtMost(host.childCount), inputLp)
        }

        companion object {
            fun build(bar: ViewGroup): Row? {
                val icons = (0 until bar.childCount).mapNotNull { i ->
                    find(bar.getChildAt(i)) { it is ImageView && it.contentDescription != null } as ImageView?
                }
                fun icon(vararg keys: String) =
                    icons.firstOrNull { i -> keys.any { i.contentDescription.toString().contains(it) } }

                val emoji = icon("表情") ?: return null
                val album = icon("相册", "图片")
                val more = icon("更多", "加号")
                val voice = icon("语音")

                val scope = bar.parent as? ViewGroup ?: return null
                val edit = find(scope) { it is TextView && (idName(it) == "input" || it.javaClass.simpleName.contains("EditText")) } as TextView?
                    ?: return null
                val input = edit.parent as? ViewGroup ?: return null
                val host = input.parent as? LinearLayout ?: return null
                val send = find(input) { idName(it) == "send_btn" }

                val ctx = bar.context
                val dp = ctx.resources.displayMetrics.density
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    tag = ROW_TAG
                }
                val ripple = TypedValue().let {
                    if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)) it.resourceId else 0
                }
                fun mirror(from: ImageView): Mirror {
                    val v = ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        contentDescription = from.contentDescription
                        val p = (9 * dp).toInt()
                        setPadding(p, p, p, p)
                        if (ripple != 0) setBackgroundResource(ripple)
                        setOnClickListener { from.performClick() }
                        setOnLongClickListener { from.performLongClick() }
                        layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
                    }
                    return Mirror(from, v).also { it.sync() }
                }

                val index = host.indexOfChild(input)
                val inputLp = input.layoutParams
                val mirrors = mutableListOf<Mirror>()
                val emojiMirror = mirror(emoji).also { mirrors += it }
                val albumMirror = album?.let(::mirror)?.also { mirrors += it }
                val moreMirror = more?.let(::mirror)?.also { mirrors += it }
                val micMirror = voice?.let(::mirror)?.also { mirrors += it }

                host.removeView(input)
                row.addView(emojiMirror.view.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = (6 * dp).toInt() })
                row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                albumMirror?.let { row.addView(it.view) }
                moreMirror?.let { row.addView(it.view) }
                micMirror?.let { row.addView(it.view.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = (6 * dp).toInt() }) }
                // 这一行有自己的一份 params：玻璃给它留的边距绝不能漏回 QQ 的输入框。
                val rowLp = (inputLp as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
                    ?: LinearLayout.LayoutParams(inputLp)
                host.addView(row, index, rowLp)

                return Row(bar, input, host, index, inputLp, edit, send, row, mirrors, micMirror)
            }
        }
    }
}
