// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

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

// ---- TG 连发：昵称留在组首，头像贴着消息组可见底部；不搬动 QQ 的视图或改列表布局。

private data class RunBinding(val group: Long, val continued: Boolean, var avatar: WeakReference<View>? = null, var nick: WeakReference<View>? = null)
private val bindingRun = ThreadLocal<RunBinding>()
private val pendingRun = WeakHashMap<View, RunBinding>()
private val runAvatars = WeakHashMap<ViewGroup, WeakReference<RunAvatars>>()

fun groupRuns() {
    // 生命周期钩子必须在关开关时仍恢复和跟踪复用；是否改变视图由绘制器按开关判断。
    val owner = feature
    feature = null
    try {
        val adapter = cls("com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vb.ui.adapter.a")
        val data = adapter.getMethod("d0")
        val head = adapter.getMethod("i0")
        var reported = false
        fun register(row: View, run: RunBinding) {
            if (!row.isAttachedToWindow || pendingRun[row] !== run) return
            val host = generateSequence(row.parent) { it.parent }.filterIsInstance<ViewGroup>()
                .firstOrNull { parent -> generateSequence<Class<*>>(parent.javaClass) { it.superclass }
                    .any { it.name == "androidx.recyclerview.widget.RecyclerView" } } ?: return
            val renderer = runAvatars[host]?.get() ?: RunAvatars(host).also {
                runAvatars[host] = WeakReference(it)
            }
            renderer.bind(row, run)
            if (!reported && run.avatar?.get() != null && run.nick?.get() != null) {
                reported = true; log("TG连发：已绑定原头像与昵称，浮动绘制器已接入")
            }
        }
        // d1/f1 声明可能在共用基类，先筛运行时组件，避免兄弟组件被重复处理。
        for ((type, avatar) in listOf(
            cls("com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent") to true,
            cls("com.tencent.mobileqq.aio.msglist.holder.component.nick.pit.AIONickComponentV2") to false,
        )) {
            val root = type.getMethod("f1")
            hook(type.method("d1")) { chain ->
                if (!type.isInstance(chain.thisObject)) return@hook chain.proceed()
                val inFlight = bindingRun.get()
                val oldView = runCatching { root.invoke(chain.thisObject) as? View }.getOrNull()
                val row = if (inFlight == null) oldView?.let { view ->
                    generateSequence(view) { it.parent as? View }.firstOrNull { pendingRun.containsKey(it) }
                } else null
                // 图片/昵称的局部更新也先还原，不能把自己的 INVISIBLE/GONE 当成 QQ 新状态。
                row?.let { v -> runAvatars.values.mapNotNull { it.get() }.forEach { it.unbind(v) } }
                chain.proceed().also {
                    val run = inFlight ?: row?.let { pendingRun[it] }
                    val view = runCatching { root.invoke(chain.thisObject) as? View }.getOrNull()
                    if (run != null) {
                        if (avatar) run.avatar = WeakReference(view) else run.nick = WeakReference(view)
                        if (row != null) register(row, run)
                    }
                }
            }
        }
        hook(adapter.method("n0")) { chain ->
            val self = chain.thisObject
            val row = runCatching { chain.getArg(0)?.get("itemView") as? View }.getOrNull()
            // 复用前恢复，QQ 本轮绑定的 visibility 才能成为新的原值。
            row?.let { v -> pendingRun.remove(v); runAvatars.values.mapNotNull { it.get() }.forEach { it.unbind(v) } }
            val list = runCatching { data.invoke(self)?.let { it.javaClass.getMethod("u").invoke(it) } as? List<*> }.getOrNull()
            val index = chain.getArg(1) as Int - (head.invoke(self) as Int)
            var start = index
            if (list != null && index in list.indices) {
                while (start > 0 && follows(list[start - 1], list[start])) start--
            }
            val group = list?.getOrNull(start)?.let(::msgId)?.takeIf { it != 0L }
            val run = group?.let { RunBinding(it, start < index) }
            val previous = bindingRun.get()
            bindingRun.set(run)
            try {
                chain.proceed().also {
                    if (row != null && run != null) {
                        pendingRun[row] = run
                        if (row.isAttachedToWindow) register(row, run) else row.post { register(row, run) }
                    }
                }
            } finally {
                bindingRun.set(previous)
            }
        }
        // 绘制位置改变后，点击仍交给 QQ 原头像；不覆盖列表原有的触摸监听器。
        hook(ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)) { chain ->
            val renderer = runAvatars[chain.thisObject]?.get()
            if (renderer?.touch(chain.getArg(0) as MotionEvent) == true) true else chain.proceed()
        }
    } finally { feature = owner }
}

private class RunRow(run: RunBinding) {
    val group = run.group
    val continued = run.continued
    val avatar = run.avatar ?: WeakReference<View>(null)
    val nick = run.nick ?: WeakReference<View>(null)
    val avatarVisibility = avatar.get()?.visibility
    val nickVisibility = nick.get()?.visibility
    fun restore() {
        avatar.get()?.let { if (avatarVisibility != null) it.visibility = avatarVisibility }
        nick.get()?.let { if (nickVisibility != null) it.visibility = nickVisibility }
    }
}

/** 一个列表一层浮动绘制；直接画 QQ 原头像，不缓存图片、不改头像层级、不关父容器裁剪。 */
private class RunAvatars(private val host: ViewGroup) : Drawable(), ViewTreeObserver.OnPreDrawListener,
    View.OnAttachStateChangeListener {
    private val rows = WeakHashMap<View, RunRow>()
    private data class Face(val view: View, val x: Float, val y: Float)
    private val faces = ArrayList<Face>()
    private var pressed: Face? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val origin = IntArray(2)
    private val position = IntArray(2)
    private val visible = Rect()
    init {
        host.overlay.add(this)
        host.viewTreeObserver.addOnPreDrawListener(this)
        host.addOnAttachStateChangeListener(this)
    }
    fun unbind(row: View) {
        val state = rows.remove(row) ?: return
        val avatar = state.avatar.get()
        if (pressed?.view === avatar) { avatar?.cancelPendingInputEvents(); pressed = null }
        faces.removeAll { it.view === avatar }
        state.restore()
    }
    fun bind(row: View, run: RunBinding) { unbind(row); rows[row] = RunRow(run); host.invalidate() }
    override fun onPreDraw(): Boolean {
        faces.clear()
        if (!on("连发合并")) {
            rows.values.forEach { it.restore() }
            pressed?.view?.cancelPendingInputEvents(); pressed = null
            return true
        }
        host.getLocationInWindow(origin)
        if (!host.getLocalVisibleRect(visible)) return true
        setBounds(0, 0, host.width, host.height)
        val groups = LinkedHashMap<Long, MutableList<Pair<View, RunRow>>>()
        val iterator = rows.entries.iterator()
        while (iterator.hasNext()) {
            val (row, state) = iterator.next()
            if (!row.isAttachedToWindow || row.parent !== host) {
                state.restore(); iterator.remove(); continue
            }
            state.nick.get()?.let { nick ->
                val target = if (state.continued) View.GONE else state.nickVisibility
                if (target != null && nick.visibility != target) nick.visibility = target
            }
            groups.getOrPut(state.group) { ArrayList() }.add(row to state)
        }
        for (group in groups.values) {
            var top = Float.POSITIVE_INFINITY
            var bottom = Float.NEGATIVE_INFINITY
            var source: View? = null
            var sourceBottom = Float.NEGATIVE_INFINITY
            for ((row, state) in group) {
                row.getLocationInWindow(position)
                val y = (position[1] - origin[1]).toFloat()
                top = minOf(top, y); bottom = maxOf(bottom, y + row.height)
                val avatar = state.avatar.get() ?: continue
                if (state.avatarVisibility != View.VISIBLE || avatar.width == 0 || avatar.height == 0) continue
                if (avatar.visibility != View.INVISIBLE) avatar.visibility = View.INVISIBLE
                if (y + row.height > sourceBottom) { source = avatar; sourceBottom = y + row.height }
            }
            val avatar = source ?: continue
            val y = floatingAvatarTop(top, bottom, visible.top.toFloat(), visible.bottom.toFloat(),
                avatar.height.toFloat(), 4 * host.dp) ?: continue
            avatar.getLocationInWindow(position)
            faces.add(Face(avatar, (position[0] - origin[0]).toFloat(), y))
        }
        return true
    }
    override fun draw(canvas: Canvas) {
        for (face in faces) {
            val save = canvas.save()
            canvas.clipRect(visible)
            canvas.translate(face.x, face.y)
            face.view.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
    fun touch(event: MotionEvent): Boolean {
        if (!on("连发合并")) { pressed?.view?.cancelPendingInputEvents(); pressed = null; return false }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pressed = faces.lastOrNull { event.x >= it.x && event.x < it.x + it.view.width &&
                event.y >= maxOf(visible.top.toFloat(), it.y) && event.y < minOf(visible.bottom.toFloat(), it.y + it.view.height) }
            downX = event.x; downY = event.y; dragging = false
        }
        val face = pressed ?: return false
        // 点头像仍可起手滚动：原列表接收完整手势，超过系统触摸阈值就取消头像点击。
        host.onTouchEvent(event)
        val move = !dragging && (kotlin.math.abs(event.x - downX) > slop ||
            kotlin.math.abs(event.y - downY) > slop || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(-face.x, -face.y)
        if (move) { copy.action = MotionEvent.ACTION_CANCEL; dragging = true }
        try { if (!dragging || move) face.view.dispatchTouchEvent(copy) } finally { copy.recycle() }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pressed = null
        return true
    }
    override fun onViewDetachedFromWindow(view: View) {
        rows.values.forEach { it.restore() }; faces.clear(); pressed?.view?.cancelPendingInputEvents(); pressed = null
        if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnPreDrawListener(this)
        host.overlay.remove(this)
    }
    override fun onViewAttachedToWindow(view: View) {
        host.overlay.add(this)
        host.viewTreeObserver.addOnPreDrawListener(this)
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: ColorFilter?) = Unit
    @Deprecated("Drawable opacity") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 头像贴组底，但不能越过组首或可见窗口底部；组已完全离屏就不画。 */
internal fun floatingAvatarTop(top: Float, bottom: Float, viewportTop: Float, viewportBottom: Float,
    height: Float, gap: Float): Float? {
    if (height <= 0 || bottom <= viewportTop || top >= viewportBottom || bottom - top < height) return null
    return maxOf(top, minOf(bottom - height - gap, viewportBottom - height - gap))
}

/** 跨日、倒序、不同发送者和系统消息均断组；连续消息只合并五分钟内的。 */
internal fun continuesMessageRun(sender: String?, previousSender: String?, time: Long, previousTime: Long,
    type: Int?, previousType: Int?): Boolean = !sender.isNullOrEmpty() && sender == previousSender &&
    type != null && previousType != null && type !in setOf(5, 29) && previousType !in setOf(5, 29) &&
    time >= previousTime && time - previousTime <= 300 &&
    (time + 8 * 3600) / 86400 == (previousTime + 8 * 3600) / 86400

private fun follows(prev: Any?, cur: Any?): Boolean {
    val a = record(prev) ?: return false
    val b = record(cur) ?: return false
    val t0 = a.get("msgTime") as? Long ?: return false
    val t1 = b.get("msgTime") as? Long ?: return false
    return continuesMessageRun(b.get("senderUid") as? String, a.get("senderUid") as? String,
        t1, t0, b.get("msgType") as? Int, a.get("msgType") as? Int)
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
    val keyOf = act.getMethod("getForwardTargetKey", String::class.java, Integer.TYPE)
    val copyOf = rec.getMethod("copyResultRecord", rec)
    val typeOf = rec.getMethod("getUinType")
    val refresh = act.getMethod("refreshRightBtn")
    hook(act.method("add2ForwardTargetList")) { chain ->
        val r = chain.getArg(0) ?: return@hook false
        val self = chain.thisObject
        val map = self.get("mForwardTargetMap") as? MutableMap<Any, Any?> ?: return@hook chain.proceed()
        val key = keyOf.invoke(self, r.get("uin"), typeOf.invoke(r)) ?: return@hook chain.proceed()
        map[key] = copyOf.invoke(r, r)
        refresh.invoke(self)
        // 顶部那条「已选 N 人」按签名找（void(List, boolean)），找不到就算了，不影响转发本身
        self.get("mSelectedAndSearchBar")?.let { bar ->
            bar.javaClass.declaredMethods
                .firstOrNull { it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(List::class.java, Boolean::class.java)) }
                ?.invoke(bar, ArrayList(map.values), true)
        }
        true
        // ponytail: 搜索页的勾选状态没同步（上游那半是死代码，mSearchFragment 这条路没核）。
        // 真机上搜索页勾选对不上的话，再去找它的 setSelectedAndJoinedUins。
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

// ---- 昵称行只留名字：群等级、头衔、成员等级、会员图标各是一个 block，问「这条消息要不要我」时一律答否。

private val NICK_DROP = setOf(
    "com.tencent.qqnt.aio.gradelevel.AIOTroopMemberGradeLevelBlock",
    "com.tencent.qqnt.aio.mutualmark.AIOTroopHonorNickBlock",
    "com.tencent.qqnt.aio.nick.memberlevel.AIOTroopMemberLevelBlock",
    "com.tencent.mobileqq.vas.vipicon.AIOVipIconProcessor",
    "com.tencent.mobileqq.vas.vipicon.AIOVipIconExProcessor",
    "com.tencent.mobileqq.aio.msglist.holder.component.nick.pit.block.AIONickIconSimpleBlock",
)

fun plainNick() {
    val block = cls("com.tencent.mobileqq.aio.msglist.holder.component.nick.block.LazyNickBlock")
    val viewOf = block.method("h") // 该 block 已经建出来的 View（可能还没有）
    hook(block.method("l")) { chain ->
        val self = chain.thisObject
        if (generateSequence<Class<*>>(self.javaClass) { it.superclass }.none { it.name in NICK_DROP }) return@hook chain.proceed()
        (viewOf.invoke(self) as? View)?.visibility = View.GONE
        false
    }
    // 调用 l() 的两处都是小方法，ART 可能把它们内联，钩子就落空；反优化保证走钩子。
    cls("com.tencent.mobileqq.aio.msglist.holder.component.nick.slot.AIONickSlotContainer").declaredMethods
        .filter { it.name == "d" }.forEach { xposed.deoptimize(it) }
    cls("com.tencent.mobileqq.aio.msglist.holder.component.nick.pit.AIONickComponentV2").declaredMethods
        .filter { it.name == "d1" }.forEach { xposed.deoptimize(it) }
}

// ---- 轻互动（戳一戳那类全屏特效）与表情雨：配置列表永远为空。

fun noLightInteraction() {
    val manager = cls("com.tencent.qqnt.biz.lightbusiness.lightinteraction.LIAConfigManager")
    val lists = manager.declaredMethods.filter { List::class.java.isAssignableFrom(it.returnType) }
    require(lists.isNotEmpty()) { "LIAConfigManager: 没有返回 List 的方法" }
    lists.forEach { m -> hook(m) { ArrayList<Any>() } }
}

fun noEmojiRain() =
    hook(cls("com.tencent.mobileqq.aio.animation.util.AioAnimationConfigHolder").method("e")) { ArrayList<Any>() }
