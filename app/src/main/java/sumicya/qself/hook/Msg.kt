// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.view.View
import java.lang.reflect.Modifier

/**
 * 消息层：VAS 会员结构、防撤回、转发页、「+」面板、昵称、轻互动、表情雨。
 *
 * 每个类名都是真 dex 里核过的完整名字（见 tools/symbols.txt）。名字对不上就让开关直接失败，
 * 不许静默失效 —— 报告里会写「✗ 失败: ClassNotFoundException(...)」，LSPosed 日志里也有。
 */

/** 每条收到的消息都掉 VIP 气泡。 */
object PlainBubble : Switch("plain_bubble") {
    override fun install() = afterConstructed(need("com.tencent.qqnt.kernel.nativeinterface.VASMsgBubble")) {
        setField(it, "bubbleId", 0)
        setField(it, "subBubbleId", 0)
    }
}

/** 会员字体和魔法字都换成默认。 */
object PlainFont : Switch("plain_font") {
    override fun install() = afterConstructed(need("com.tencent.qqnt.kernel.nativeinterface.VASMsgFont")) {
        setField(it, "fontId", 0)
        setField(it, "magicFontType", 0)
    }
}

/** 头像挂件。 */
object NoPendant : Switch("no_pendant") {
    override fun install() = afterConstructed(need("com.tencent.qqnt.kernel.nativeinterface.VASMsgAvatarPendant")) {
        setField(it, "pendantId", 0L)
        setField(it, "pendantDiyInfoId", 0)
    }
}

/**
 * 内核从 MSF 推送里知道谁撤回了消息：丢掉撤回推送，从同步推送里抠掉撤回那一段，别的原样放行。
 *
 * 真 dex 里这个方法只有一种形态：IQQNTWrapperSession$CppProxy.onMsfPush(String, byte[], PushExtraInfo)。
 * 但 QQ 自带 QFix，这个类上有 $redirector_ 字段 —— 运行时它还往里插参数，真实参数表跟 dex 不一样。
 * 所以命令和正文按类型认，不按位置。
 */
object AntiRecall : Switch("anti_recall") {
    private const val MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush"
    private const val SYNC_PUSH = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush"

    override fun install() {
        val pushes = need("com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession\$CppProxy")
            .declaredMethods
            .filter { it.name == "onMsfPush" && it.parameterCount >= 2 }
        for (method in pushes) {
            hook(method) { chain ->
                val args = chain.args
                val command = args.firstOrNull { it is String } as? String
                val at = args.indexOfFirst { it is ByteArray }
                val body = if (at < 0) null else args[at] as ByteArray
                when {
                    body == null -> chain.proceed()
                    command == MSG_PUSH && Proto.isRecallPush(body) -> null
                    command == SYNC_PUSH -> {
                        val stripped = Proto.without(body, 8)
                        if (stripped === body) chain.proceed()
                        else chain.proceed(args.toMutableList().also { it[at] = stripped }.toTypedArray())
                    }
                    else -> chain.proceed()
                }
            }
        }
        require(pushes.isNotEmpty(), "onMsfPush")
    }
}

/** 转发页永远显示好友、群、多选这几个入口。 */
object MultiForward : Switch("multi_forward") {
    private val slots = listOf("contactLayout", "friendLayout", "multiChatLayout", "troopDiscussionLayout")

    override fun install() {
        val m = need("com.tencent.mobileqq.activity.ForwardRecentActivity").getDeclaredMethod("initEntryHeaderView")
        hook(m) { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { self ->
                for (slot in slots) runCatching {
                    val f = self.javaClass.getDeclaredField(slot).apply { isAccessible = true }
                    (f.get(self) as? View)?.visibility = View.VISIBLE
                }
            }
            result
        }
    }
}

/**
 * 「+」面板精简成 TG 的附件菜单：照片、拍摄、文件、位置、红包留着，一起派对、礼物、直播间这类去掉。
 *
 * 9.2.10 把拉到的入口存在 PlusPanelUiState.FetchCompleted 的私有 ArrayList 字段 d 里，通过
 * getter a() 发出来 —— 所以过滤要挂在 getter 上：每次取都从存着的 list 里剔掉不要的标题。
 */
object PlusPanel : Switch("tg_plus_panel") {
    private val drop = setOf(
        "一起派对", "好友爱玩", "一起看", "一起K歌", "一起听歌", "一起玩", "礼物", "厘米秀",
        "短视频", "直播间", "群课堂", "作业", "匿名送礼", "赞赏照片", "匿问我答", "滤镜", "涂鸦",
    )

    private fun titles(o: Any): Sequence<String> = sequence {
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type == String::class.java && !Modifier.isStatic(f.modifiers)) {
                    f.isAccessible = true
                    (f.get(o) as? String)?.let { yield(it) }
                }
            }
            c = c.superclass
        }
    }

    private fun dropped(item: Any?): Boolean =
        item != null && runCatching { titles(item).any { it in drop } }.getOrDefault(false)

    override fun install() {
        val getters = need("com.tencent.qqnt.pluspanel.data.PlusPanelUiState\$FetchCompleted")
            .declaredMethods
            .filter { List::class.java.isAssignableFrom(it.returnType) && it.parameterCount <= 1 }
        getters.forEach { method ->
            hook(method) { chain ->
                val result = chain.proceed()
                (result as? MutableList<Any?>)?.removeAll { dropped(it) }
                result
            }
        }
        require(getters.isNotEmpty(), "FetchCompleted 的 list getter")
    }
}

/**
 * 昵称行只留名字：群等级、头衔、成员等级、会员图标在 QQ 里是各自独立的 block，
 * 每个都会问一句「要不要绑自己」—— 让它们答否，顺便把已经 inflate 出来的 view 藏掉。
 *
 * LazyNickBlock.l(AIOMsgItem) 返回 boolean，就是那一问；9.2.10 里它是 public final。
 */
object PlainNick : Switch("plain_nick") {
    private val block = setOf(
        "com.tencent.qqnt.aio.gradelevel.AIOTroopMemberGradeLevelBlock",
        "com.tencent.qqnt.aio.mutualmark.AIOTroopHonorNickBlock",
        "com.tencent.qqnt.aio.nick.memberlevel.AIOTroopMemberLevelBlock",
        "com.tencent.mobileqq.vas.vipicon.AIOVipIconProcessor",
        "com.tencent.mobileqq.vas.vipicon.AIOVipIconExProcessor",
        "com.tencent.mobileqq.aio.msglist.holder.component.nick.pit.block.AIONickIconSimpleBlock",
    )

    private fun dropped(o: Any?): Boolean {
        var c: Class<*>? = o?.javaClass
        while (c != null) {
            if (c.name in block) return true
            c = c.superclass
        }
        return false
    }

    override fun install() {
        val viewOf = need("com.tencent.mobileqq.aio.msglist.holder.component.nick.block.a").getDeclaredMethod("h")
        val questions = need("com.tencent.mobileqq.aio.msglist.holder.component.nick.block.LazyNickBlock")
            .declaredMethods.filter { it.name == "l" }
        questions.forEach { method ->
            hook(method) { chain ->
                val self = chain.thisObject
                if (dropped(self)) {
                    runCatching { (viewOf.invoke(self) as? View)?.visibility = View.GONE }
                    false
                } else {
                    chain.proceed()
                }
            }
        }
        require(questions.isNotEmpty(), "LazyNickBlock.l")
        // 昵称槽里真正拼内容的那两个方法很小，ART 会把它们内联掉，钩子就看不见了。
        cls("com.tencent.mobileqq.aio.msglist.holder.component.nick.slot.AIONickSlotContainer")
            ?.declaredMethods?.filter { it.name == "d" }?.forEach { deopt(it) }
        cls("com.tencent.mobileqq.aio.msglist.holder.component.nick.pit.AIONickComponentV2")
            ?.declaredMethods?.filter { it.name == "d1" }?.forEach { deopt(it) }
    }
}

/** 早安、晚安、戳一戳这类轻互动直接别发。 */
object NoLightInteraction : Switch("no_light_interaction") {
    override fun install() {
        val lists = need("com.tencent.qqnt.biz.lightbusiness.lightinteraction.LIAConfigManager")
            .declaredMethods.filter { it.returnType == List::class.java }
        lists.forEach { constant(it, ArrayList<Any>()) }
        require(lists.isNotEmpty(), "LIAConfigManager 的 list")
    }
}

/** 关键词触发的全屏掉表情。 */
object NoDropSticker : Switch("no_drop_sticker") {
    override fun install() {
        val rules = need("com.tencent.mobileqq.aio.animation.util.AioAnimationConfigHolder")
            .declaredMethods
            .filter { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
        rules.forEach { constant(it, ArrayList<Any>()) }
        require(rules.isNotEmpty(), "AioAnimationConfigHolder 的规则表")
    }
}
