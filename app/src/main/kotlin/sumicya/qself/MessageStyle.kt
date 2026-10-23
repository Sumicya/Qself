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
internal const val BUBBLE_CORNER = "bubble_corner_dp"
internal const val BUBBLE_PADDING = "bubble_padding_dp"
internal const val HEADER_TOP_OFFSET = "header_top_offset_dp"
internal fun bubbleCornerDp(): Float = (store()?.getInt(BUBBLE_CORNER, 17) ?: 17).coerceIn(0, 32).toFloat()
internal fun bubblePaddingDp(): Float = (store()?.getInt(BUBBLE_PADDING, 5) ?: 5).coerceIn(0, 16).toFloat()
internal fun headerTopOffsetDp(): Float = (store()?.getInt(HEADER_TOP_OFFSET, 1) ?: 1).coerceIn(-12, 16).toFloat()
internal const val TIME_FORMAT_GUIDE =
    "格式码（左对齐显示）：yyyy/yy 年 · MM/M 月 · dd/d 日 · E 星期 · HH/H 0-23时 · kk/k 1-24时 · hh/h 1-12时 · KK/K 0-11时 · a 上下午 · mm/m 分 · ss/s 秒 · SSS/S 毫秒；文字用单引号包裹"
internal fun validTimePattern(pattern: String): Boolean = pattern.isNotEmpty() && pattern.none { it.isISOControl() } && runCatching {
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.EPOCH.atZone(ZoneId.systemDefault()))
}.isSuccess
internal fun messageTime(seconds: Long, pattern: String, zone: ZoneId = ZoneId.systemDefault()): String =
    if (seconds <= 0) "" else runCatching {
        val fmt = if (validTimePattern(pattern)) pattern else "HH:mm"
        DateTimeFormatter.ofPattern(fmt, Locale.getDefault()).format(Instant.ofEpochSecond(seconds).atZone(zone))
    }.getOrDefault("")
internal fun avatarCorner(size: Int, percent: Int): Int =
    (size.coerceAtLeast(0) * percent.coerceAtLeast(0) / 100f).toInt()

private fun messageRecord(item: Any?): Any? = runCatching { item?.javaClass?.getMethod("getMsgRecord")?.invoke(item) }.getOrNull()
private fun textViews(view: View): Sequence<TextView> = sequence {
    if (view is TextView && view.javaClass.name == "com.tencent.qqnt.aio.widget.AIOMsgTextView") yield(view)
    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) yieldAll(textViews(view.getChildAt(i)))
}
private const val TEXT_COMPONENT = "com.tencent.mobileqq.aio.msglist.holder.component.text.AIOTextContentComponent"
private val CONTENT_COMPONENTS = arrayOf(
    TEXT_COMPONENT,
    "com.tencent.mobileqq.aio.msglist.holder.component.file.AIOFileContentComponent",
    "com.tencent.mobileqq.aio.msglist.holder.component.pic.AIOPicContentComponent",
    "com.tencent.mobileqq.aio.msglist.holder.component.reply.AIOReplyComponent",
    "com.tencent.mobileqq.aio.msglist.holder.component.mix.AIOMixContentComponent",
    "com.tencent.mobileqq.aio.msglist.holder.component.ptt.AIOPttContentComponent",
    "com.tencent.mobileqq.aio.msglist.holder.component.marketface.AIOMarketFaceComponent",
    "com.tencent.mobileqq.aio.msglist.holder.component.ark.AIOArkContentComponent",
    "com.tencent.mobileqq.aio.msglist.holder.component.video.AIOVideoContentComponent",
)
private fun textBind() = cls(TEXT_COMPONENT).getDeclaredMethod("d1", Integer.TYPE,
    cls("com.tencent.aio.data.msglist.a"), List::class.java)
private fun textRoot() = cls(TEXT_COMPONENT).getDeclaredMethod("f1")

private val externalStamps = WeakHashMap<android.view.ViewGroup, ExternalStamp>()
private val timestampSlots = WeakHashMap<View, Int>()
private const val MATCH_PARENT_RESTORE_TAG = 0x51534D50

/** 消息外独立时间标签：挂在 msgContent（BubbleLayoutCompatPress）底部，他人左对齐、自己右对齐，适配全部消息类型。 */
private class ExternalStamp(host: android.view.ViewGroup) {
    private val host = WeakReference(host)
    private val labelView = TextView(host.context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        isClickable = false
        isFocusable = false
    }
    var seconds = 0L
    var self = false
    fun restore() {
        val container = host.get()
        if (container != null) {
            for (i in 0 until container.childCount) {
                val ch = container.getChildAt(i) ?: continue
                if (ch === labelView) continue
                if (ch.getTag(MATCH_PARENT_RESTORE_TAG) == true) {
                    ch.setTag(MATCH_PARENT_RESTORE_TAG, null)
                    ch.layoutParams?.let { lp ->
                        lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        ch.layoutParams = lp
                    }
                }
            }
        }
        (labelView.parent as? android.view.ViewGroup)?.removeView(labelView)
    }
    fun apply() {
        val container = host.get() ?: return
        if (!on("消息内时间")) {
            restore()
            return
        }
        val text = messageTime(seconds, store()?.getString(TIME_PATTERN, "HH:mm") ?: "HH:mm")
        if (text.isEmpty()) {
            restore()
            return
        }
        val dp = container.dp
        val align = if (self) android.view.Gravity.END else android.view.Gravity.START
        // BaseContentComponent.d1 调用 msgContent.addView(componentRoot) 时未传 LayoutParams，
        // LinearLayout 默认生成 width=MATCH_PARENT；一旦同容器加入 WRAP_CONTENT 的时间标签，
        // LinearLayout.measureVertical 的 allFillParent 变 false 并触发 forceUniformWidth，把图片/表情/卡片/短文本压成时间宽度。
        // 因此把兄弟内容根的 MATCH_PARENT 改为 WRAP_CONTENT 并按发送方向对齐。
        for (i in 0 until container.childCount) {
            val ch = container.getChildAt(i) ?: continue
            if (ch === labelView) continue
            val chLp = ch.layoutParams
            if (chLp != null) {
                var changed = false
                if (chLp.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                    ch.setTag(MATCH_PARENT_RESTORE_TAG, true)
                    chLp.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    changed = true
                }
                if (chLp is android.widget.LinearLayout.LayoutParams && chLp.gravity != align) {
                    chLp.gravity = align
                    changed = true
                }
                if (changed) ch.layoutParams = chLp
            }
        }
        labelView.text = text
        labelView.setTextColor(if (night()) 0xCCDFE5F0.toInt() else 0x993C3C43.toInt())
        labelView.gravity = align
        val lp = (labelView.layoutParams as? android.widget.LinearLayout.LayoutParams)
            ?: android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        lp.gravity = align
        lp.topMargin = (2 * dp).toInt()
        lp.bottomMargin = 0
        lp.leftMargin = 0
        lp.rightMargin = 0
        lp.marginStart = 0
        lp.marginEnd = 0
        labelView.layoutParams = lp
        if (labelView.parent !== container) {
            (labelView.parent as? android.view.ViewGroup)?.removeView(labelView)
            container.addView(labelView)
        } else if (container.getChildAt(container.childCount - 1) !== labelView) {
            container.removeView(labelView)
            container.addView(labelView)
        }
        labelView.visibility = View.VISIBLE
    }
    fun dispose() = restore()
}

/** 覆盖全部消息类型（文字、图片、文件、引用、混排、语音、表情、卡片、视频、合并转发等）的消息外时间与顶部时间条折叠。 */
fun messageTimestamps() {
    val component = cls("com.tencent.mobileqq.aio.msglist.holder.component.c")
    val slot = component.getMethod("i1")
    val msgItem = cls("com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent").getMethod("M1")
    val dataType = cls("com.tencent.aio.data.msglist.a")
    val vb = cls("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB")
    val vbTemplate = vb.getDeclaredField("d").apply { isAccessible = true }
    val templateClass = cls("com.tencent.mobileqq.aio.msglist.holder.template.n")
    val tplContent = templateClass.getDeclaredMethod("b")
    val tplReceiver = cls("com.tencent.mobileqq.aio.msglist.holder.template.g")
    val tplSender = cls("com.tencent.mobileqq.aio.msglist.holder.template.l")
    fun bindSlot(slotView: android.view.ViewGroup, item: Any?) {
        val time = messageRecord(item)?.get("msgTime") as? Long ?: 0L
        val self = runCatching { item?.javaClass?.getMethod("isSelf")?.invoke(item) as? Boolean }.getOrNull() == true
        externalStamps.getOrPut(slotView) { ExternalStamp(slotView) }.also {
            it.seconds = time
            it.self = self
            it.apply()
        }
    }
    for (name in CONTENT_COMPONENTS) {
        val bind = cls(name).getDeclaredMethod("d1", Integer.TYPE, dataType, List::class.java)
        hook(bind, gated = false) { chain ->
            chain.proceed().also {
                val slotView = runCatching { slot.invoke(chain.thisObject) as? android.view.ViewGroup }.getOrNull() ?: return@also
                val item = chain.args.getOrNull(1) ?: runCatching { msgItem.invoke(chain.thisObject) }.getOrNull()
                bindSlot(slotView, item)
            }
        }
    }
    hook(vb.method("k1"), gated = false) { chain ->
        chain.proceed().also {
            if (!vb.isInstance(chain.thisObject)) return@also
            val tpl = runCatching { vbTemplate.get(chain.thisObject) }.getOrNull() ?: return@also
            if (!tplReceiver.isInstance(tpl) && !tplSender.isInstance(tpl)) return@also
            val slotView = runCatching { tplContent.invoke(tpl) as? android.view.ViewGroup }.getOrNull() ?: return@also
            bindSlot(slotView, chain.args.getOrNull(1))
        }
    }
    val topStamp = cls("com.tencent.mobileqq.aio.msglist.holder.component.timestamp.AIOTimestampComponent")
        .getDeclaredMethod("d1", Integer.TYPE, dataType, List::class.java)
    hook(topStamp, gated = false) { chain ->
        chain.proceed().also {
            val view = runCatching { slot.invoke(chain.thisObject) as? View }.getOrNull() ?: return@also
            val saved = timestampSlots.getOrPut(view) { view.visibility }
            if (on("消息内时间")) {
                if (view.visibility != View.GONE) timestampSlots[view] = view.visibility
                view.visibility = View.GONE
            } else if (view.visibility == View.GONE && saved != View.GONE) {
                view.visibility = saved
            }
        }
    }
}

private val inAioAvatarLoad = ThreadLocal<Boolean>()
private val inAioAvatarDraw = ThreadLocal<Boolean>()
private val aioAvatarOptions: MutableSet<Any> = java.util.Collections.synchronizedSet(
    java.util.Collections.newSetFromMap(WeakHashMap())
)
private val aioProDrawables: MutableSet<Any> = java.util.Collections.synchronizedSet(
    java.util.Collections.newSetFromMap(WeakHashMap())
)
private class AvatarCorners(
    view: View,
    component: Any,
    val nativeRadius: Int,
    val nativeMode: Int,
    private val setter: java.lang.reflect.Method,
    private val lastRadiusField: java.lang.reflect.Field?,
    private val loadAvatar: java.lang.reflect.Method,
    private val msgField: java.lang.reflect.Field,
    private val cacheField: java.lang.reflect.Field,
) : View.OnLayoutChangeListener {
    private val view = WeakReference(view)
    private val component = WeakReference(component)
    private val origOutline = view.outlineProvider
    private val origClip = view.clipToOutline
    var loadedNoCircle = false
    var loadedSkipVas = false
    fun apply(enabled: Boolean = on("头像圆角"), allowReload: Boolean = true) {
        val v = view.get() ?: return
        val percent = (store()?.getInt(AVATAR_RADIUS, 20) ?: 20).coerceIn(0, 50)
        val wantNoCircle = enabled
        val wantSkipVas = enabled && percent < 50
        if (allowReload && inAioAvatarLoad.get() != true && (wantNoCircle != loadedNoCircle || wantSkipVas != loadedSkipVas)) {
            component.get()?.let { comp ->
                runCatching {
                    val item = msgField.get(comp)
                    if (item != null) {
                        cacheField.set(comp, null)
                        loadedNoCircle = wantNoCircle
                        loadedSkipVas = wantSkipVas
                        loadAvatar.invoke(comp, v.context, item)
                        cacheField.get(comp)?.let { aioAvatarOptions.add(it) }
                    }
                }
            }
        }
        val size = minOf(v.width, v.height).takeIf { it > 0 }
            ?: minOf(v.measuredWidth, v.measuredHeight).takeIf { it > 0 }
            ?: v.layoutParams?.let { minOf(it.width, it.height) }?.takeIf { it > 0 }
            ?: (37f * v.dp).toInt()
        val radius = avatarCorner(size, percent)
        runCatching { lastRadiusField?.setInt(v, -1) }
        // 真包 checkPathChanged 的 mode=1 分支调用四角等半径 addRoundRect；0%（90°直角）时置 0。
        if (enabled) {
            setter.invoke(v, radius, if (radius > 0) 1 else 0)
            if (radius > 0) {
                v.outlineProvider = capsule(radius.toFloat())
                v.clipToOutline = true
            } else {
                v.outlineProvider = origOutline
                v.clipToOutline = false
            }
        } else if (nativeMode == 0 && loadedNoCircle && size > 0) {
            setter.invoke(v, size / 2, 1)
            v.outlineProvider = origOutline
            v.clipToOutline = origClip
        } else {
            setter.invoke(v, nativeRadius, nativeMode)
            v.outlineProvider = origOutline
            v.clipToOutline = origClip
        }
        v.invalidate()
        notifyChatAvatarsUpdated()
    }
    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
        apply(allowReload = false)
    }
    fun dispose() { apply(false, allowReload = false); view.get()?.removeOnLayoutChangeListener(this) }
}
private val corners = WeakHashMap<View, AvatarCorners>()
fun customAvatarCorners() {
    val type = cls("com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent")
    val msgItemType = cls("com.tencent.mobileqq.aio.msg.AIOMsgItem")
    val image = type.getDeclaredMethod("C1").apply { isAccessible = true }
    val loadAvatar = type.getDeclaredMethod("K1", android.content.Context::class.java, msgItemType).apply { isAccessible = true }
    val defaultFace = type.getDeclaredMethod("D1").apply { isAccessible = true }
    val msgField = type.getDeclaredField("i").apply { isAccessible = true }
    val cacheField = type.getDeclaredField("m").apply { isAccessible = true }
    val roundRectView = cls("com.tencent.qqnt.base.widget.RoundRectImageView")
    val setter = roundRectView.getDeclaredMethod("setCornerRadiusAndMode", Integer.TYPE, Integer.TYPE)
    val lastRadiusField = runCatching { roundRectView.getDeclaredField("mLastRadius").apply { isAccessible = true } }.getOrNull()
    val roundRectDraw = roundRectView.getDeclaredMethod("draw", Canvas::class.java)
    val metaBuilder = cls("com.tencent.qqnt.avatar.meta.b\$a")
    val metaCircle = metaBuilder.getDeclaredMethod("m", java.lang.Boolean.TYPE)
    val metaPutExtra = metaBuilder.getDeclaredMethod("a", String::class.java, Any::class.java)
    val metaExtras = runCatching { metaBuilder.getDeclaredMethod("e").apply { isAccessible = true } }.getOrNull()
    val metaBuild = metaBuilder.getDeclaredMethod("b")
    val optionBuilder = cls("com.tencent.qqnt.avatar.b\$a")
    val optionBuild = optionBuilder.getDeclaredMethod("a")
    val optionVas = optionBuilder.getDeclaredMethod("i", cls("com.tencent.qqnt.avatar.i"))
    val engineClass = cls("com.tencent.qqnt.avatar.impl.engine.a")
    val engineOptionField = engineClass.getDeclaredField("a").apply { isAccessible = true }
    val engineLoadC = engineClass.getDeclaredMethod("c", String::class.java, android.content.Context::class.java)
    val engineLoadD = engineClass.getDeclaredMethod("d", String::class.java, android.content.Context::class.java)
    val proParamBuilder = cls("com.tencent.mobileqq.proavatar.e\$a").getDeclaredMethod("a")
    val proParamType = cls("com.tencent.mobileqq.proavatar.e")
    val proCircleField = proParamType.getDeclaredField("p").apply { isAccessible = true }
    val proVasAvatarField = proParamType.getDeclaredField("d").apply { isAccessible = true }
    val proVasFrameField = proParamType.getDeclaredField("f").apply { isAccessible = true }
    val proDrawableClass = cls("com.tencent.mobileqq.proavatar.QQProAvatarDrawable")
    val proDrawableMask = proDrawableClass.getDeclaredMethod(
        "z",
        cls("com.tencent.qqnt.avatar.layer.d"),
        Canvas::class.java,
        android.graphics.Rect::class.java,
        android.graphics.Paint::class.java,
        cls("kotlin.jvm.functions.Function0"),
    ).apply { isAccessible = true }
    val fnInvoke = cls("kotlin.jvm.functions.Function0").getMethod("invoke")
    val circleBitmap = cls("com.tencent.mobileqq.utils.BaseImageUtil").getDeclaredMethod(
        "getCircleFaceBitmap", android.graphics.Bitmap::class.java, Integer.TYPE, Integer.TYPE
    )
    fun syncView(comp: Any) {
        runCatching { cacheField.get(comp) }.getOrNull()?.let { aioAvatarOptions.add(it) }
        val v = image.invoke(comp) as? View ?: return
        (v as? android.widget.ImageView)?.drawable?.let { aioProDrawables.add(it) }
        val existing = corners[v]
        val isFirst = existing == null
        val state = if (existing != null) existing.also {
            if (inAioAvatarLoad.get() == true) {
                it.loadedNoCircle = on("头像圆角")
                it.loadedSkipVas = it.loadedNoCircle && (store()?.getInt(AVATAR_RADIUS, 20) ?: 20) < 50
            }
        } else AvatarCorners(
            v, comp, v.get("mRadius") as Int, v.get("mRoundMode") as Int,
            setter, lastRadiusField, loadAvatar, msgField, cacheField,
        ).also {
            if (inAioAvatarLoad.get() == true) {
                it.loadedNoCircle = on("头像圆角")
                it.loadedSkipVas = it.loadedNoCircle && (store()?.getInt(AVATAR_RADIUS, 20) ?: 20) < 50
            }
            corners[v] = it
            v.addOnLayoutChangeListener(it)
        }
        state.apply(allowReload = isFirst && inAioAvatarLoad.get() != true)
    }
    hook(optionBuild, gated = false) { chain ->
        chain.proceed().also { opt ->
            if (inAioAvatarLoad.get() == true && opt != null) aioAvatarOptions.add(opt)
        }
    }
    for (m in listOf(engineLoadC, engineLoadD)) {
        hook(m, gated = false) { chain ->
            val opt = runCatching { engineOptionField.get(chain.thisObject) }.getOrNull()
            val isAio = inAioAvatarLoad.get() == true || (opt != null && opt in aioAvatarOptions)
            if (!isAio) chain.proceed()
            else {
                val prev = inAioAvatarLoad.get()
                inAioAvatarLoad.set(true)
                try {
                    chain.proceed().also { d -> if (d != null) aioProDrawables.add(d) }
                } finally {
                    if (prev == null) inAioAvatarLoad.remove() else inAioAvatarLoad.set(prev)
                }
            }
        }
    }
    for (name in listOf("onUpdate", "C", "x")) {
        proDrawableClass.declaredMethods.filter { it.name == name }.forEach { m ->
            hook(m.apply { isAccessible = true }, gated = false) { chain ->
                val isAio = inAioAvatarLoad.get() == true || (chain.thisObject != null && chain.thisObject in aioProDrawables)
                if (!isAio) chain.proceed()
                else {
                    val prev = inAioAvatarLoad.get()
                    inAioAvatarLoad.set(true)
                    try {
                        chain.proceed().also {
                            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) notifyChatAvatarsUpdated()
                        }
                    } finally { if (prev == null) inAioAvatarLoad.remove() else inAioAvatarLoad.set(prev) }
                }
            }
        }
    }
    hook(metaPutExtra, gated = false) { chain ->
        if (inAioAvatarLoad.get() == true && on("头像圆角") &&
            (store()?.getInt(AVATAR_RADIUS, 20) ?: 20) < 50 &&
            chain.args.getOrNull(0) == "vas_special_shape_mask"
        ) chain.thisObject else chain.proceed()
    }
    // 普通聊天（J1=false）K1 走 FaceDrawableEngine（协程工作线程）默认裁成 360° 圆形；开启头像圆角时切到 _no_circle 并清除 vas_special_shape_mask，交给 RoundRectImageView 裁剪。
    hook(metaBuild, gated = false) { chain ->
        if (inAioAvatarLoad.get() == true && on("头像圆角")) {
            metaCircle.invoke(chain.thisObject, false)
            if ((store()?.getInt(AVATAR_RADIUS, 20) ?: 20) < 50) {
                (runCatching { metaExtras?.invoke(chain.thisObject) as? MutableMap<*, *> }.getOrNull())
                    ?.remove("vas_special_shape_mask")
            }
        }
        chain.proceed()
    }
    hook(proParamBuilder, gated = false) { chain ->
        chain.proceed().also { bean ->
            if (inAioAvatarLoad.get() == true && on("头像圆角") && bean != null) {
                val pct = (store()?.getInt(AVATAR_RADIUS, 20) ?: 20).coerceIn(0, 50)
                proCircleField.setBoolean(bean, false)
                if (pct < 50) {
                    proVasAvatarField.setBoolean(bean, false)
                    proVasFrameField.setBoolean(bean, false)
                }
            }
        }
    }
    hook(optionVas, gated = false) { chain ->
        if (inAioAvatarLoad.get() == true && on("头像圆角") && (store()?.getInt(AVATAR_RADIUS, 20) ?: 20) < 50) {
            chain.proceed(arrayOf<Any?>(null))
        } else chain.proceed()
    }
    hook(circleBitmap, gated = false) { chain ->
        if (inAioAvatarLoad.get() == true && on("头像圆角")) chain.getArg(0) else chain.proceed()
    }
    hook(roundRectDraw, gated = false) { chain ->
        val state = corners[chain.thisObject]
        if (state == null) chain.proceed()
        else {
            state.apply(allowReload = false)
            val prev = inAioAvatarDraw.get()
            inAioAvatarDraw.set(true)
            try { chain.proceed() } finally { if (prev == null) inAioAvatarDraw.remove() else inAioAvatarDraw.set(prev) }
        }
    }
    hook(proDrawableMask, gated = false) { chain ->
        if (inAioAvatarDraw.get() == true && on("头像圆角") && (store()?.getInt(AVATAR_RADIUS, 20) ?: 20) < 50) {
            chain.args.getOrNull(4)?.let { fnInvoke.invoke(it) }
            null
        } else chain.proceed()
    }
    hook(defaultFace, gated = false) { chain ->
        val prev = inAioAvatarLoad.get()
        inAioAvatarLoad.set(true)
        try { chain.proceed() } finally { if (prev == null) inAioAvatarLoad.remove() else inAioAvatarLoad.set(prev) }
    }
    hook(loadAvatar, gated = false) { chain ->
        val prev = inAioAvatarLoad.get()
        inAioAvatarLoad.set(true)
        try {
            chain.proceed().also { syncView(chain.thisObject) }
        } finally { if (prev == null) inAioAvatarLoad.remove() else inAioAvatarLoad.set(prev) }
    }
    hook(type.getDeclaredMethod("d1", Integer.TYPE, cls("com.tencent.aio.data.msglist.a"), List::class.java), gated = false) { chain ->
        chain.proceed().also { syncView(chain.thisObject) }
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
    externalStamps.values.toList().forEach { it.apply() }
    timestampSlots.entries.toList().forEach { (slot, saved) ->
        if (!slot.isAttachedToWindow) timestampSlots.remove(slot)
        else slot.visibility = if (on("消息内时间")) View.GONE else saved
    }
    corners.values.toList().forEach { it.apply() }
    mentions.keys.toList().forEach(::applyMentions)
    refreshChatLayout()
}
fun resetMessageStyle() {
    externalStamps.values.toList().forEach { it.dispose() }; externalStamps.clear()
    timestampSlots.entries.toList().forEach { (slot, saved) -> slot.visibility = saved }; timestampSlots.clear()
    corners.values.toList().forEach { it.dispose() }; corners.clear()
    mentions.keys.toList().forEach { it.text = clearAtSpans(it) }
    mentions.clear(); mentionDown.clear(); openMention = null; inAioAvatarLoad.remove(); inAioAvatarDraw.remove()
    aioAvatarOptions.clear(); aioProDrawables.clear()
}
