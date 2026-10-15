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
    if (continued && original > 0) (original - (4 * density).toInt()).coerceAtLeast(0) else original

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

private class RunAvatar(container: View, image: View) {
    val container = WeakReference(container)
    val image = WeakReference(image)
    val visibility = container.visibility
    fun restore() { container.get()?.visibility = visibility }
}
private class RunLayout(val avatars: List<RunAvatar>, nicks: List<View>, contents: List<View>, root: View) {
    val spacing = RunSpacing(root)
    val nicks = nicks.map { WeakReference(it) to it.visibility }
    val contents = contents.map { WeakReference(it) }
    fun apply(continued: Boolean, enabled: Boolean): Boolean {
        var changed = spacing.apply(enabled && continued)
        if (!enabled) avatars.forEach { it.restore() }
        nicks.forEach { (ref, visibility) -> ref.get()?.let { view ->
            val target = if (enabled && continued) View.GONE else visibility
            if (view.visibility != target) { view.visibility = target; changed = true }
        } }
        return changed
    }
    fun restore() = apply(false, false)
}
private class RunBinding(val group: RunKey, val continued: Boolean, var layout: RunLayout? = null)
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
        val vb = cls("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB")
        val vbRoot = vb.getMethod("s1")
        val registry = vb.getMethod("o1")
        val components = cls("com.tencent.mobileqq.aio.msglist.holder.g").getMethod("get")
        val componentRoot = cls("com.tencent.mobileqq.aio.msglist.holder.component.c").getMethod("f1")
        val avatarType = cls("com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent")
        // 真包 C1 的访问标记为 0x12（private final），getMethod 只查 public 会使整项安装失败。
        val avatarImage = avatarType.method("C1").apply { isAccessible = true }
        val nickType = cls("com.tencent.mobileqq.aio.msglist.holder.component.nick.pit.AIONickComponentV2")
        val contentType = cls("com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent")
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
        // 从已绑定 VB 的组件表一次取全，不依赖哪个头像组件先/最后触发 d1。
        hook(vb.method("k1")) { chain ->
            if (!vb.isInstance(chain.thisObject)) return@hook chain.proceed()
            val root = runCatching { vbRoot.invoke(chain.thisObject) as? View }.getOrNull()
            val knownRow = root?.let { generateSequence(it) { v -> v.parent as? View }
                .firstOrNull { v -> pendingRun.containsKey(v) } }
            val context = bindingRun.get() ?: knownRow?.let { row -> pendingRun[row]?.let { row to it } }
            context?.first?.let(::unbind)
            chain.proceed().also {
                if (context != null) {
                    val (row, run) = context
                    run.layout = runCatching {
                        val table = components.invoke(registry.invoke(chain.thisObject)) as SparseArray<*>
                        val avatars = ArrayList<RunAvatar>()
                        val nicks = ArrayList<View>()
                        val contents = ArrayList<View>()
                        for (i in 0 until table.size()) {
                            val c = table.valueAt(i) ?: continue
                            if (!avatarType.isInstance(c) && !nickType.isInstance(c) && !contentType.isInstance(c)) continue
                            val view = componentRoot.invoke(c) as? View ?: continue
                            when {
                                avatarType.isInstance(c) -> {
                                    val image = avatarImage.invoke(c) as? View ?: continue
                                    if (avatars.none { it.container.get() === view }) avatars.add(RunAvatar(view, image))
                                }
                                nickType.isInstance(c) -> nicks.add(view)
                                contentType.isInstance(c) -> contents.add(view)
                            }
                        }
                        RunLayout(avatars, nicks.distinct(), contents.distinct(), vbRoot.invoke(chain.thisObject) as View)
                    }.onFailure { if (!warned) { warned = true; log("TG连发定位：组件读取失败，未改该消息布局", it) } }.getOrNull()
                    // 必须在消息绑定结束、RecyclerView 测量前折叠昵称/间距；不能放到滚动 pre-draw。
                    run.layout?.apply(run.continued, on("连发合并"))
                    register(row, run)
                }
            }
        }
        hook(adapter.method("n0")) { chain ->
            val row = runCatching { chain.getArg(0)?.get("itemView") as? View }.getOrNull()
            row?.let { pendingRun.remove(it); unbind(it) }
            val list = runCatching { data.invoke(chain.thisObject)?.let { it.javaClass.getMethod("u").invoke(it) } as? List<*> }.getOrNull()
            val index = chain.getArg(1) as Int - (head.invoke(chain.thisObject) as Int)
            val run = if (list != null && index in list.indices) {
                val cache = runIndexes[chain.thisObject]?.takeIf { it.first.get() === list && it.second.size == list.size }
                    ?: (WeakReference(list) to RunStarts(list.size)).also { runIndexes[chain.thisObject] = it }
                val start = cache.second.start(index) { i -> follows(list[i - 1], list[i]) }
                list[start]?.let { RunBinding(RunKey(it), start < index) }
            } else null
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

private class RunRow(run: RunBinding, val layout: RunLayout) {
    val group = run.group
    val continued = run.continued
    fun restore() = layout.restore()
}

/** 布局在绑定/设置变化时完成；滚动帧只采样几何并提交到同一帧的 overlay。 */
private class RunAvatars(private val host: ViewGroup) : Drawable(), ViewTreeObserver.OnPreDrawListener,
    View.OnAttachStateChangeListener {
    private val rows = WeakHashMap<View, RunRow>()
    private class Face(val group: RunKey, val view: View, val target: View,
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
        unbind(row)
        run.layout?.let { layout ->
            layout.apply(run.continued, on("连发合并"))
            rows[row] = RunRow(run, layout)
            host.invalidate()
        }
    }
    fun refresh() {
        val enabled = on("连发合并")
        rows.values.forEach { it.layout.apply(it.continued, enabled) }
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
            old.view !== new.view || old.target !== new.target || old.matrix != new.matrix || new.view.isDirty
        }
        faces = next
        viewport = Rect(clip)
        pressed?.let { old ->
            val current = next.firstOrNull { it.target === old.target }
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
        for ((row, state) in rows) if (row.isAttachedToWindow && row.parent === host)
            groups.getOrPut(state.group) { ArrayList() }.add(state)
        val next = ArrayList<Face>()
        for ((key, group) in groups) {
            var firstCenter = Float.POSITIVE_INFINITY
            var lastCenter = Float.NEGATIVE_INFINITY
            for (state in group) {
                var top = Float.POSITIVE_INFINITY
                var bottom = Float.NEGATIVE_INFINITY
                for (ref in state.layout.contents) {
                    val content = ref.get() ?: continue
                    if (!content.isShown) continue
                    val rect = rectangle(content, inHost(content)) ?: continue
                    top = minOf(top, rect.top); bottom = maxOf(bottom, rect.bottom)
                }
                if (top.isFinite() && bottom.isFinite()) {
                    val center = (top + bottom) / 2
                    firstCenter = minOf(firstCenter, center); lastCenter = maxOf(lastCenter, center)
                }
            }
            val avatars = group.flatMap { it.layout.avatars }
            // 尽量保留上帧同一个原图源，不能每滚入一行就切到新的头像组件。
            val preferred = pressed?.takeIf { it.group == key }?.target
                ?: faces.firstOrNull { it.group == key }?.target
            val candidates = avatars.sortedBy { if (it.container.get() === preferred) 0 else 1 }
            var face: Face? = null
            for (avatar in candidates) {
                if (avatar.visibility != View.VISIBLE) continue
                val image = avatar.image.get() ?: continue
                val container = avatar.container.get() ?: continue
                val matrix = inHost(image)
                val rect = rectangle(image, matrix) ?: continue
                val y = floatingAvatarTop(firstCenter, lastCenter, clip.top.toFloat(), clip.bottom.toFloat(),
                    rect.height(), 4 * host.dp) ?: continue
                val delta = y - rect.top
                matrix.postTranslate(0f, delta)
                val targetMatrix = inHost(container).apply { postTranslate(0f, delta) }
                val inverse = Matrix()
                if (!targetMatrix.invert(inverse)) continue
                rect.offset(0f, delta)
                face = Face(key, image, container, matrix, inverse, rect)
                break
            }
            if (face == null) {
                // 几何未就绪时不提前藏掉所有原图；完全离屏的组照常交给列表裁剪。
                avatars.forEach { it.restore() }
            } else {
                avatars.forEach { it.container.get()?.let { view ->
                    if (view.visibility == View.VISIBLE) view.visibility = View.INVISIBLE
                } }
                next.add(face)
            }
        }
        commitFrame(next, clip)
        return true
    }
    override fun draw(canvas: Canvas) {
        for (face in faces) {
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
            pressed = faces.lastOrNull { viewport.contains(event.x.toInt(), event.y.toInt()) && it.rect.contains(event.x, event.y) }
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
        if (!on("连发合并")) cancelPressed()
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
    runAvatars.clear(); pendingRun.clear(); runIndexes.clear(); pendingAttachments.clear(); bindingRun.remove()
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

internal fun messageRunSender(uid: String?, uin: Long?, self: Boolean): String? = when {
    self -> "self"
    uin != null && uin > 0 -> "uin:$uin"
    !uid.isNullOrBlank() -> "uid:$uid"
    else -> null
}

/** 跨日、倒序、不同发送者和系统消息均断组；连续消息只合并五分钟内的。 */
internal fun continuesMessageRun(sender: String?, previousSender: String?, time: Long, previousTime: Long,
    type: Int?, previousType: Int?): Boolean = !sender.isNullOrEmpty() && sender == previousSender &&
    type != null && previousType != null && type !in setOf(5, 29) && previousType !in setOf(5, 29) &&
    time >= previousTime && time - previousTime <= 300 &&
    (time + 8 * 3600) / 86400 == (previousTime + 8 * 3600) / 86400

private fun follows(prev: Any?, cur: Any?): Boolean {
    if (prev === cur) return false
    val previousId = msgId(prev)
    if (previousId != null && previousId != 0L && previousId == msgId(cur)) return false
    val a = record(prev) ?: return false
    val b = record(cur) ?: return false
    val t0 = a.get("msgTime") as? Long ?: return false
    val t1 = b.get("msgTime") as? Long ?: return false
    fun sender(item: Any?, record: Any): String? {
        val self = runCatching { item?.javaClass?.getMethod("isSelf")?.invoke(item) as? Boolean }.getOrNull() == true
        return messageRunSender(record.get("senderUid") as? String, record.get("senderUin") as? Long, self)
    }
    return continuesMessageRun(sender(cur, b), sender(prev, a),
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

// ---- 轻互动（戳一戳那类全屏特效）与表情雨：配置列表永远为空。

fun noLightInteraction() {
    val manager = cls("com.tencent.qqnt.biz.lightbusiness.lightinteraction.LIAConfigManager")
    val lists = manager.declaredMethods.filter { List::class.java.isAssignableFrom(it.returnType) }
    require(lists.isNotEmpty()) { "LIAConfigManager: 没有返回 List 的方法" }
    lists.forEach { m -> hook(m) { ArrayList<Any>() } }
}

fun noEmojiRain() =
    hook(cls("com.tencent.mobileqq.aio.animation.util.AioAnimationConfigHolder").method("e")) { ArrayList<Any>() }
