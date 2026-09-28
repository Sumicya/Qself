// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** QQ 的按钮留在原位执行点击；玻璃行只借它们的图和行为，关闭开关时整行可撤销。 */
private val rows = WeakHashMap<View, WeakReference<Row>>()
private const val ROW = "qself-row"

fun tgInput(strip: ViewGroup) {
    val old = rows[strip]?.get()
    if (!on("TG输入栏")) {
        old?.restore()
        rows.remove(strip)
        return
    }
    if (old?.active() == true) { old.sync(); return }
    val scope = strip.parent as? ViewGroup ?: return
    val edit = find(scope) { it is TextView && (idName(it) == "input" || it.javaClass.simpleName.contains("EditText")) } as? TextView ?: return
    val box = edit.parent as? ViewGroup ?: return
    val host = box.parent as? ViewGroup ?: return // addView 前必须从直接父容器移除
    if (generateSequence(box as View) { it.parent as? View }.any { it.tag == ROW || it === strip }) return
    val row = Row(strip, edit, box, host, find(box) { idName(it) == "send_btn" })
    rows[strip] = WeakReference(row)
    row.sync()
}

private fun find(v: View, pred: (View) -> Boolean): View? {
    if (pred(v)) return v
    if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), pred)?.let { return it }
    return null
}

private fun idName(v: View): String? =
    if (v.id == View.NO_ID) null else runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()

/** dispatchTouchEvent 能看见子按钮/编辑框的触摸，不拦截 QQ 的点击或输入。 */
private class PressLayout(ctx: Context) : LinearLayout(ctx) {
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                isPressed = e.x >= 0 && e.x < width && e.y >= 0 && e.y < height
                (background as? Glass)?.touch(e.x, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isPressed = false
        }
        return super.dispatchTouchEvent(e)
    }
}

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
    private val dp = strip.dp
    private val row = LinearLayout(strip.context)
    private val field = PressLayout(strip.context)
    private val boxLp = box.layoutParams
    private val boxHeight = boxLp.height
    private val index = host.indexOfChild(box)
    private val stripVisibility = strip.visibility
    private val slot = (generateSequence(strip as View) { it.parent as? View }
        .firstOrNull { it.parent === host } as? ViewGroup)?.takeIf { it !== box }
    private var slotHeight: Int? = null
    private val backgrounds = LinkedHashMap<View, Drawable>()
    private val hiddenImages = LinkedHashMap<View, Int>()
    private val mirrors = ArrayList<Mirror>(3)
    private var sendVisibility: Int? = null
    private var showingMic: Boolean? = null
    private val mic: Mirror?
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { swap(); clearBackgrounds() }
    }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clearBackgrounds() }

    init {
        val icons = (0 until strip.childCount).mapNotNull { i ->
            find(strip.getChildAt(i)) { it is ImageView && it.contentDescription != null } as? ImageView
        }
        fun icon(vararg keys: String) = icons.firstOrNull { i -> keys.any { i.contentDescription.toString().contains(it) } }
        val ctx = strip.context
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
        field.orientation = LinearLayout.HORIZONTAL
        field.gravity = Gravity.CENTER_VERTICAL
        field.outlineProvider = capsule(22 * dp)
        field.clipToOutline = true
        field.elevation = 3 * dp
        field.background = Glass(field, 22 * dp)
        field.isClickable = true
        field.setPadding((2 * dp).toInt(), 0, (2 * dp).toInt(), 0)

        // 不增加 box 的固定高度；外边距只给玻璃两侧，关开关后不会残留上下空槽。
        host.removeView(box)
        emoji?.view?.let(field::addView)
        field.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        more?.view?.let(field::addView)
        row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins((8 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
        })
        mic?.view?.let {
            it.outlineProvider = capsule()
            it.clipToOutline = true
            it.elevation = 3 * dp
            it.background = Glass(it)
            (it.layoutParams as LinearLayout.LayoutParams).marginEnd = (8 * dp).toInt()
            row.addView(it)
        }
        host.addView(row, index, boxLp)
        strip.visibility = View.GONE
        clearBackgrounds()
        edit.addOnLayoutChangeListener(layoutListener)
        edit.addTextChangedListener(watcher)
    }

    fun active() = row.parent === host && box.parent === field

    fun restore() {
        edit.removeTextChangedListener(watcher)
        edit.removeOnLayoutChangeListener(layoutListener)
        mic?.view?.animate()?.cancel()
        if (active()) {
            host.removeView(row)
            field.removeView(box)
            boxLp.height = boxHeight
            host.addView(box, index.coerceIn(0, host.childCount), boxLp)
        }
        strip.visibility = stripVisibility
        slotHeight?.let { h -> slot?.layoutParams?.let { it.height = h; slot.layoutParams = it } }
        hiddenImages.forEach { (v, visibility) -> v.visibility = visibility }
        backgrounds.forEach { (v, drawable) -> if (v.isAttachedToWindow) v.background = drawable }
        sendVisibility?.let { send?.visibility = it }
    }

    fun sync() {
        if (strip.visibility != View.GONE) strip.visibility = View.GONE
        clearBackgrounds()
        mirrors.forEach { it.sync() }
        swap()
        star()
        collapse()
    }

    private fun clearBackgrounds() {
        fun clear(v: View) {
            v.background?.let { if (v !in backgrounds) backgrounds[v] = it; v.background = null }
        }
        clear(edit)
        clear(box)
        for (v in listOf(host, strip.parent as? View))
            if (v != null && v.height in 1..(120 * v.dp).toInt()) clear(v)
    }

    private fun star() {
        fun go(v: View) {
            if (v === send || v === edit) return
            if (v is ImageView) {
                if (v.visibility != View.GONE) { hiddenImages.putIfAbsent(v, v.visibility); v.visibility = View.GONE }
            } else if (v is ViewGroup) for (i in 0 until v.childCount) go(v.getChildAt(i))
        }
        go(box)
    }

    /** 只压图标带所在的空槽，绝不压空聊天列表。 */
    private fun collapse() {
        val v = slot ?: return
        val lp = v.layoutParams ?: return
        if (blank(v)) {
            if (slotHeight == null && v.height > 0) {
                slotHeight = lp.height
                lp.height = 0
                v.layoutParams = lp
            }
        } else {
            slotHeight?.let { lp.height = it; v.layoutParams = lp }
            slotHeight = null
        }
    }

    private fun blank(v: View): Boolean = when {
        v.visibility == View.GONE -> true
        v is ViewGroup -> (0 until v.childCount).all { blank(v.getChildAt(it)) }
        else -> false
    }

    /** QQ 自己的发送按钮保留，镜像麦克风以缩放淡入/淡出切换。 */
    private fun swap() {
        val m = mic ?: return
        val s = send ?: return
        val show = edit.text.isNullOrEmpty()
        if (show) {
            if (sendVisibility == null) sendVisibility = s.visibility
            if (s.visibility != View.GONE) s.visibility = View.GONE
        } else {
            sendVisibility?.let { if (s.visibility != it) s.visibility = it }
            sendVisibility = null
        }
        if (showingMic == show) return
        showingMic = show
        val v = m.view
        v.animate().cancel()
        if (show) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.scaleX = 0.78f
            v.scaleY = 0.78f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(OvershootInterpolator(1.2f)).start()
        } else {
            v.animate().alpha(0f).scaleX(0.78f).scaleY(0.78f).setDuration(140)
                .withEndAction { if (showingMic == false) v.visibility = View.GONE }.start()
        }
    }
}
