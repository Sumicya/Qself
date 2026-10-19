// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.SparseArray
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import java.lang.ref.WeakReference
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** 聊天：会员装饰归零、防撤回、连发合并、转发、「+」面板、昵称行、轻互动、表情雨。 */

// ---- 会员装饰：NT 内核把气泡/字体/挂件放在几个纯数据结构里，构造完立刻清零，界面就按默认画。

fun plainBubble() = cls("com.tencent.qqnt.kernel.nativeinterface.VASMsgBubble").afterNew {
    it.set("bubbleId", 0)
    it.set("subBubbleId", 0)
}

fun plainFont() = cls("com.tencent.qqnt.kernel.nativeinterface.VASMsgFont").afterNew {
    it.set("fontId", 0)
    it.set("magicFontType", 0)
}

fun noPendant() = cls("com.tencent.qqnt.kernel.nativeinterface.VASMsgAvatarPendant").afterNew {
    it.set("pendantId", 0L)
    it.set("pendantDiyInfoId", 0)
}


// ---- 防撤回：服务器推来的撤回通知在进 NT 内核之前吞掉，消息留在本地；再按 seq 把那条消息捞出来，
// 记下 msgId（列表里画成半透明），并往会话里插一条本地灰字「xx 尝试撤回一条消息」。

private const val MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush"
private const val INFO_SYNC = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush"
private const val MSG_CB = "com.tencent.qqnt.kernel.nativeinterface.IMsgOperateCallback"
private const val TIP_CB = "com.tencent.qqnt.kernel.nativeinterface.IAddJsonGrayTipMsgCallback"

/** 被撤回过的消息 msgId，存 SharedPreferences「recalled」；ponytail: 只留最近 500 条，更早的标记会消失。 */
private val recalled: MutableSet<Long> = Collections.synchronizedSet(LinkedHashSet())
private val loaded by lazy { store()?.getString("recalled", null)?.split(',')?.mapNotNullTo(recalled) { it.toLongOrNull() }; true }

fun antiRecall() {
    val proxy = cls("com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession\$CppProxy")
    hook(proxy.method("onMsfPush")) { chain ->
        val cmd = chain.getArg(0) as? String
        val body = chain.getArg(1) as? ByteArray
        val r = if (body != null && cmd == MSG_PUSH) recall(body) else null
        when {
            body == null -> chain.proceed()
            r != null -> { runCatching { tip(chain.thisObject, r) }.onFailure { log("撤回灰字失败", it) }; null }
            cmd == INFO_SYNC -> chain.proceed(chain.args.toTypedArray().also { it[1] = stripSyncRecall(body) })
            else -> chain.proceed()
        }
    }
    // 列表里被撤回过的那条压到半透明。k1 / s1 都声明在列表 VB 的基类上，钩子会盖住所有子类，
    // 所以取视图和取参数都包起来：兄弟子类走进来就当没这回事，别把异常抛回 QQ。
    val vb = cls("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB")
    hook(vb.method("k1")) { chain ->
        chain.proceed().also {
            val root = runCatching { vb.getMethod("s1").invoke(chain.thisObject) as? View }.getOrNull() ?: return@also
            val id = runCatching { msgId(chain.getArg(1)) }.getOrNull()
            if (loaded && id != null && id in recalled) root.alpha = 0.5f else if (root.alpha == 0.5f) root.alpha = 1f
        }
    }
}

/** 一次撤回：会话类型（1 私聊 / 2 群）、会话 peerUid（对方 uid / 群号）、被撤回的 seq。 */
class Recall(val chatType: Int, val peer: String, val seqs: List<Long>)

/**
 * MsgPush{1: Message{2: ContentHead{1: type, 2: subType}, 3: Body{2: content}}}。
 * 私聊撤回 528/138：content = FriendRecall{1: Info{1: fromUid, 2: toUid, 3: seq}}；
 * 群撤回 732/17：content = 4 字节群号 + 类型 + 长度 + NotifyMsgBody{1: type(7 = 撤回), 4: 群号, 11: Recall{3: [{1: seq}]}}。
 */
fun recall(push: ByteArray): Recall? {
    val message = push.pb().bytes(1)?.pb() ?: return null
    val head = message.bytes(2)?.pb() ?: return null
    val content = message.bytes(3)?.pb()?.bytes(2)
    return when (head.long(1) to head.long(2)) {
        528L to 138L -> {
            // ponytail: 自己在别的设备上撤回时 fromUid 是自己，灰字会插到跟自己的会话里；懒得查 toUid 是不是本人。
            val info = content?.pb()?.bytes(1)?.pb()
            Recall(1, info?.bytes(1)?.let { String(it) }.orEmpty(), listOfNotNull(info?.long(3)))
        }
        732L to 17L -> {
            if (content == null || content.size <= 7) return null
            val body = content.copyOfRange(7, content.size).pb()
            if (body.long(1) != 7L) return null
            val seqs = body.bytes(11)?.pb()?.filter { it.num == 3 }?.mapNotNull { (it.value as? ByteArray)?.pb()?.long(1) }.orEmpty()
            Recall(2, body.long(4)?.toString().orEmpty(), seqs)
        }
        else -> null
    }
}

/** 按 seq 捞出被撤回的消息 → 记 msgId、拿发送者名字 → 插灰字。内核回调在内核线程上来。 */
private fun tip(session: Any, r: Recall) {
    if (r.peer.isEmpty() || r.seqs.isEmpty()) return
    val svc = session.javaClass.getMethod("getMsgService").invoke(session) ?: return
    val contact = cls("com.tencent.qqnt.kernelpublic.nativeinterface.Contact")
        .getConstructor(Int::class.java, String::class.java, String::class.java).newInstance(r.chatType, r.peer, "")
    val fetch = svc.javaClass.getMethod("getMsgsBySeqAndCount", contact.javaClass, Long::class.java, Int::class.java, Boolean::class.java, Boolean::class.java, cls(MSG_CB))
    for (seq in r.seqs) fetch.invoke(svc, contact, seq, 1, true, true, proxy(MSG_CB) { args ->
        val rec = (args?.get(2) as? List<*>)?.firstOrNull { it?.get("msgSeq") == seq }
        rec?.let { remember(it.get("msgId") as Long) }
        val name = (rec?.get("sendMemberName") as? String)?.ifEmpty { null } ?: rec?.get("sendNickName") as? String
        gray(svc, contact, name, rec?.get("senderUid") as? String)
        null
    })
}

private fun remember(id: Long) {
    if (loaded) recalled += id
    store()?.edit()?.putString("recalled", synchronized(recalled) { recalled.toList() }.takeLast(500).joinToString(","))?.apply()
}

private fun gray(svc: Any, contact: Any, name: String?, uid: String?) {
    val items = JSONArray()
    if (name != null && uid != null) items.put(JSONObject().put("txt", name).put("type", "qq").put("uid", uid).put("col", "3").put("nm", ""))
    items.put(JSONObject().put("txt", if (name != null && uid != null) " 尝试撤回一条消息" else "对方尝试撤回一条消息").put("type", "nor"))
    val json = JSONObject().put("align", "center").put("items", items).toString()
    val element = cls("com.tencent.qqnt.kernelpublic.nativeinterface.JsonGrayElement")
        .getConstructor(Long::class.java, String::class.java, String::class.java, Boolean::class.java, cls("com.tencent.qqnt.kernelpublic.nativeinterface.XmlToJsonParam"))
        .newInstance(2201L, json, "尝试撤回一条消息", true, null)
    svc.javaClass.getMethod("addLocalJsonGrayTipMsg", contact.javaClass, element.javaClass, Boolean::class.java, Boolean::class.java, cls(TIP_CB))
        .invoke(svc, contact, element, true, true, proxy(TIP_CB) { null })
}

/** 内核回调接口的动态代理：Object 那几个方法自己答，其余交给 [body]。 */
private fun proxy(iface: String, body: (Array<*>?) -> Any?): Any {
    val c = cls(iface)
    return Proxy.newProxyInstance(loader, arrayOf(c)) { self, m, args ->
        when (m.name) {
            "toString" -> "Qself$" + c.simpleName
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args?.get(0)
            else -> body(args)
        }
    }
}

private fun msgId(item: Any?): Long? = item?.let { runCatching { it.javaClass.getMethod("getMsgId").invoke(it) as? Long }.getOrNull() }

/** 一条 protobuf 字段：编号、在原 buffer 里的起止、值（varint→Long，bytes→ByteArray）。 */
class Pb(val num: Int, val start: Int, val end: Int, val value: Any)

/** 极简 protobuf 解码，只读一层；坏数据直接抛，钩子就按没装处理。 */
fun ByteArray.pb(): List<Pb> {
    val out = ArrayList<Pb>()
    var i = 0
    fun varint(): Long {
        var v = 0L
        var shift = 0
        while (true) {
            val b = this[i++].toInt()
            v = v or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return v
            shift += 7
        }
    }
    while (i < size) {
        val start = i
        val key = varint()
        val num = (key ushr 3).toInt()
        val value: Any = when ((key and 7).toInt()) {
            0 -> varint()
            1 -> { i += 8; 0L }
            5 -> { i += 4; 0L }
            2 -> { val n = varint().toInt(); copyOfRange(i, i + n).also { i += n } }
            else -> throw IllegalArgumentException("wire type ${key and 7}")
        }
        out += Pb(num, start, i, value)
    }
    return out
}

fun List<Pb>.bytes(num: Int) = firstOrNull { it.num == num }?.value as? ByteArray
fun List<Pb>.long(num: Int) = firstOrNull { it.num == num }?.value as? Long

/** InfoSyncPush 的字段 8 是 SyncMsgRecall（重连补发的撤回），整段剪掉，其余原样。 */
fun stripSyncRecall(sync: ByteArray): ByteArray {
    val fields = sync.pb()
    if (fields.none { it.num == 8 }) return sync
    val out = java.io.ByteArrayOutputStream(sync.size)
    fields.filter { it.num != 8 }.forEach { out.write(sync, it.start, it.end - it.start) }
    return out.toByteArray()
}

// ---- TG 连发：昵称固定组首，头像按内容中心浮动；保留原层级，仅收紧续条正上间距。

/** 正常消息按 msgId；待发送消息没有 id 时按组首对象身份，不能跳过自己的待发送消息。 */
internal class RunKey(private val item: Any) {
    private val id = msgId(item)?.takeIf { it != 0L }
    override fun equals(other: Any?): Boolean = other is RunKey &&
        if (id != null) id == other.id else other.id == null && item === other.item
    override fun hashCode(): Int = id?.hashCode() ?: System.identityHashCode(item)
}
/** 同一帧的消息快照共享索引；每个长组只回溯一次，帧前失效，避免复用旧列表缓存。 */
internal class RunStarts(val size: Int) {
    private val starts = IntArray(size) { -1 }
    fun start(index: Int, joinsPrevious: (Int) -> Boolean): Int {
        require(index in 0 until size)
        if (starts[index] >= 0) return starts[index]
        var cursor = index
        while (cursor > 0 && starts[cursor] < 0 && joinsPrevious(cursor)) cursor--
        val start = if (starts[cursor] >= 0) starts[cursor] else cursor
        for (i in cursor..index) starts[i] = start
        return start
    }
}

internal fun compactMessageTop(original: Int, continued: Boolean, density: Float): Int =
    if (continued && original > 0) minOf(original, (2 * density).toInt()) else original

internal data class BubbleCornerRadii(val topLeft: Int, val topRight: Int, val bottomRight: Int, val bottomLeft: Int)

/** 统一左部无圆角（0dp），右部灵活圆角（随连发首/中/尾、高度与圆角配置自适应；90度即 0% 时不圆角）。 */
internal fun nagramBubbleRadii(
    self: Boolean,
    continued: Boolean,
    continues: Boolean,
    density: Float,
    cornerPercent: Int = 20,
    heightPx: Int = 0,
): BubbleCornerRadii {
    if (cornerPercent <= 0 || density <= 0f) return BubbleCornerRadii(0, 0, 0, 0)
    val scale = (cornerPercent.coerceIn(0, 50) / 20f).coerceIn(0.25f, 1.5f)
    val outer = (17 * density * scale).roundToInt()
    val near = (6 * density * scale).roundToInt()
    val cap = if (heightPx > 0) (heightPx / 2).coerceAtLeast(0) else Int.MAX_VALUE
    val topRight = (if (continued) near else outer).coerceAtMost(cap)
    val bottomRight = (if (continues) near else outer).coerceAtMost(cap)
    return BubbleCornerRadii(
        topLeft = 0,
        topRight = topRight,
        bottomRight = bottomRight,
        bottomLeft = 0,
    )
}

internal data class NagramRowMargins(
    val avatarWidth: Int,
    val avatarMarginStart: Int,
    val avatarMarginEnd: Int,
    val contentMarginStart: Int,
    val contentMarginEnd: Int,
    val topGap: Int = 0,
)

/** 全员左对齐并尽量缩短边距：头像 36dp 左距 2dp，正文/昵称紧贴头像右侧 2dp、右距 4dp（总左缩进仅 40dp）。 */
internal fun nagramRowMargins(self: Boolean, density: Float, continued: Boolean = false): NagramRowMargins =
    NagramRowMargins(
        avatarWidth = (36 * density).roundToInt(),
        avatarMarginStart = (2 * density).roundToInt(),
        avatarMarginEnd = 0,
        contentMarginStart = (2 * density).roundToInt(),
        contentMarginEnd = (4 * density).roundToInt(),
        topGap = ((if (continued) 1 else 2) * density).roundToInt(),
    )

private class ReplyQuoteBar(private val dp: Float, private val self: Boolean) : Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val night = night()
        val barColor = if (self) (if (night) 0xFF8AB4F8.toInt() else 0xFF2B7CD3.toInt())
            else (monet(night, 0xFF) ?: if (night) 0xFF64B5EF.toInt() else 0xFF3390EC.toInt())
        val bgColor = (barColor and 0x00FFFFFF) or (if (night) 0x24000000 else 0x1A000000)
        val r = 4 * dp
        rect.set(b)
        paint.color = bgColor
        canvas.drawRoundRect(rect, r, r, paint)
        val barW = 3 * dp
        rect.set(b.left.toFloat(), b.top.toFloat(), (b.left + barW).coerceAtMost(b.right.toFloat()), b.bottom.toFloat())
        paint.color = barColor
        canvas.drawRoundRect(rect, r, r, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable opacity") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private class FlexibleRightBubbleDrawable(
    private val fill: Int,
    private val continued: Boolean,
    private val continues: Boolean,
    private val dp: Float,
    private val cornerPercent: Int,
) : Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val path = android.graphics.Path()
    private val rect = RectF()
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val radii = nagramBubbleRadii(false, continued, continues, dp, cornerPercent, b.height())
        val tr = radii.topRight.toFloat().coerceAtMost(b.width().toFloat())
        val br = radii.bottomRight.toFloat().coerceAtMost(b.width().toFloat())
        if (tr <= 0.5f && br <= 0.5f) {
            canvas.drawRect(rect, paint)
            return
        }
        path.reset()
        // 左上、左下恒为 0f（统一左部无圆角），右上、右下按连发与 90° 设置灵活圆角。
        path.addRoundRect(
            rect,
            floatArrayOf(0f, 0f, tr, tr, br, br, 0f, 0f),
            android.graphics.Path.Direction.CW,
        )
        canvas.drawPath(path, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable opacity") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private class BubbleSurface(
    surface: View,
    innerClears: List<View> = emptyList(),
    quote: View? = null,
) {
    private val surface = WeakReference(surface)
    private val innerClears = innerClears.filter { it !== surface }.distinct().map { WeakReference(it) to it.background }
    private val quote = WeakReference(quote)
    private val savedBg = surface.background
    private val savedQuoteBg = quote?.background
    private val savedQuotePadding = quote?.let { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
    private var active = false
    fun apply(self: Boolean, continued: Boolean, continues: Boolean, enabled: Boolean) {
        val v = surface.get() ?: return
        if (!enabled) { restore(); return }
        active = true
        val dp = v.dp
        val cornerPercent = if (on("头像圆角")) (store()?.getInt(AVATAR_RADIUS, 20) ?: 20).coerceIn(0, 50) else 20
        val night = night()
        val fill = if (self) {
            monet(night, if (night) 0xFF else 0x38) ?: if (night) 0xFF2B5278.toInt() else 0xFFEFFDDE.toInt()
        } else {
            if (night) 0xFF182533.toInt() else 0xFFFFFFFF.toInt()
        }
        v.background = FlexibleRightBubbleDrawable(fill, continued, continues, dp, cornerPercent)
        v.invalidate()
        for ((ref, _) in innerClears) {
            ref.get()?.let { if (it.background != null) it.background = null }
        }
        quote.get()?.let { q ->
            q.background = ReplyQuoteBar(dp, self)
            val padL = (8 * dp).roundToInt()
            val padV = (4 * dp).roundToInt()
            q.setPadding(padL, padV, padL, padV)
        }
    }
    fun restore() {
        if (!active) return
        active = false
        surface.get()?.background = savedBg
        for ((ref, bg) in innerClears) {
            ref.get()?.background = bg
        }
        quote.get()?.let { q ->
            q.background = savedQuoteBg
            savedQuotePadding?.let { q.setPadding(it[0], it[1], it[2], it[3]) }
        }
    }
}

private class ConstraintFields(params: Class<*>) {
    val topToTop = params.getDeclaredField("topToTop").apply { isAccessible = true }
    val topToBottom = params.getDeclaredField("topToBottom").apply { isAccessible = true }
    val bottomToBottom = params.getDeclaredField("bottomToBottom").apply { isAccessible = true }
    val startToStart = params.getDeclaredField("startToStart").apply { isAccessible = true }
    val startToEnd = params.getDeclaredField("startToEnd").apply { isAccessible = true }
    val endToStart = params.getDeclaredField("endToStart").apply { isAccessible = true }
    val endToEnd = params.getDeclaredField("endToEnd").apply { isAccessible = true }
    val leftToLeft = params.getDeclaredField("leftToLeft").apply { isAccessible = true }
    val leftToRight = params.getDeclaredField("leftToRight").apply { isAccessible = true }
    val rightToLeft = params.getDeclaredField("rightToLeft").apply { isAccessible = true }
    val rightToRight = params.getDeclaredField("rightToRight").apply { isAccessible = true }
    val horizontalBias = params.getDeclaredField("horizontalBias").apply { isAccessible = true }
    val validate = params.getDeclaredMethod("validate").apply { isAccessible = true }
}

private class SavedConstraint(view: View?, private val f: ConstraintFields) {
    val ref = WeakReference(view)
    val lp = view?.layoutParams as? ViewGroup.MarginLayoutParams
    val width = lp?.width ?: 0
    val height = lp?.height ?: 0
    val leftMargin = lp?.leftMargin ?: 0
    val rightMargin = lp?.rightMargin ?: 0
    val topMargin = lp?.topMargin ?: 0
    val bottomMargin = lp?.bottomMargin ?: 0
    val marginStart = lp?.marginStart ?: 0
    val marginEnd = lp?.marginEnd ?: 0
    val topToTop = getInt(f.topToTop)
    val topToBottom = getInt(f.topToBottom)
    val bottomToBottom = getInt(f.bottomToBottom)
    val startToStart = getInt(f.startToStart)
    val startToEnd = getInt(f.startToEnd)
    val endToStart = getInt(f.endToStart)
    val endToEnd = getInt(f.endToEnd)
    val leftToLeft = getInt(f.leftToLeft)
    val leftToRight = getInt(f.leftToRight)
    val rightToLeft = getInt(f.rightToLeft)
    val rightToRight = getInt(f.rightToRight)
    val horizontalBias = runCatching { lp?.let { f.horizontalBias.getFloat(it) } }.getOrNull()
    private fun getInt(field: java.lang.reflect.Field): Int? = runCatching { lp?.let { field.getInt(it) } }.getOrNull()
    fun restore() {
        val v = ref.get() ?: return
        val p = lp ?: return
        if (v.layoutParams !== p) return
        p.width = width; p.height = height
        p.leftMargin = leftMargin; p.rightMargin = rightMargin
        p.topMargin = topMargin; p.bottomMargin = bottomMargin
        p.marginStart = marginStart; p.marginEnd = marginEnd
        runCatching {
            topToTop?.let { f.topToTop.setInt(p, it) }
            topToBottom?.let { f.topToBottom.setInt(p, it) }
            bottomToBottom?.let { f.bottomToBottom.setInt(p, it) }
            startToStart?.let { f.startToStart.setInt(p, it) }
            startToEnd?.let { f.startToEnd.setInt(p, it) }
            endToStart?.let { f.endToStart.setInt(p, it) }
            endToEnd?.let { f.endToEnd.setInt(p, it) }
            leftToLeft?.let { f.leftToLeft.setInt(p, it) }
            leftToRight?.let { f.leftToRight.setInt(p, it) }
            rightToLeft?.let { f.rightToLeft.setInt(p, it) }
            rightToRight?.let { f.rightToRight.setInt(p, it) }
            horizontalBias?.let { f.horizontalBias.setFloat(p, it) }
            f.validate.invoke(p)
        }
        v.layoutParams = p
    }
}

private const val ID_MULTI_SELECT = 0x7f0a3afd

private class RowGeometry(
    root: View,
    avatarSlot: View?,
    contentSlot: View?,
    nickSlot: View?,
    tailSlot: View?,
    headSlot: View?,
    private val f: ConstraintFields,
) {
    private val root = WeakReference(root)
    private val origRootPadding = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
    private val avatar = SavedConstraint(avatarSlot, f)
    private val content = SavedConstraint(contentSlot, f)
    private val nick = SavedConstraint(nickSlot, f)
    private val tail = SavedConstraint(tailSlot, f)
    private val head = SavedConstraint(headSlot, f)
    private val origContentGravity = (contentSlot as? LinearLayout)?.gravity
    private var applied = false
    fun apply(self: Boolean, continued: Boolean, enabled: Boolean) {
        if (!enabled) { restore(); return }
        applied = true
        val row = root.get()
        val av = avatar.ref.get()
        val cv = content.ref.get()
        val dp = (av ?: cv ?: row)?.dp ?: return
        val m = nagramRowMargins(self, dp, continued)
        row?.setPadding(0, 0, 0, 0)
        val hasMultiSelect = (row as? ViewGroup)?.findViewById<View>(ID_MULTI_SELECT) != null
        val avId = av?.id?.takeIf { it != View.NO_ID } ?: 0x7f0a3aea
        val cvId = cv?.id?.takeIf { it != View.NO_ID } ?: 0x7f0a11b9
        if (av != null && avatar.lp != null && av.layoutParams === avatar.lp) {
            val p = avatar.lp
            p.width = m.avatarWidth
            p.height = m.avatarWidth
            p.leftMargin = m.avatarMarginStart
            p.marginStart = m.avatarMarginStart
            p.rightMargin = m.avatarMarginEnd
            p.marginEnd = m.avatarMarginEnd
            p.topMargin = m.topGap
            p.bottomMargin = 0
            runCatching {
                f.startToEnd.setInt(p, if (hasMultiSelect) ID_MULTI_SELECT else -1)
                f.startToStart.setInt(p, if (hasMultiSelect) -1 else 0)
                f.leftToLeft.setInt(p, if (hasMultiSelect) -1 else 0)
                f.leftToRight.setInt(p, -1)
                f.endToEnd.setInt(p, -1)
                f.endToStart.setInt(p, -1)
                f.rightToRight.setInt(p, -1)
                f.rightToLeft.setInt(p, -1)
                if (cv != null && cv.id != View.NO_ID) {
                    f.topToTop.setInt(p, -1)
                    f.topToBottom.setInt(p, -1)
                    f.bottomToBottom.setInt(p, cv.id)
                }
                f.validate.invoke(p)
            }
            av.layoutParams = p
        }
        val nv = nick.ref.get()
        if (nv != null && nick.lp != null && nv.layoutParams === nick.lp) {
            val p = nick.lp
            p.leftMargin = m.contentMarginStart
            p.marginStart = m.contentMarginStart
            p.rightMargin = m.contentMarginEnd
            p.marginEnd = m.contentMarginEnd
            p.topMargin = 0
            p.bottomMargin = 0
            runCatching {
                f.startToEnd.setInt(p, avId)
                f.startToStart.setInt(p, -1)
                f.leftToRight.setInt(p, avId)
                f.leftToLeft.setInt(p, -1)
                f.endToEnd.setInt(p, 0)
                f.endToStart.setInt(p, -1)
                f.rightToRight.setInt(p, 0)
                f.rightToLeft.setInt(p, -1)
                f.horizontalBias.setFloat(p, 0f)
                f.validate.invoke(p)
            }
            nv.layoutParams = p
        }
        if (cv != null && content.lp != null && cv.layoutParams === content.lp) {
            val p = content.lp
            p.leftMargin = m.contentMarginStart
            p.marginStart = m.contentMarginStart
            p.rightMargin = m.contentMarginEnd
            p.marginEnd = m.contentMarginEnd
            p.topMargin = m.topGap
            p.bottomMargin = 0
            runCatching {
                f.startToEnd.setInt(p, avId)
                f.startToStart.setInt(p, -1)
                f.leftToRight.setInt(p, avId)
                f.leftToLeft.setInt(p, -1)
                f.endToEnd.setInt(p, 0)
                f.endToStart.setInt(p, -1)
                f.rightToRight.setInt(p, 0)
                f.rightToLeft.setInt(p, -1)
                f.horizontalBias.setFloat(p, 0f)
                f.validate.invoke(p)
            }
            if (cv is LinearLayout) cv.gravity = Gravity.START or Gravity.TOP
            cv.layoutParams = p
        }
        for (aux in listOf(tail, head)) {
            val v = aux.ref.get() ?: continue
            val p = aux.lp ?: continue
            if (v.layoutParams !== p) continue
            p.leftMargin = m.contentMarginStart
            p.marginStart = m.contentMarginStart
            p.rightMargin = 0
            p.marginEnd = 0
            runCatching {
                f.startToEnd.setInt(p, cvId)
                f.leftToRight.setInt(p, cvId)
                f.endToStart.setInt(p, -1)
                f.rightToLeft.setInt(p, -1)
                f.validate.invoke(p)
            }
            v.layoutParams = p
        }
    }
    fun restore() {
        if (!applied) return
        applied = false
        root.get()?.setPadding(origRootPadding[0], origRootPadding[1], origRootPadding[2], origRootPadding[3])
        avatar.restore()
        nick.restore()
        content.restore()
        (content.ref.get() as? LinearLayout)?.let { cv -> origContentGravity?.let { cv.gravity = it } }
        tail.restore()
        head.restore()
    }
}

private class RunSpacing(view: View) {
    private val view = WeakReference(view)
    private val params = view.layoutParams as? ViewGroup.MarginLayoutParams
    private val top = params?.topMargin ?: 0
    fun apply(continued: Boolean): Boolean {
        val view = view.get() ?: return false
        val params = params ?: return false
        if (view.layoutParams !== params) return false
        val target = compactMessageTop(top, continued, view.dp)
        if (params.topMargin == target) return false
        params.topMargin = target
        view.layoutParams = params
        return true
    }
}

/** 模块覆盖与 QQ 原生可见性分开，原生异步刷新不能把续条昵称/重复头像重新亮出来。 */
internal fun runVisibility(native: Int, override: Int?): Int =
    if (override == View.INVISIBLE && native != View.VISIBLE) native else override ?: native

private val runMeasurements = WeakHashMap<View, WeakReference<RunBinding>>()
private val runVisibilities = WeakHashMap<View, RunVisibility>()
private class RunVisibility(view: View) {
    private val view = WeakReference(view)
    var native = view.visibility
    private var override: Int? = null
    var writing = false
    init { runVisibilities[view] = this }
    fun receive(value: Int): Int { native = value; return runVisibility(native, override) }
    fun apply(value: Int?) {
        override = value
        view.get()?.let {
            val target = runVisibility(native, override)
            if (it.visibility != target) {
                writing = true
                try { it.visibility = target } finally { writing = false }
            }
        }
    }
    fun dispose() {
        apply(null)
        view.get()?.let { if (runVisibilities[it] === this) runVisibilities.remove(it) }
    }
}
private class RunAvatar(container: View, image: View) {
    val container = WeakReference(container)
    val image = WeakReference(image)
    val visibility = RunVisibility(container)
    fun hide() = visibility.apply(View.INVISIBLE)
    fun restore() = visibility.apply(null)
}
private class RunLayout(val avatars: List<RunAvatar>, nicks: List<View>, contents: List<View>, slots: List<View>,
    root: View, avatarSlots: List<View>, val bubbles: List<BubbleSurface> = emptyList(),
    val rowGeometry: RowGeometry? = null, var self: Boolean = false, var continues: Boolean = false) {
    private val root = WeakReference(root)
    val avatarSlots = avatarSlots.map { WeakReference(it) }
    private var compact = false
    val spacing = slots.map(::RunSpacing)
    val nicks = nicks.map(::RunVisibility)
    val contents = contents.map { WeakReference(it) }
    val contentSlots = slots.map { WeakReference(it) }
    fun apply(continued: Boolean, enabled: Boolean) {
        val next = enabled && (continued || self)
        if (compact != next) { compact = next; root.get()?.requestLayout() }
        spacing.forEach { it.apply(enabled && continued) }
        rowGeometry?.apply(self, continued, enabled)
        bubbles.forEach { it.apply(self, continued, continues, enabled && on("统一气泡")) }
        if (!enabled) avatars.forEach { it.restore() }
        nicks.forEach { it.apply(if (enabled && (continued || self)) View.GONE else null) }
    }
    fun restore() = apply(false, false)
    fun dispose() {
        restore()
        rowGeometry?.restore()
        bubbles.forEach { it.restore() }
        avatars.forEach { it.visibility.dispose() }
        nicks.forEach { it.dispose() }
        root.get()?.let { if (runMeasurements[it]?.get()?.layout === this) runMeasurements.remove(it) }
    }
}
private class RunBinding(val item: Any, adapter: Any, var index: Int, var group: RunKey,
    var continued: Boolean, var continues: Boolean, var self: Boolean = false, var layout: RunLayout? = null) {
    val adapter = WeakReference(adapter)
    var valid = true
}
private val bindingRun = ThreadLocal<Pair<View, RunBinding>>()
private val runIndexes = WeakHashMap<Any, Pair<WeakReference<List<*>>, RunStarts>>()
private val pendingAttachments = WeakHashMap<View, View.OnAttachStateChangeListener>()
private val pendingRun = WeakHashMap<View, RunBinding>()
private val runAvatars = WeakHashMap<ViewGroup, WeakReference<RunAvatars>>()

fun groupRuns() {
    // 始终跟踪复用；昵称/间距在绑定和开关变化时处理，绘制器只跟随滚动。
    val owner = feature
    feature = null
    try {
        val adapter = cls("com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vb.ui.adapter.a")
        val data = adapter.getMethod("d0")
        val head = adapter.getMethod("i0")
        val differ = cls("com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vb.ui.adapter.differ.AutoCancelAsyncListDiffer")
        val current = differ.getDeclaredMethod("u")
        val committed = differ.getDeclaredMethod("j", List::class.java,
            cls("com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vb.ui.adapter.differ.e"))
        val differAdapters = WeakHashMap<Any, WeakReference<Any>>()
        val vb = cls("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB")
        val vbRoot = vb.getMethod("s1")
        val vbTemplate = vb.getDeclaredField("d").apply { isAccessible = true }
        val templateClass = cls("com.tencent.mobileqq.aio.msglist.holder.template.n")
        val tplAvatar = templateClass.getDeclaredMethod("a")
        val tplContent = templateClass.getDeclaredMethod("b")
        val tplNick = templateClass.getDeclaredMethod("e")
        val tplTail = templateClass.getDeclaredMethod("i")
        val tplHead = templateClass.getDeclaredMethod("u")
        val tplReceiver = cls("com.tencent.mobileqq.aio.msglist.holder.template.g")
        val tplSender = cls("com.tencent.mobileqq.aio.msglist.holder.template.l")
        val registry = vb.getMethod("o1")
        val components = cls("com.tencent.mobileqq.aio.msglist.holder.g").getMethod("get")
        val component = cls("com.tencent.mobileqq.aio.msglist.holder.component.c")
        val componentRoot = component.getMethod("f1")
        val componentBubble = component.getMethod("h1")
        val componentSlot = component.getMethod("i1")
        val extras = vb.getDeclaredField("G").apply { isAccessible = true }
        val constraints = cls("androidx.constraintlayout.widget.ConstraintLayout")
        val params = cls("androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams")
        val constraintFields = ConstraintFields(params)
        val widget = params.getDeclaredMethod("getConstraintWidget")
        val widgetType = cls("androidx.constraintlayout.solver.widgets.ConstraintWidget")
        val widgetY = widgetType.getDeclaredMethod("getY")
        val widgetHeight = widgetType.getDeclaredMethod("getHeight")
        val measured = View::class.java.getDeclaredMethod("setMeasuredDimension", Integer.TYPE, Integer.TYPE)
            .apply { isAccessible = true }
        val avatarType = cls("com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent")
        // 真包 C1 的访问标记为 0x12（private final），getMethod 只查 public 会使整项安装失败。
        val avatarImage = avatarType.method("C1").apply { isAccessible = true }
        val nickType = cls("com.tencent.mobileqq.aio.msglist.holder.component.nick.pit.AIONickComponentV2")
        val slotNickType = cls("com.tencent.mobileqq.aio.msglist.holder.component.nick.slot.AIONickComponentV4")
        val contentType = cls("com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent")
        val mixType = cls("com.tencent.mobileqq.aio.msglist.holder.base.BaseMixContentComponent")
        val replyType = cls("com.tencent.mobileqq.aio.msglist.holder.component.reply.AIOReplyComponent")
        val textType = cls("com.tencent.mobileqq.aio.msglist.holder.component.text.AIOTextContentComponent")
        val textContentGetter = textType.getDeclaredMethod("k2").apply { isAccessible = true }
        val textMsgContentType = cls("com.tencent.mobileqq.aio.msg.TextMsgContent")
        val textFgField = textMsgContentType.getDeclaredField("b").apply { isAccessible = true }
        val textLinearField = textMsgContentType.getDeclaredField("d").apply { isAccessible = true }
        val textViewGetter = textMsgContentType.getDeclaredMethod("l").apply { isAccessible = true }
        val textUpdateBubble = textMsgContentType.getDeclaredMethod("H", cls("com.tencent.mobileqq.aio.msg.ai")).apply { isAccessible = true }
        val pttType = cls("com.tencent.mobileqq.aio.msglist.holder.component.ptt.AIOPttContentComponent")
        val skinInfoType = cls("com.tencent.mobileqq.aio.msglist.holder.skin.AIOBubbleSkinInfo")
        val paddingType = cls("com.tencent.mobileqq.aio.msglist.holder.skin.padding.j")
        val paddingCalc = paddingType.getDeclaredMethod("a", cls("com.tencent.mobileqq.aio.msg.AIOMsgItem"))
        val padLeft = paddingType.getDeclaredField("a").apply { isAccessible = true }
        val padRight = paddingType.getDeclaredField("b").apply { isAccessible = true }
        val padTop = paddingType.getDeclaredField("c").apply { isAccessible = true }
        val padBottom = paddingType.getDeclaredField("d").apply { isAccessible = true }
        val bindingVB = ThreadLocal<Boolean>()
        var reported = false
        var warned = false
        fun unbind(row: View) = runAvatars.values.mapNotNull { it.get() }.forEach { it.unbind(row) }
        fun register(row: View, run: RunBinding) {
            if (!row.isAttachedToWindow || pendingRun[row] !== run || run.layout == null) return
            val host = generateSequence(row.parent) { it.parent }.filterIsInstance<ViewGroup>()
                .firstOrNull { parent -> generateSequence<Class<*>>(parent.javaClass) { it.superclass }
                    .any { it.name == "androidx.recyclerview.widget.RecyclerView" } } ?: return
            val renderer = runAvatars[host]?.get() ?: RunAvatars(host).also {
                runAvatars[host] = WeakReference(it)
            }
            renderer.bind(row, run)
            val layout = run.layout ?: return
            if (!reported && layout.avatars.isNotEmpty() && layout.contents.isNotEmpty()) {
                reported = true
                log("TG连发定位：原头像容器=${layout.avatars.size}，实际内容=${layout.contents.size}，昵称=${layout.nicks.size}；按内容中心定位")
            }
        }
        fun capture(binder: Any, row: View, run: RunBinding) {
            run.layout = runCatching {
                val table = components.invoke(registry.invoke(binder)) as SparseArray<*>
                val active = extras.get(binder) as SparseArray<*>
                val all = (0 until table.size()).map { table.valueAt(it) } +
                    (0 until active.size()).map { active.valueAt(it) }
                val avatars = ArrayList<RunAvatar>()
                val avatarSlots = ArrayList<View>()
                val nicks = ArrayList<View>()
                val contents = ArrayList<View>()
                val slots = ArrayList<View>()
                val bubbles = ArrayList<BubbleSurface>()
                // 主表 + 已激活附加表 G；不是把未创建容器的 f.a 全部拿来猜。
                for (c in all.filterNotNull().distinct()) {
                    when {
                        avatarType.isInstance(c) -> {
                            val view = componentRoot.invoke(c) as? View ?: continue
                            val image = avatarImage.invoke(c) as? View ?: continue
                            (componentSlot.invoke(c) as? View)?.let { avatarSlots.add(it) }
                            if (avatars.none { it.container.get() === view }) avatars.add(RunAvatar(view, image))
                        }
                        (nickType.isInstance(c) || slotNickType.isInstance(c)) -> (componentSlot.invoke(c) as? View)?.let { nicks.add(it) }
                        contentType.isInstance(c) -> {
                            val rootView = componentRoot.invoke(c) as? View
                            val bubbleView = runCatching { componentBubble.invoke(c) as? View }.getOrNull()
                            (rootView ?: bubbleView)?.let { contents.add(it) }
                            // VB.H1 改的是 component.i1().layoutParams.topMargin，不是 VB 根视图。
                            (componentSlot.invoke(c) as? View)?.let { slots.add(it) }
                            if (mixType.isInstance(c) && rootView != null) {
                                val quote = if (replyType.isInstance(c) && rootView is ViewGroup && rootView.childCount > 0) rootView.getChildAt(0) else null
                                bubbles.add(BubbleSurface(rootView, listOfNotNull(bubbleView), quote))
                            } else if (textType.isInstance(c) && (rootView ?: bubbleView) != null) {
                                val tmc = runCatching { textContentGetter.invoke(c) }.getOrNull()
                                val fg = runCatching { tmc?.let { textFgField.get(it) as? View } }.getOrNull()
                                val linear = runCatching { tmc?.let { textLinearField.get(it) as? View } }.getOrNull()
                                val tv = runCatching { tmc?.let { textViewGetter.invoke(it) as? View } }.getOrNull()
                                val surfaceTarget = rootView ?: bubbleView!!
                                bubbles.add(BubbleSurface(surfaceTarget, listOfNotNull(bubbleView, fg, linear, tv)))
                            } else if (pttType.isInstance(c) && (bubbleView ?: rootView) != null) {
                                bubbles.add(BubbleSurface((bubbleView ?: rootView)!!))
                            }
                        }
                    }
                }
                val rootView = vbRoot.invoke(binder) as View
                val tpl = runCatching { vbTemplate.get(binder) }.getOrNull()
                val avSlot = runCatching { tpl?.let { tplAvatar.invoke(it) as? View } }.getOrNull() ?: avatarSlots.firstOrNull()
                val ctSlot = runCatching { tpl?.let { tplContent.invoke(it) as? View } }.getOrNull() ?: slots.firstOrNull()
                val nkSlot = runCatching { tpl?.let { tplNick.invoke(it) as? View } }.getOrNull() ?: nicks.firstOrNull()
                val tlSlot = runCatching { tpl?.let { tplTail.invoke(it) as? View } }.getOrNull()
                val hdSlot = runCatching { tpl?.let { tplHead.invoke(it) as? View } }.getOrNull()
                val geom = RowGeometry(rootView, avSlot, ctSlot, nkSlot, tlSlot, hdSlot, constraintFields)
                RunLayout(avatars, nicks.distinct(), contents.distinct(), slots.distinct(),
                    rootView, avatarSlots.distinct(), bubbles, geom, run.self, run.continues)
            }.onFailure { if (!warned) { warned = true; log("TG连发定位：组件读取失败，未改该消息布局", it) } }.getOrNull()
            run.layout?.let { runMeasurements[vbRoot.invoke(binder) as View] = WeakReference(run) }
            run.layout?.apply(run.continued, on("连发合并"))
            register(row, run)
        }
        fun context(binder: Any): Pair<View, RunBinding>? {
            bindingRun.get()?.let { return it }
            val root = runCatching { vbRoot.invoke(binder) as? View }.getOrNull() ?: return null
            val row = generateSequence(root) { it.parent as? View }.firstOrNull { pendingRun.containsKey(it) } ?: return null
            return pendingRun[row]?.let { row to it }
        }
        hook(vb.method("k1")) { chain ->
            if (!vb.isInstance(chain.thisObject)) return@hook chain.proceed()
            val context = context(chain.thisObject)
            context?.first?.let(::unbind)
            val previous = bindingVB.get()
            bindingVB.set(true)
            try { chain.proceed().also {
                context?.let { (row, run) -> capture(chain.thisObject, row, run) }
            } } finally { if (previous == null) bindingVB.remove() else bindingVB.set(previous) }
        }
        // 延后激活昵称等附加组件也要登记，不能只拍第一次 k1 的组件快照。
        hook(vb.getDeclaredMethod("j1", Integer.TYPE)) { chain ->
            if (bindingVB.get() == true ||
                (extras.get(chain.thisObject) as SparseArray<*>).get(chain.getArg(0) as Int) != null) return@hook chain.proceed()
            val context = context(chain.thisObject)
            context?.first?.let(::unbind)
            chain.proceed().also { context?.let { (row, run) -> capture(chain.thisObject, row, run) } }
        }
        hook(View::class.java.getDeclaredMethod("setVisibility", Integer.TYPE)) { chain ->
            val state = runVisibilities[chain.thisObject]
            if (state == null || state.writing) chain.proceed()
            else chain.proceed(arrayOf<Any?>(state.receive(chain.getArg(0) as Int)))
        }
        // 原生头像保持已测量以供浮绘，但续条行高不再被头像槽撑大。
        // 直接采用 ConstraintLayout.onLayout 使用的 widget Y/height，不猜子视图坐标。
        hook(constraints.getDeclaredMethod("onMeasure", Integer.TYPE, Integer.TYPE)) { chain ->
            chain.proceed().also {
                val row = chain.thisObject as ViewGroup
                val run = runMeasurements[row]?.get() ?: return@also
                val layout = run.layout ?: return@also
                if (!run.valid || !run.continued || !on("连发合并") ||
                    View.MeasureSpec.getMode(chain.getArg(1) as Int) == View.MeasureSpec.EXACTLY) return@also
                val excluded = layout.avatarSlots.mapNotNull { it.get() }
                var bottom = 0
                for (i in 0 until row.childCount) {
                    val child = row.getChildAt(i)
                    val lp = child.layoutParams
                    if (child in excluded || child.visibility == View.GONE ||
                        lp.height == ViewGroup.LayoutParams.MATCH_PARENT || !params.isInstance(lp)) continue
                    val native = widget.invoke(lp) ?: continue
                    bottom = maxOf(bottom, (widgetY.invoke(native) as Int) + (widgetHeight.invoke(native) as Int))
                }
                val target = compactRunHeight(row.measuredHeight, bottom, row.paddingBottom, row.minimumHeight)
                if (target < row.measuredHeight) measured.invoke(row, row.measuredWidthAndState,
                    View.resolveSizeAndState(target, chain.getArg(1) as Int,
                        row.measuredHeightAndState and View.MEASURED_STATE_MASK))
            }
        }
        for (skinOwner in listOf(mixType, replyType)) {
            hook(skinOwner.getDeclaredMethod("C2", skinInfoType)) { chain ->
                chain.proceed().also {
                    val rootView = runCatching { componentRoot.invoke(chain.thisObject) as? View }.getOrNull() ?: return@also
                    val row = generateSequence(rootView) { it.parent as? View }.firstOrNull { pendingRun.containsKey(it) } ?: return@also
                    pendingRun[row]?.let { run -> run.layout?.apply(run.continued, on("连发合并")) }
                }
            }
        }
        hook(textUpdateBubble) { chain ->
            chain.proceed().also {
                val fg = runCatching { textFgField.get(chain.thisObject) as? View }.getOrNull() ?: return@also
                val row = generateSequence(fg) { it.parent as? View }.firstOrNull { pendingRun.containsKey(it) } ?: return@also
                pendingRun[row]?.let { run -> run.layout?.apply(run.continued, on("连发合并")) }
            }
        }
        hook(paddingCalc) { chain ->
            chain.proceed().also {
                if (!on("连发合并")) return@also
                val l = padLeft.getInt(chain.thisObject)
                val r = padRight.getInt(chain.thisObject)
                val t = padTop.getInt(chain.thisObject)
                val b = padBottom.getInt(chain.thisObject)
                val h = minOf(l, r).coerceAtLeast(0)
                val v = (minOf(t, b) * 2 / 3).coerceAtLeast(0)
                padLeft.setInt(chain.thisObject, h)
                padRight.setInt(chain.thisObject, h)
                padTop.setInt(chain.thisObject, v)
                padBottom.setInt(chain.thisObject, v)
            }
        }
        for (tplType in listOf(tplReceiver, tplSender)) {
            hook(tplType.getDeclaredMethod("q", java.lang.Boolean.TYPE)) { chain ->
                chain.proceed().also {
                    val av = runCatching { tplAvatar.invoke(chain.thisObject) as? View }.getOrNull() ?: return@also
                    val row = generateSequence(av) { it.parent as? View }.firstOrNull { pendingRun.containsKey(it) } ?: return@also
                    pendingRun[row]?.let { run -> run.layout?.apply(run.continued, on("连发合并")) }
                }
            }
        }
        fun regroup(owner: Any, list: List<*>, starts: RunStarts) {
            for ((_, run) in pendingRun) if (run.adapter.get() === owner) {
                val index = runItemIndex(list, run.item, run.index)
                run.valid = index >= 0
                if (index < 0) { run.layout?.restore(); continue }
                run.index = index
                val start = starts.start(index) { i -> follows(list[i - 1], list[i]) }
                run.group = RunKey(list[start]!!)
                run.continued = start < index
                run.continues = index < list.lastIndex && follows(list[index], list[index + 1])
                run.self = isSelfMsg(run.item)
                run.layout?.let { it.self = run.self; it.continues = run.continues }
                run.layout?.apply(run.continued, on("连发合并"))
            }
            runAvatars.values.mapNotNull { it.get() }.forEach { it.refresh() }
        }
        hook(committed) { chain -> chain.proceed().also {
            val owner = differAdapters[chain.thisObject]?.get() ?: return@also
            val list = current.invoke(chain.thisObject) as? List<*> ?: return@also
            runIndexes.remove(owner)
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) regroup(owner, list, RunStarts(list.size))
        } }
        hook(adapter.method("n0")) { chain ->
            val row = runCatching { chain.getArg(0)?.get("itemView") as? View }.getOrNull()
            row?.let { pendingRun.remove(it); unbind(it) }
            val source = data.invoke(chain.thisObject)
            if (source != null) differAdapters[source] = WeakReference(chain.thisObject)
            val list = source?.let { current.invoke(it) as? List<*> }
            val index = chain.getArg(1) as Int - (head.invoke(chain.thisObject) as Int)
            val run = if (list != null && index in list.indices) {
                val cache = runIndexes[chain.thisObject]?.takeIf { it.first.get() === list && it.second.size == list.size }
                    ?: (WeakReference(list) to RunStarts(list.size)).also { runIndexes[chain.thisObject] = it }
                regroup(chain.thisObject, list, cache.second)
                val start = cache.second.start(index) { i -> follows(list[i - 1], list[i]) }
                list[index]?.let { RunBinding(it, chain.thisObject, index, RunKey(list[start]!!), start < index,
                    index < list.lastIndex && follows(list[index], list[index + 1]), isSelfMsg(it)) }
            } else null
            if (row != null && run != null) pendingRun[row] = run
            val previous = bindingRun.get()
            bindingRun.set(if (row != null && run != null) row to run else null)
            try {
                chain.proceed().also {
                    if (row != null && run != null) {
                        pendingRun[row] = run
                        if (row.isAttachedToWindow) register(row, run) else if (!pendingAttachments.containsKey(row)) {
                            // 同步接入首次 attach，在这一帧 pre-draw 前定位，不排主线程 post 队列。
                            val listener = object : View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(view: View) {
                                    view.removeOnAttachStateChangeListener(this)
                                    pendingAttachments.remove(view)
                                    pendingRun[view]?.let { register(view, it) }
                                }
                                override fun onViewDetachedFromWindow(view: View) = Unit
                            }
                            pendingAttachments[row] = listener
                            row.addOnAttachStateChangeListener(listener)
                        }
                    }
                }
            } finally { bindingRun.set(previous) }
        }
        hook(ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)) { chain ->
            val renderer = runAvatars[chain.thisObject]?.get()
            if (renderer?.touch(chain.getArg(0) as MotionEvent) == true) true else chain.proceed()
        }
    } finally { feature = owner }
}

private class RunRow(val run: RunBinding, val layout: RunLayout) {
    val group get() = run.group
    val continued get() = run.continued
    fun restore() = layout.dispose()
}

/** 布局在绑定/设置变化时完成；滚动帧只采样几何并提交到同一帧的 overlay。 */
private class RunAvatars(private val host: ViewGroup) : Drawable(), ViewTreeObserver.OnPreDrawListener,
    View.OnAttachStateChangeListener {
    private val rows = WeakHashMap<View, RunRow>()
    private class Face(val group: RunKey, val binding: RunBinding, val view: View, val target: View,
        val matrix: Matrix, val toTarget: Matrix, val rect: RectF)
    private var faces = emptyList<Face>()
    private var viewport = Rect()
    private val toHost = Matrix()
    private var pressed: Face? = null
    private var tracking = false
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    init {
        host.overlay.add(this)
        host.viewTreeObserver.addOnPreDrawListener(this)
        host.addOnAttachStateChangeListener(this)
    }
    fun unbind(row: View) {
        val state = rows.remove(row) ?: return
        val containers = state.layout.avatars.mapNotNull { it.container.get() }
        if (pressed?.target in containers) cancelPressed()
        val next = faces.filterNot { it.target in containers }
        commitFrame(next, viewport)
        state.restore()
    }
    fun bind(row: View, run: RunBinding) {
        if (rows[row]?.run === run && rows[row]?.layout === run.layout) return
        unbind(row)
        run.layout?.let { layout ->
            layout.apply(run.continued, on("连发合并"))
            rows[row] = RunRow(run, layout)
            host.invalidate()
        }
    }
    fun refresh() {
        val enabled = on("连发合并")
        rows.values.forEach { if (it.run.valid) it.layout.apply(it.continued, enabled) else it.layout.restore() }
        if (!enabled) { cancelPressed(); commitFrame(emptyList(), viewport) }
        host.invalidate()
    }
    private fun inHost(view: View) = Matrix().also {
        view.transformMatrixToGlobal(it)
        it.postConcat(toHost)
    }
    private fun rectangle(view: View, matrix: Matrix): RectF? {
        if (view.width <= 0 || view.height <= 0) return null
        return RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()).also(matrix::mapRect)
            .takeIf { it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() && !it.isEmpty }
    }
    private fun commitFrame(next: List<Face>, clip: Rect) {
        // 改 Kotlin 坐标不会让硬件 overlay 的 display list 自动失效，必须显式提交重绘。
        // 只在几何/内容变化时失效，静止时不制造无穷刷新循环。
        val changed = viewport != clip || next.size != faces.size || next.indices.any { i ->
            val old = faces[i]; val new = next[i]
            old.view !== new.view || old.target !== new.target || old.binding !== new.binding ||
                old.group != new.group || old.matrix != new.matrix || new.view.isDirty
        }
        faces = next
        viewport = Rect(clip)
        pressed?.let { old ->
            val current = next.firstOrNull { it.target === old.target && it.binding === old.binding && it.group == old.group }
            if (current == null) cancelPressed() else pressed = current
        }
        if (changed) invalidateSelf()
    }
    override fun onPreDraw(): Boolean {
        runIndexes.clear()
        val clip = Rect()
        if (!on("连发合并") || !host.getLocalVisibleRect(clip)) {
            commitFrame(emptyList(), clip)
            return true
        }
        if (host.clipToPadding) {
            clip.left = maxOf(clip.left, host.paddingLeft)
            clip.top = maxOf(clip.top, host.paddingTop)
            clip.right = minOf(clip.right, host.width - host.paddingRight)
            clip.bottom = minOf(clip.bottom, host.height - host.paddingBottom)
        }
        val global = Matrix()
        host.transformMatrixToGlobal(global)
        if (clip.isEmpty || !global.invert(toHost)) { commitFrame(emptyList(), clip); return true }
        setBounds(0, 0, host.width, host.height)
        val groups = LinkedHashMap<RunKey, MutableList<RunRow>>()
        // 不在这里恢复昵称或间距，不 requestLayout，也不取消正在滚动的一帧。
        for ((row, state) in rows) if (state.run.valid && row.isAttachedToWindow && row.parent === host)
            groups.getOrPut(state.group) { ArrayList() }.add(state)
        val next = ArrayList<Face>()
        for ((key, group) in groups) {
            var groupTop = Float.POSITIVE_INFINITY
            var groupBottom = Float.NEGATIVE_INFINITY
            var firstCenter = Float.POSITIVE_INFINITY
            var lastCenter = Float.NEGATIVE_INFINITY
            var hasStart = false
            var hasEnd = false
            for (state in group) {
                var top = Float.POSITIVE_INFINITY
                var bottom = Float.NEGATIVE_INFINITY
                // 异步正文根被替换/暂时隐藏时仍取稳定的原生正文槽，不能退回行顶头像。
                val roots = state.layout.contents.mapNotNull { it.get() }.filter {
                    it.isAttachedToWindow && it.visibility != View.GONE && it.width > 0 && it.height > 0
                }
                val geometry = roots.ifEmpty { state.layout.contentSlots.mapNotNull { it.get() } }
                for (content in geometry) {
                    if (!content.isAttachedToWindow || content.visibility == View.GONE) continue
                    val rect = rectangle(content, inHost(content)) ?: continue
                    top = minOf(top, rect.top); bottom = maxOf(bottom, rect.bottom)
                }
                if (top.isFinite() && bottom.isFinite()) {
                    val center = (top + bottom) / 2
                    groupTop = minOf(groupTop, top); groupBottom = maxOf(groupBottom, bottom)
                    firstCenter = minOf(firstCenter, center); lastCenter = maxOf(lastCenter, center)
                    hasStart = hasStart || !state.run.continued
                    hasEnd = hasEnd || !state.run.continues
                }
            }
            val avatars = group.flatMap { it.layout.avatars }
            val merged = group.size > 1 || !hasStart || !hasEnd || group.any { it.run.continued || it.run.continues }
            // 尽量保留上帧同一个原图源，不能每滚入一行就切到新的头像组件。
            val preferred = pressed?.takeIf { it.group == key }?.target
                ?: faces.firstOrNull { it.group == key }?.target
            val candidates = avatars.sortedBy { if (it.container.get() === preferred) 0 else 1 }
            var face: Face? = null
            for (avatar in candidates) {
                if (avatar.visibility.native != View.VISIBLE) continue
                val image = avatar.image.get() ?: continue
                if (image.visibility != View.VISIBLE) continue
                val container = avatar.container.get() ?: continue
                val matrix = inHost(image)
                val rect = rectangle(image, matrix) ?: continue
                val bounds = runAvatarBounds(groupTop, groupBottom, firstCenter, lastCenter, rect.height(), merged) ?: continue
                val y = floatingRunAvatarTop(bounds.first, bounds.second, clip.top.toFloat(), clip.bottom.toFloat(),
                    rect.height(), 2 * host.dp, hasStart, hasEnd) ?: continue
                val delta = y - rect.top
                matrix.postTranslate(0f, delta)
                val targetMatrix = inHost(container).apply { postTranslate(0f, delta) }
                val inverse = Matrix()
                if (!targetMatrix.invert(inverse)) continue
                rect.offset(0f, delta)
                val binding = group.first { state -> avatar in state.layout.avatars }.run
                face = Face(key, binding, image, container, matrix, inverse, rect)
                break
            }
            if (face == null) {
                // 正文异步切换的一帧沿用本组上次有效位置，不能恢复到 QQ 的行顶位置。
                val old = faces.firstOrNull { previous -> previous.group == key && isCurrent(previous) && avatars.any {
                    it.image.get() === previous.view && it.visibility.native == View.VISIBLE
                } }
                if (old != null && old.view.isAttachedToWindow) {
                    val matrix = inHost(old.view)
                    val source = rectangle(old.view, matrix)
                    if (source != null) {
                        val top = old.rect.top.coerceIn(clip.top.toFloat() - source.height(),
                            maxOf(clip.top.toFloat() - source.height(), clip.bottom - source.height() - 2 * host.dp))
                        val delta = top - source.top
                        matrix.postTranslate(0f, delta)
                        val targetMatrix = inHost(old.target).apply { postTranslate(0f, delta) }
                        val inverse = Matrix()
                        if (targetMatrix.invert(inverse)) {
                            source.offset(0f, delta)
                            face = Face(key, old.binding, old.view, old.target, matrix, inverse, source)
                        }
                    }
                }
            }
            // 没有可绘制源时不能把原生头像也隐藏，否则异步绑定期间整组头像消失。
            if (face != null) { avatars.forEach { it.hide() }; next.add(face) }
            else avatars.forEach { it.restore() }
        }
        commitFrame(next, clip)
        return true
    }
    // RecyclerView 可在两次 pre-draw 之间重绑；绘制与每个触摸事件都核对绑定身份。
    private fun isCurrent(face: Face): Boolean = face.binding.valid && face.binding.group == face.group &&
        face.view.isAttachedToWindow && face.target.isAttachedToWindow && rows.values.any { state ->
            state.run === face.binding && state.layout.avatars.any {
                it.image.get() === face.view && it.container.get() === face.target
            }
        }
    override fun draw(canvas: Canvas) {
        for (face in faces) {
            if (!isCurrent(face)) continue
            val save = canvas.save()
            canvas.clipRect(viewport)
            canvas.concat(face.matrix)
            face.view.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
    private fun cancelPressed() {
        val face = pressed ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        cancel.transform(face.toTarget)
        try { face.target.dispatchTouchEvent(cancel) } finally { cancel.recycle(); pressed = null }
    }
    fun touch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!on("连发合并")) return false
            pressed = faces.lastOrNull { isCurrent(it) && viewport.contains(event.x.toInt(), event.y.toInt()) && it.rect.contains(event.x, event.y) }
            tracking = pressed != null
            downX = event.x; downY = event.y; dragging = false
        }
        if (!tracking) return false
        // DOWN 先让列表停止旧惯性；之后列表就是触摸目标，只走 onTouchEvent。
        // 不能在 UP 再调 onInterceptTouchEvent：RecyclerView 会先清速度，导致惯性失效。
        if (event.actionMasked == MotionEvent.ACTION_DOWN && host.onInterceptTouchEvent(event)) {
            cancelPressed(); dragging = true
        }
        host.onTouchEvent(event)
        if (!on("连发合并") || pressed?.let { !isCurrent(it) } == true) cancelPressed()
        val move = kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        if (!dragging && move) { cancelPressed(); dragging = true }
        pressed?.let { face ->
            val copy = MotionEvent.obtain(event)
            copy.transform(face.toTarget)
            try { face.target.dispatchTouchEvent(copy) } finally { copy.recycle() }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            pressed = null; tracking = false
        }
        return true
    }
    override fun onViewDetachedFromWindow(view: View) {
        cancelPressed()
        if (tracking) {
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
            try { host.onTouchEvent(cancel) } finally { cancel.recycle(); tracking = false }
        }
        rows.values.forEach { it.restore() }
        commitFrame(emptyList(), Rect())
        if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnPreDrawListener(this)
        host.overlay.remove(this)
    }
    fun dispose() {
        onViewDetachedFromWindow(host)
        rows.clear()
        host.removeOnAttachStateChangeListener(this)
    }
    override fun onViewAttachedToWindow(view: View) {
        refresh()
        host.overlay.add(this)
        host.viewTreeObserver.addOnPreDrawListener(this)
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: ColorFilter?) = Unit
    @Deprecated("Drawable opacity") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

fun refreshChatLayout() {
    runAvatars.values.mapNotNull { it.get() }.forEach { it.refresh() }
    pendingRun.values.forEach { run -> run.layout?.apply(run.continued, on("连发合并")) }
}

fun resetChatLayout() {
    runAvatars.values.mapNotNull { it.get() }.forEach { it.dispose() }
    pendingAttachments.entries.toList().forEach { (view, listener) -> view.removeOnAttachStateChangeListener(listener) }
    pendingRun.values.forEach { it.layout?.dispose() }
    runAvatars.clear(); pendingRun.clear(); runIndexes.clear(); pendingAttachments.clear(); runVisibilities.clear(); runMeasurements.clear(); bindingRun.remove()
}

/** 单条严格按内容中心；长组跟随最后一条内容中心，并约束在组首与可见底部之间。 */
internal fun floatingAvatarTop(firstCenter: Float, lastCenter: Float, viewportTop: Float, viewportBottom: Float,
    height: Float, gap: Float): Float? {
    if (!firstCenter.isFinite() || !lastCenter.isFinite() || !height.isFinite() || height <= 0 ||
        !viewportTop.isFinite() || !viewportBottom.isFinite() || viewportBottom <= viewportTop ||
        !gap.isFinite() || gap < 0 || firstCenter > lastCenter ||
        lastCenter + height / 2 <= viewportTop || firstCenter - height / 2 >= viewportBottom) return null
    return maxOf(firstCenter, minOf(lastCenter, viewportBottom - height / 2 - gap)) - height / 2
}

/** 只有双方共有的标识才能比较；不能一条用 UID、另一条用后来才补齐的 UIN。 */
internal fun runSenderPair(uid: String?, uin: Long?, self: Boolean, previousUid: String?, previousUin: Long?,
    previousSelf: Boolean): Pair<String?, String?> = when {
    self && previousSelf -> "self" to "self"
    !uid.isNullOrBlank() && !previousUid.isNullOrBlank() -> "uid:$uid" to "uid:$previousUid"
    uin != null && uin > 0 && previousUin != null && previousUin > 0 -> "uin:$uin" to "uin:$previousUin"
    else -> null to null
}

internal fun runItemIndex(list: List<*>, item: Any, previous: Int): Int {
    if (list.getOrNull(previous) === item) return previous
    list.indexOfFirst { it === item }.takeIf { it >= 0 }?.let { return it }
    val id = msgId(item)?.takeIf { it != 0L } ?: return -1
    return list.indexOfFirst { msgId(it) == id }
}

internal fun compactRunHeight(nativeHeight: Int, contentBottom: Int, paddingBottom: Int, minimum: Int): Int =
    if (contentBottom <= 0) nativeHeight else minOf(nativeHeight, maxOf(minimum, contentBottom + paddingBottom))

/** 头像定位：滚动合并大于居中。只要组高度超过头像或存在多条/续接消息，即按整组上下边界滚动合并；仅短单条居中。 */
internal fun runAvatarBounds(
    groupTop: Float,
    groupBottom: Float,
    firstCenter: Float,
    lastCenter: Float,
    height: Float,
    merged: Boolean,
): Pair<Float, Float>? {
    if (!groupTop.isFinite() || !groupBottom.isFinite() || !firstCenter.isFinite() || !lastCenter.isFinite() ||
        !height.isFinite() || height <= 0f || groupTop > groupBottom || firstCenter > lastCenter) return null
    val span = groupBottom - groupTop
    if (span > height) return (groupTop + height / 2f) to (groupBottom - height / 2f)
    if (merged && firstCenter < lastCenter) return minOf(firstCenter, groupTop + height / 2f) to maxOf(lastCenter, groupBottom - height / 2f)
    val center = (groupTop + groupBottom) / 2f
    return minOf(firstCenter, center) to maxOf(lastCenter, center)
}

/** 可见的只有组中间一条时，不能误当成整个组只有一条；起止约束来自消息数据。 */
internal fun floatingRunAvatarTop(first: Float, last: Float, top: Float, bottom: Float, height: Float, gap: Float,
    hasStart: Boolean, hasEnd: Boolean): Float? {
    if (!first.isFinite() || !last.isFinite() || first > last) return null
    return floatingAvatarTop(if (hasStart) first else minOf(first, top - height),
        if (hasEnd) last else maxOf(last, bottom + height), top, bottom, height, gap)
}

/** 跨日、倒序、不同发送者和系统消息均断组；连续消息只合并五分钟内的。 */
internal fun continuesMessageRun(sender: String?, previousSender: String?, time: Long, previousTime: Long,
    type: Int?, previousType: Int?): Boolean = !sender.isNullOrEmpty() && sender == previousSender &&
    type != null && previousType != null && type !in setOf(5, 29) && previousType !in setOf(5, 29) &&
    time >= previousTime && time - previousTime <= 300 &&
    (time + 8 * 3600) / 86400 == (previousTime + 8 * 3600) / 86400

private fun isSelfMsg(item: Any?): Boolean =
    runCatching { item?.javaClass?.getMethod("isSelf")?.invoke(item) as? Boolean }.getOrNull() == true

private fun follows(prev: Any?, cur: Any?): Boolean {
    if (prev === cur) return false
    val previousId = msgId(prev)
    if (previousId != null && previousId != 0L && previousId == msgId(cur)) return false
    val a = record(prev) ?: return false
    val b = record(cur) ?: return false
    val t0 = a.get("msgTime") as? Long ?: return false
    val t1 = b.get("msgTime") as? Long ?: return false
    val sender = runSenderPair(b.get("senderUid") as? String, b.get("senderUin") as? Long, isSelfMsg(cur),
        a.get("senderUid") as? String, a.get("senderUin") as? Long, isSelfMsg(prev))
    return continuesMessageRun(sender.first, sender.second, t1, t0, b.get("msgType") as? Int, a.get("msgType") as? Int)
}

private fun record(item: Any?): Any? = item?.let { runCatching { it.javaClass.getMethod("getMsgRecord").invoke(it) }.getOrNull() }

// ---- 回复不@：QQ 回复消息时会往输入框插一个「@昵称 」，这一步整个跳过。

/**
 * ponytail: 直接不跑插 @ 的那个方法（上游 QAuxiliary 的 fallback 策略）。上游更稳的做法是双钩子——
 * 进回复流程时置标志、让「这条消息是不是匿名」的判断返回 true，于是只跳掉 @ 那一段；
 * 但那个判断读的 anonymousExtInfo 字段在 9.2.10 的 dex 里找不到读点，不硬凑。
 * 真机上回复要是掉帧、或者引用条出不来，就回来上双钩子。
 */
fun replyNoAt() = hook(cls("com.tencent.mobileqq.aio.input.reply.i").method("k")) { null }

// ---- 转发页：好友/群/多选入口一直显示。

fun multiForward() {
    val slots = listOf("contactLayout", "friendLayout", "multiChatLayout", "troopDiscussionLayout")
    hook(cls("com.tencent.mobileqq.activity.ForwardRecentActivity").method("initEntryHeaderView")) { chain ->
        chain.proceed().also { slots.forEach { s -> (chain.thisObject.get(s) as? View)?.visibility = View.VISIBLE } }
    }
}

// ---- 左滑回复：卡片 (Ark) 消息 QQ 一律说不支持回复，这里一律说支持。（上游 QAuxiliary 同款）

fun replyAnyMsg() = hook(cls("com.tencent.mobileqq.ark.api.impl.ArkHelperImpl").method("isSupportReply")) { true }

// ---- 转发不限人数：QQ 的 add2ForwardTargetList 选满 9 个就返回 false，这里自己往 map 里塞，再刷新右侧按钮。

@Suppress("UNCHECKED_CAST")
fun noForwardLimit() {
    val act = cls("com.tencent.mobileqq.activity.ForwardRecentActivity")
    val rec = cls("com.tencent.mobileqq.selectmember.ResultRecord")
    // 要反射的东西装的时候全解析好：缺一个当场 ✗，别等到点转发才发现
    val keyOf = act.getDeclaredMethod("getForwardTargetKey", String::class.java, Integer.TYPE).apply { isAccessible = true }
    val copyOf = rec.getMethod("copyResultRecord", rec)
    val typeOf = rec.getMethod("getUinType")
    val refresh = act.getDeclaredMethod("refreshRightBtn").apply { isAccessible = true }
    val syncSearch = act.getDeclaredMethod("setSearchSelectedAndJoinedUins").apply { isAccessible = true }
    hook(act.getDeclaredMethod("add2ForwardTargetList", rec)) { chain ->
        val r = chain.getArg(0) ?: return@hook false
        val self = chain.thisObject
        val map = self.get("mForwardTargetMap") as? MutableMap<Any, Any?> ?: return@hook chain.proceed()
        val key = keyOf.invoke(self, r.get("uin"), typeOf.invoke(r)) ?: return@hook chain.proceed()
        map[key] = copyOf.invoke(r, r)
        refresh.invoke(self)
        syncSearch.invoke(self)
        // 顶部那条「已选 N 人」按签名找（void(List, boolean)），找不到就算了，不影响转发本身
        self.get("mSelectedAndSearchBar")?.let { bar ->
            bar.javaClass.declaredMethods
                .firstOrNull { it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(List::class.java, Boolean::class.java)) }
                ?.apply { isAccessible = true }?.invoke(bar, ArrayList(map.values), true)
        }
        true
    }
}

// ---- 「+」面板：只留正经附件（照片、拍摄、文件、位置、红包……），娱乐入口剔掉。

private val PLUS_DROP = setOf(
    "一起派对", "好友爱玩", "一起看", "一起K歌", "一起听歌", "一起玩", "礼物", "厘米秀",
    "短视频", "直播间", "群课堂", "作业", "匿名送礼", "赞赏照片", "匿问我答", "滤镜", "涂鸦",
)

fun plusPanel() {
    // 9.2.10 把入口存在 PlusPanelUiState.FetchCompleted 的 ArrayList 里，getter a() 发出来；每次取都剔一遍。
    hook(cls("com.tencent.qqnt.pluspanel.data.PlusPanelUiState\$FetchCompleted").method("a")) { chain ->
        chain.proceed().also { (it as? MutableList<*>)?.removeAll { item -> item != null && title(item) in PLUS_DROP } }
    }
}

private fun title(item: Any): String? =
    runCatching { item.javaClass.getMethod("getTitle").invoke(item) as? String }.getOrNull()
        ?: runCatching { item.javaClass.getMethod("getName").invoke(item) as? String }.getOrNull()

// ---- 轻互动（戳一戳那类全屏特效）与表情雨：配置列表永远为空。

fun noLightInteraction() {
    val manager = cls("com.tencent.qqnt.biz.lightbusiness.lightinteraction.LIAConfigManager")
    val lists = manager.declaredMethods.filter { List::class.java.isAssignableFrom(it.returnType) }
    require(lists.isNotEmpty()) { "LIAConfigManager: 没有返回 List 的方法" }
    lists.forEach { m -> hook(m) { ArrayList<Any>() } }
}

fun noEmojiRain() =
    hook(cls("com.tencent.mobileqq.aio.animation.util.AioAnimationConfigHolder").method("e")) { ArrayList<Any>() }
