// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ClickableSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import java.lang.ref.WeakReference
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.WeakHashMap

internal const val TIME_PATTERN = "message_time_pattern"
internal const val AVATAR_RADIUS = "avatar_radius_percent"
internal fun validTimePattern(pattern: String): Boolean = pattern.length in 1..80 && pattern.none { it.isISOControl() } && runCatching {
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.EPOCH.atZone(ZoneId.systemDefault()))
}.isSuccess
internal fun messageTime(seconds: Long, pattern: String, zone: ZoneId = ZoneId.systemDefault()): String =
    if (seconds <= 0) "" else runCatching {
        val fmt = if (validTimePattern(pattern)) pattern else "HH:mm"
        DateTimeFormatter.ofPattern(fmt, Locale.getDefault()).format(Instant.ofEpochSecond(seconds).atZone(zone)).take(120)
    }.getOrDefault("")
internal fun avatarCorner(size: Int, percent: Int): Int = (size.coerceAtLeast(0) * percent.coerceIn(0, 50) / 100f).toInt()

private fun messageRecord(item: Any?): Any? = item?.javaClass?.getMethod("getMsgRecord")?.invoke(item)
private fun textViews(view: View): Sequence<TextView> = sequence {
    if (view is TextView && view.javaClass.name == "com.tencent.qqnt.aio.widget.AIOMsgTextView") yield(view)
    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) yieldAll(textViews(view.getChildAt(i)))
}
private const val TEXT_COMPONENT = "com.tencent.mobileqq.aio.msglist.holder.component.text.AIOTextContentComponent"
private fun textBind() = cls(TEXT_COMPONENT).getDeclaredMethod("d1", Integer.TYPE,
    cls("com.tencent.aio.data.msglist.a"), List::class.java)
private fun textRoot() = cls(TEXT_COMPONENT).getDeclaredMethod("f1")

private val stamps = WeakHashMap<TextView, MessageStamp>()
private class MessageStamp(view: TextView) : Drawable(), View.OnLayoutChangeListener {
    private val view = WeakReference(view)
    private val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
    private var minimum = view.minimumWidth
    private val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    var seconds = 0L
    private var label = ""
    init { view.addOnLayoutChangeListener(this) }
    fun captureNative() {
        view.get()?.let {
            padding[0] = it.paddingLeft; padding[1] = it.paddingTop; padding[2] = it.paddingRight; padding[3] = it.paddingBottom
            minimum = it.minimumWidth
        }
    }
    fun restore() {
        view.get()?.let {
            it.overlay.remove(this)
            it.setPadding(padding[0], padding[1], padding[2], padding[3]); it.minimumWidth = minimum
        }
    }
    fun apply() {
        val v = view.get() ?: return
        restore()
        if (!on("消息内时间")) return
        label = messageTime(seconds, store()?.getString(TIME_PATTERN, "HH:mm") ?: "HH:mm")
        if (label.isEmpty()) return
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11f, v.resources.displayMetrics)
        paint.color = v.currentTextColor
        paint.alpha = (android.graphics.Color.alpha(v.currentTextColor) * .7f).toInt()
        val side = (10 * v.dp).toInt(); val vertical = (6 * v.dp).toInt()
        val line = kotlin.math.ceil((paint.descent() - paint.ascent()).toDouble()).toInt() + (4 * v.dp).toInt()
        v.setPadding(side, vertical, side, vertical + line)
        v.minimumWidth = maxOf(minimum, (paint.measureText(label) + side * 2).toInt())
        setBounds(0, 0, v.width, v.height)
        v.overlay.add(this); invalidateSelf()
    }
    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
        setBounds(0, 0, v.width, v.height); invalidateSelf()
    }
    override fun draw(canvas: Canvas) {
        val v = view.get() ?: return
        if (!on("消息内时间") || label.isEmpty()) return
        val width = (v.width - v.paddingLeft - v.paddingRight).coerceAtLeast(0).toFloat()
        val text = TextUtils.ellipsize(label, paint, width, TextUtils.TruncateAt.END).toString()
        val x = if (v.layoutDirection == View.LAYOUT_DIRECTION_RTL) v.paddingLeft.toFloat()
            else v.width - v.paddingRight - paint.measureText(text)
        canvas.drawText(text, x, v.height - v.paddingBottom + (4 * v.dp).toInt() - paint.ascent(), paint)
    }
    fun dispose() { restore(); view.get()?.removeOnLayoutChangeListener(this) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** 仅重绘已核实的普通文字正文；不改消息字符串、复制内容或输入框，不强套图片/卡片布局。 */
fun messageTimestamps() {
    val root = textRoot()
    val bound = WeakHashMap<Any, List<WeakReference<TextView>>>()
    hook(textBind(), gated = false) { chain ->
        bound.remove(chain.thisObject)?.mapNotNull { it.get() }?.forEach { stamps[it]?.restore() }
        chain.proceed().also {
            val body = root.invoke(chain.thisObject) as? View ?: return@also
            val views = textViews(body).toList()
            bound[chain.thisObject] = views.map { WeakReference(it) }
            val time = messageRecord(chain.getArg(1))?.get("msgTime") as? Long ?: 0L
            views.forEach { v -> stamps.getOrPut(v) { MessageStamp(v) }.also { it.captureNative(); it.seconds = time; it.apply() } }
        }
    }
}

private class AvatarCorners(view: View, val nativeRadius: Int, val nativeMode: Int,
    private val setter: java.lang.reflect.Method) : View.OnLayoutChangeListener {
    private val view = WeakReference(view)
    fun apply(enabled: Boolean = on("头像圆角")) {
        val v = view.get() ?: return
        val radius = avatarCorner(minOf(v.width, v.height), store()?.getInt(AVATAR_RADIUS, 20) ?: 20)
        // 真包 checkPathChanged 的 mode=1 分支调用四角等半径 addRoundRect；不是位掩码。
        setter.invoke(v, if (enabled) radius else nativeRadius, if (enabled) 1 else nativeMode)
        v.invalidate()
    }
    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) { apply() }
    fun dispose() { apply(false); view.get()?.removeOnLayoutChangeListener(this) }
}
private val corners = WeakHashMap<View, AvatarCorners>()
fun customAvatarCorners() {
    val type = cls("com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent")
    val image = type.getDeclaredMethod("C1").apply { isAccessible = true }
    val setter = cls("com.tencent.qqnt.base.widget.RoundRectImageView")
        .getDeclaredMethod("setCornerRadiusAndMode", Integer.TYPE, Integer.TYPE)
    hook(type.getDeclaredMethod("d1", Integer.TYPE, cls("com.tencent.aio.data.msglist.a"), List::class.java), gated = false) { chain ->
        val before = image.invoke(chain.thisObject) as? View
        before?.let { corners.remove(it)?.dispose() }
        chain.proceed().also {
            val v = image.invoke(chain.thisObject) as? View ?: return@also
            val state = AvatarCorners(v, v.get("mRadius") as Int, v.get("mRoundMode") as Int, setter)
            corners[v] = state; v.addOnLayoutChangeListener(state); state.apply()
        }
    }
}

internal data class MentionRange(val start: Int, val end: Int, val uin: Long, val uid: String)
/** 按元素顺序匹配；重复名字不总是指向首个，偏移使用 Android 的 UTF-16 单位。 */
internal fun mentionRanges(text: String, elements: List<Triple<String, Long, String>>): List<MentionRange> {
    var cursor = 0
    return elements.mapNotNull { (name, uin, uid) ->
        if (name.length < 2 || !name.startsWith("@")) return@mapNotNull null
        val start = text.indexOf(name, cursor)
        if (start < 0) return@mapNotNull null
        cursor = start + name.length
        if (uin <= 0 && !uid.startsWith("u_")) null else MentionRange(start, cursor, uin, uid)
    }
}
private class AtSpan(private val open: (View) -> Unit) : ClickableSpan() {
    override fun onClick(widget: View) = open(widget)
}
private val mentions = WeakHashMap<TextView, List<Triple<String, Long, String>>>()
private val mentionDown = WeakHashMap<TextView, Triple<Float, Float, Long>>()
private fun clearAtSpans(v: TextView): SpannableString {
    val text = SpannableString(v.text)
    text.getSpans(0, text.length, AtSpan::class.java).forEach(text::removeSpan)
    return text
}
private var openMention: ((View, MentionRange) -> Unit)? = null
private fun applyMentions(v: TextView) {
    val text = clearAtSpans(v)
    if (on("消息显示At对象")) for (range in mentionRanges(text.toString(), mentions[v].orEmpty())) {
        // 原有链接和宿主点击段优先；只补缺失的 @ 对象链接。
        if (text.getSpans(range.start, range.end, ClickableSpan::class.java).isNotEmpty()) continue
        text.setSpan(AtSpan { openMention?.invoke(it, range) }, range.start, range.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    v.text = text
}
fun clickableMentions() {
    val root = textRoot()
    val relation = cls("com.tencent.relation.common.api.IRelationNTUinAndUidApi")
    val lookup = relation.getMethod("getUinFromUid", String::class.java)
    val api = cls("com.tencent.mobileqq.qroute.QRoute").getMethod("api", Class::class.java)
    val all = cls("com.tencent.mobileqq.profilecard.data.AllInOne").getConstructor(String::class.java, Integer.TYPE)
    val profile = cls("com.tencent.mobileqq.profilecard.activity.FriendProfileCardActivity")
    openMention = { view, range ->
        runCatching {
            val uin = if (range.uin > 0) range.uin.toString() else lookup.invoke(api.invoke(null, relation), range.uid) as? String
            require(!uin.isNullOrBlank() && uin.toLongOrNull()?.let { it > 0 } == true) { "暂未找到此 UID 对应的 QQ 号：${range.uid}" }
            view.context.startActivity(Intent(view.context, profile).apply {
                putExtra("AllInOne", all.newInstance(uin, 35) as Parcelable)
                if (view.context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { AlertDialog.Builder(view.context).setTitle("无法打开 At 对象").setMessage(it.message).setPositiveButton("好", null).show() }
    }
    hook(textBind(), gated = false) { chain ->
        chain.proceed().also {
            val body = root.invoke(chain.thisObject) as? View ?: return@also
            val record = messageRecord(chain.getArg(1))
            val elements = if (record?.get("chatType") == 2) (record.get("elements") as? List<*>).orEmpty().mapNotNull { e ->
                val t = e?.get("textElement") ?: return@mapNotNull null
                if ((t.get("atType") as? Int ?: 0) == 0) null else Triple(t.get("content") as? String ?: "",
                    t.get("atUid") as? Long ?: 0L, t.get("atNtUid") as? String ?: "")
            } else emptyList()
            textViews(body).forEach { v -> mentions[v] = elements; applyMentions(v) }
        }
    }
    hook(cls("com.tencent.qqnt.aio.widget.SelectableTextView").getDeclaredMethod("onTouchEvent", MotionEvent::class.java), gated = false) { chain ->
        val v = chain.thisObject as? TextView ?: return@hook chain.proceed()
        val event = chain.getArg(0) as MotionEvent
        if (!on("消息显示At对象") || v !in mentions) return@hook chain.proceed()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> mentionDown[v] = Triple(event.x, event.y, event.eventTime)
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> mentionDown.remove(v)
            MotionEvent.ACTION_UP -> {
                val down = mentionDown.remove(v)
                val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                if (down != null && event.eventTime - down.third < ViewConfiguration.getLongPressTimeout() &&
                    kotlin.math.abs(event.x - down.first) < slop && kotlin.math.abs(event.y - down.second) < slop) {
                    val layout = v.layout
                    val x = event.x - v.totalPaddingLeft + v.scrollX
                    val y = event.y - v.totalPaddingTop + v.scrollY
                    if (layout != null && y >= 0 && y < layout.height) {
                        val line = layout.getLineForVertical(y.toInt())
                        if (x >= layout.getLineLeft(line) && x <= layout.getLineRight(line)) {
                            val offset = layout.getOffsetForHorizontal(line, x)
                            val text = v.text as? android.text.Spanned
                            val spans = text?.getSpans(offset, offset, AtSpan::class.java)
                            spans?.firstOrNull { text.getSpanStart(it) <= offset && offset < text.getSpanEnd(it) }?.let {
                                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                                try { chain.proceed(arrayOf<Any?>(cancel)) } finally { cancel.recycle() }
                                it.onClick(v); return@hook true
                            }
                        }
                    }
                }
            }
        }
        chain.proceed()
    }
}

fun refreshMessageStyle() {
    stamps.values.toList().forEach { it.apply() }
    corners.values.toList().forEach { it.apply() }
    mentions.keys.toList().forEach(::applyMentions)
}
fun resetMessageStyle() {
    stamps.values.toList().forEach { it.dispose() }; stamps.clear()
    corners.values.toList().forEach { it.dispose() }; corners.clear()
    mentions.keys.toList().forEach { it.text = clearAtSpans(it) }
    mentions.clear(); mentionDown.clear(); openMention = null
}
