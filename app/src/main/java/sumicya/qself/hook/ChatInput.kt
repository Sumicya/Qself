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
import java.util.WeakHashMap

/**
 * Telegram-style chat input: one row, [emoji] [text] [attach] [more] [mic ⇄ send].
 *
 * QQ's icon strip under the input box (PanelIconLinearLayout) is hidden. Its buttons stay alive,
 * and the new row's buttons are mirrors of them: same drawables (so emoji ⇄ keyboard state follows),
 * and a tap calls performClick() on QQ's original, so every panel still behaves natively.
 * The input ConstraintLayout itself is only re-parented into the row, and put back on disable.
 */
object TgInputBar : ViewRule("tg_input_bar") {
    const val ROW_TAG = "qself-tg-input"
    private val rows = WeakHashMap<View, Row>()

    override fun match(v: View): Boolean {
        if (!v.javaClass.name.endsWith("PanelIconLinearLayout")) return false
        val row = rows[v] ?: Row.build(v as ViewGroup)?.also { rows[v] = it } ?: return false
        row.sync()
        return true
    }

    override fun uninstall() {
        rows.values.forEach { runCatching { it.restore() } }
        rows.clear()
        super.uninstall()
    }

    private fun find(v: View, pred: (View) -> Boolean): View? {
        if (pred(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), pred)?.let { return it }
        return null
    }

    private fun idName(v: View): String? =
        if (v.id == View.NO_ID) null else runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()

    private class Mirror(val src: ImageView, val view: ImageView) {
        var shown: Drawable? = null
        fun sync() {
            val d = src.drawable
            if (d !== shown) {
                shown = d
                view.setImageDrawable(d?.constantState?.newDrawable(src.resources)?.mutate() ?: d)
                view.imageTintList = src.imageTintList
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
    ) {
        private var sendHiddenFrom: Int? = null
        private val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = swap()
        }

        init { edit.addTextChangedListener(watcher) }

        fun sync() {
            mirrors.forEach { it.sync() }
            swap()
            collapse()
        }

        /** Original height of host slots we squeezed to 0. */
        private val squeezed = HashMap<View, Int>()

        /**
         * With the icon strip gone QQ keeps its space as an empty slot above the input.
         * Any sibling slot with nothing visible inside is squeezed to 0; it comes back
         * the moment QQ puts something there (reply preview and the like).
         */
        private fun collapse() {
            for (i in 0 until host.childCount) {
                val c = host.getChildAt(i)
                if (c === row || c !is ViewGroup) continue
                val lp = c.layoutParams ?: continue
                if (blank(c)) {
                    if (c !in squeezed && c.height > 0) {
                        squeezed[c] = lp.height
                        lp.height = 0
                        c.layoutParams = lp
                    }
                } else squeezed.remove(c)?.let { lp.height = it; c.layoutParams = lp }
            }
        }

        private fun blank(v: View): Boolean = when {
            v.visibility == View.GONE -> true
            v is ViewGroup -> (0 until v.childCount).all { blank(v.getChildAt(it)) }
            else -> false
        }

        /** Empty box shows the mic, typing shows QQ's own send button. */
        private fun swap() {
            val m = mic ?: return
            val s = send ?: return
            val empty = edit.text.isNullOrEmpty()
            if (empty) {
                if (sendHiddenFrom == null) sendHiddenFrom = s.visibility
                if (s.visibility != View.GONE) s.visibility = View.GONE
                if (m.view.visibility != View.VISIBLE) m.view.visibility = View.VISIBLE
            } else {
                sendHiddenFrom?.let { if (s.visibility != it) s.visibility = it }
                sendHiddenFrom = null
                if (m.view.visibility != View.GONE) m.view.visibility = View.GONE
            }
        }

        fun restore() {
            edit.removeTextChangedListener(watcher)
            squeezed.forEach { (c, h) -> c.layoutParams?.let { it.height = h; c.layoutParams = it } }
            squeezed.clear()
            sendHiddenFrom?.let { send?.visibility = it }
            row.removeView(input)
            host.removeView(row)
            host.addView(input, index.coerceAtMost(host.childCount), inputLp)
        }

        companion object {
            fun build(bar: ViewGroup): Row? {
                val icons = (0 until bar.childCount).mapNotNull { i ->
                    find(bar.getChildAt(i)) { it is ImageView && it.contentDescription != null } as ImageView?
                }
                fun icon(vararg keys: String) = icons.firstOrNull { i -> keys.any { i.contentDescription.toString().contains(it) } }
                val emoji = icon("表情") ?: return null
                val more = icon("更多", "加号")
                val album = icon("相册", "图片")
                val voice = icon("语音")

                // The input box lives next to the strip, under the same container.
                val scope = bar.parent as? ViewGroup ?: return null
                val edit = find(scope) { it is TextView && (idName(it) == "input" || it.javaClass.simpleName.contains("EditText")) }
                    as TextView? ?: return null
                val input = edit.parent as? ViewGroup ?: return null
                val host = input.parent as? LinearLayout ?: return null
                val send = find(input) { idName(it) == "send_btn" }

                val ctx = bar.context
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    tag = ROW_TAG
                }
                val dp = ctx.resources.displayMetrics.density
                val ripple = TypedValue().let {
                    if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)) it.resourceId else 0
                }
                fun mirror(src: ImageView): Mirror {
                    val v = ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        contentDescription = src.contentDescription
                        val p = (9 * dp).toInt()
                        setPadding(p, p, p, p)
                        if (ripple != 0) setBackgroundResource(ripple)
                        setOnClickListener { src.performClick() }
                        setOnLongClickListener { src.performLongClick() }
                        layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
                    }
                    return Mirror(src, v).also { it.sync() }
                }

                val index = host.indexOfChild(input)
                val inputLp = input.layoutParams
                val mirrors = mutableListOf<Mirror>()
                val emojiM = mirror(emoji).also { mirrors += it }
                val albumM = album?.let(::mirror)?.also { mirrors += it }
                val moreM = more?.let(::mirror)?.also { mirrors += it }
                val micM = voice?.let(::mirror)?.also { mirrors += it }

                host.removeView(input)
                row.addView(emojiM.view.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = (6 * dp).toInt() })
                row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                albumM?.let { row.addView(it.view) }
                moreM?.let { row.addView(it.view) }
                micM?.let { row.addView(it.view.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = (6 * dp).toInt() }) }
                // The row gets its own params: glass margins on it must never leak back into QQ's box.
                val rowLp = (inputLp as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
                    ?: LinearLayout.LayoutParams(inputLp)
                host.addView(row, index, rowLp)
                return Row(bar, input, host, index, inputLp, edit, send, row, mirrors, micM)
            }
        }
    }
}
