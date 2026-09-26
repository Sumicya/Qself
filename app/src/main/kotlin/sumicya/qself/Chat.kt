// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.view.View

/** 聊天：会员装饰归零、防撤回、转发、「+」面板、昵称行、轻互动、表情雨。 */

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

// ---- 防撤回：服务器推来的撤回通知在进 NT 内核之前吞掉，消息留在本地。
// ponytail: 不加「对方撤回了一条消息」灰字；消息就像没被撤回过。要灰字得写内核 DB，不值。

private const val MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush"
private const val INFO_SYNC = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush"

fun antiRecall() {
    val proxy = cls("com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession\$CppProxy")
    hook(proxy.method("onMsfPush")) { chain ->
        val cmd = chain.getArg(0) as? String
        val body = chain.getArg(1) as? ByteArray
        when {
            body == null -> chain.proceed()
            cmd == MSG_PUSH && isRecall(body) -> null
            cmd == INFO_SYNC -> chain.proceed(chain.args.toTypedArray().also { it[1] = stripSyncRecall(body) })
            else -> chain.proceed()
        }
    }
}

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

/** MsgPush{1: Message{2: ContentHead{1: type, 2: subType}, 3: Body{2: content}}}；私聊撤回 528/138，群撤回 732/17 且 op_type 7。 */
fun isRecall(push: ByteArray): Boolean {
    val message = push.pb().bytes(1)?.pb() ?: return false
    val head = message.bytes(2)?.pb() ?: return false
    return when (head.long(1) to head.long(2)) {
        528L to 138L -> true
        732L to 17L -> {
            val content = message.bytes(3)?.pb()?.bytes(2) ?: return false
            content.size > 7 && content.copyOfRange(7, content.size).pb().long(1) == 7L
        }
        else -> false
    }
}

/** InfoSyncPush 的字段 8 是 SyncMsgRecall（重连补发的撤回），整段剪掉，其余原样。 */
fun stripSyncRecall(sync: ByteArray): ByteArray {
    val fields = sync.pb()
    if (fields.none { it.num == 8 }) return sync
    val out = java.io.ByteArrayOutputStream(sync.size)
    fields.filter { it.num != 8 }.forEach { out.write(sync, it.start, it.end - it.start) }
    return out.toByteArray()
}

// ---- 转发页：好友/群/多选入口一直显示。

fun multiForward() {
    val slots = listOf("contactLayout", "friendLayout", "multiChatLayout", "troopDiscussionLayout")
    hook(cls("com.tencent.mobileqq.activity.ForwardRecentActivity").method("initEntryHeaderView")) { chain ->
        chain.proceed().also { slots.forEach { s -> (chain.thisObject.get(s) as? View)?.visibility = View.VISIBLE } }
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
