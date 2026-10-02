// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

/** 聊天：会员装饰归零、防撤回、连发合并、转发、「+」面板、昵称行、轻互动、表情雨。 */

// ---- 会员装饰：NT 内核把气泡/字体/挂件放在几个纯数据结构里，构造完立刻清零，界面就按默认画。

fun plainDecor() {
    cls("com.tencent.qqnt.kernel.nativeinterface.VASMsgBubble")
        .afterNew { it.set("bubbleId", 0); it.set("subBubbleId", 0) }
    cls("com.tencent.qqnt.kernel.nativeinterface.VASMsgFont")
        .afterNew { it.set("fontId", 0); it.set("magicFontType", 0) }
    cls("com.tencent.qqnt.kernel.nativeinterface.VASMsgAvatarPendant")
        .afterNew { it.set("pendantId", 0L); it.set("pendantDiyInfoId", 0) }
}


// ---- 防撤回：服务器推来的撤回通知在进 NT 内核之前整个吞掉，消息留在本地；同时把被撤回的消息
// 按「会话类型:对方:seq」记进 SharedPreferences，列表绑定每一行时拿 MsgRecord 对 key，
// 命中就压半透明、右上角画一个 ✗。全程同步 —— 不按 seq 回内核捞消息，也不插灰字。

private const val MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush"
private const val INFO_SYNC = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush"

/** 被撤回消息的 key（「类型:peerUid:seq」），存 SharedPreferences「recalled」；ponytail: 只留最近 500 条，更早的标记会消失。 */
private val recalled: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
private val loaded by lazy { store()?.getString("recalled", null)?.split(',')?.filterTo(recalled) { ':' in it }; true }

fun antiRecall() {
    val proxy = cls("com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession\$CppProxy")
    hook(proxy.method("onMsfPush")) { chain ->
        val cmd = chain.getArg(0) as? String
        val body = chain.getArg(1) as? ByteArray
        when {
            body == null -> chain.proceed()
            cmd == MSG_PUSH && recall(body).also(::remember) != null -> null // 吞
            cmd == INFO_SYNC -> chain.proceed(chain.args.toTypedArray().also { it[1] = stripSyncRecall(body) })
            else -> chain.proceed()
        }
    }
    // 列表绑定每一行时标记。k1 / s1 声明在 AIOBubbleMsgItemVB 本类（dex 核过）；
    // 参数是 AIOMsgItem 或它基类的兄弟子类，取记录包在 runCatching 里，兄弟走进来就当没这回事。
    val vb = cls("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB")
    hook(vb.method("k1")) { chain ->
        chain.proceed().also {
            val root = runCatching { vb.getMethod("s1").invoke(chain.thisObject) as? View }.getOrNull() ?: return@also
            val rec = record(chain.getArg(1)) ?: return@also
            mark(root, loaded && "${rec.get("chatType")}:${rec.get("peerUid")}:${rec.get("msgSeq")}" in recalled)
        }
    }
}

/**
 * 一次撤回推送 → 标记 key 列表；**返回 null = 不是撤回（或判不出来），不吞**。
 * MsgPush{1: Message{2: ContentHead{1: type, 2: subType}, 3: Body{2: content}}}。
 * 私聊撤回 528/138：content = FriendRecall{1: Info{1: fromUid, 2: toUid, 3: seq}}；
 * 群撤回 732/17：content = 4 字节群号 + 类型 + 长度 + NotifyMsgBody{1: type(7 = 撤回), 4: 群号, 11: Recall{3: [{1: seq}]}}。
 *
 * ponytail: 私聊撤回 from / to 两个 uid 各记一条 —— 自己在别的设备上撤回时 from 是自己、
 * 会话的 peerUid 是对方，懒得判自己是谁，两条都记总有一条对上。
 */
fun recall(push: ByteArray): List<String>? {
    val message = push.pb().bytes(1)?.pb() ?: return null
    val head = message.bytes(2)?.pb() ?: return null
    val content = message.bytes(3)?.pb()?.bytes(2)
    return when (head.long(1) to head.long(2)) {
        528L to 138L -> {
            val info = content?.pb()?.bytes(1)?.pb()
            val seq = info?.long(3) ?: return emptyList() // 解不出细节也照样吞，只是标不了
            listOfNotNull(info.bytes(1)?.let { "1:${String(it)}:$seq" }, info.bytes(2)?.let { "1:${String(it)}:$seq" })
        }
        732L to 17L -> {
            // 群通知共用这个通道，判不出 opType=7 的一律放行，别吞错
            if (content == null || content.size <= 7) return null
            val body = content.copyOfRange(7, content.size).pb()
            if (body.long(1) != 7L) return null
            val group = body.long(4)?.toString().orEmpty()
            body.bytes(11)?.pb()?.filter { it.num == 3 }
                ?.mapNotNull { (it.value as? ByteArray)?.pb()?.long(1)?.let { s -> "2:$group:$s" } }.orEmpty()
        }
        else -> null
    }
}

private fun remember(keys: List<String>?) {
    if (keys.isNullOrEmpty() || !loaded) return
    recalled += keys
    store()?.edit()?.putString("recalled", synchronized(recalled) { recalled.toList() }.takeLast(500).joinToString(","))?.apply()
}

// ---- 撤回标记：压半透明 + 右上角一个画出来的 ✗（系统字体里叉的字形各家各样，自己画才稳）。
// ✗ 走 ViewOverlay：不进视图树，行复用、布局变化都不干扰。

private val marks = WeakHashMap<View, Drawable>()

private fun mark(root: View, on: Boolean) {
    if (on) {
        root.alpha = 0.5f
        if (root !in marks) {
            val x = XMark(root.dp)
            marks[root] = x
            root.overlay.add(x)
            // ponytail: 等一帧布局完再摆位，✗ 只摆这一次；转屏 / 改字号后摆歪了就再加 layout 监听。
            root.post { if (marks[root] === x && root.width > 0) {
                val s = (12 * root.dp).toInt(); val m = (6 * root.dp).toInt()
                x.setBounds(root.width - s - m, m, root.width - m, m + s)
            } }
        }
    } else if (root in marks || root.alpha == 0.5f) {
        root.alpha = 1f
        marks.remove(root)?.let(root.overlay::remove)
    }
}

private class XMark(dp: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * dp
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFF453A.toInt()
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val u = min(b.width(), b.height()) * 0.32f
        c.drawLine(cx - u, cy - u, cx + u, cy + u, paint)
        c.drawLine(cx + u, cy - u, cx - u, cy + u, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
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

// ---- 连发合并：同一人 5 分钟内连着发的消息，后面那些不再画头像（占位留着，气泡不会往边上挪）和昵称行。
// 列表适配器绑定每一行时先看它跟上一行是不是「连发」，头像 / 昵称组件绑定完再按结果折起来。

private val grouped: MutableSet<Long> = Collections.newSetFromMap(ConcurrentHashMap())
private val folded = WeakHashMap<View, Int>()

fun groupRuns() {
    val adapter = cls("com.tencent.aio.part.root.panel.content.firstLevel.msglist.mvx.vb.ui.adapter.a")
    // 取数据和取表头数的方法装的时候就解析好：找不到直接 ✗ 报出来，别在每次绑定时静悄悄空转。
    val data = adapter.getMethod("d0")
    val head = adapter.getMethod("i0")
    hook(adapter.method("n0")) { chain ->
        val self = chain.thisObject
        val list = runCatching { data.invoke(self)?.let { it.javaClass.getMethod("u").invoke(it) } as? List<*> }.getOrNull()
        val i = chain.getArg(1) as Int - (runCatching { head.invoke(self) as Int }.getOrNull() ?: 0)
        val cur = list?.getOrNull(i)
        msgId(cur)?.let { if (follows(list?.getOrNull(i - 1), cur)) grouped.add(it) else grouped.remove(it) }
        chain.proceed()
    }
    val avatar = cls("com.tencent.mobileqq.aio.msglist.holder.component.avatar.AIOAvatarContentComponent")
    val nick = cls("com.tencent.mobileqq.aio.msglist.holder.component.nick.pit.AIONickComponentV2")
    for ((c, mode) in listOf(avatar to View.INVISIBLE, nick to View.GONE)) {
        val root = c.getMethod("f1") // 组件的根视图，声明在 MVVM 基类上
        hook(c.method("d1")) { chain ->
            // 先还原上一次折的，让 QQ 自己的绑定从干净状态开始；绑完再按需要折。
            val v = runCatching { root.invoke(chain.thisObject) as? View }.getOrNull()
            v?.let { view -> folded.remove(view)?.let { view.visibility = it } }
            chain.proceed().also {
                val id = runCatching { msgId(chain.getArg(1)) }.getOrNull()
                if (v != null && id != null && id in grouped) { folded[v] = v.visibility; v.visibility = mode }
            }
        }
    }
}

/** 两条都是正经消息（不是灰字 5 / 开场白 29）、同一发送者、相隔不到 5 分钟。 */
private fun follows(prev: Any?, cur: Any?): Boolean {
    val a = record(prev) ?: return false
    val b = record(cur) ?: return false
    val sender = a.get("senderUid") as? String
    return !sender.isNullOrEmpty() && sender == b.get("senderUid") &&
        a.get("msgType") !in setOf(5, 29) && b.get("msgType") !in setOf(5, 29) &&
        abs((b.get("msgTime") as Long) - (a.get("msgTime") as Long)) <= 300
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
