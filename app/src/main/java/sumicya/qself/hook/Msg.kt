// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.view.View
import java.lang.reflect.Modifier

// Only stable names live here: the NT kernel JNI structs, public SDK entry points and
// unobfuscated QQ classes. Every switch is independent, and a miss only leaves that switch off.

private const val KERNEL = "com.tencent.qqnt.kernel.nativeinterface"

/** Every received message loses its VIP bubble. */
object PlainBubble : Feature("plain_bubble") {
    override fun install() = afterConstructed(need("$KERNEL.VASMsgBubble")) {
        setField(it, "bubbleId", 0)
        setField(it, "subBubbleId", 0)
    }
}

object PlainFont : Feature("plain_font") {
    override fun install() = afterConstructed(need("$KERNEL.VASMsgFont")) {
        setField(it, "fontId", 0)
        setField(it, "magicFontType", 0)
    }
}

object NoPendant : Feature("no_pendant") {
    override fun install() = afterConstructed(need("$KERNEL.VASMsgAvatarPendant")) {
        setField(it, "pendantId", 0L)
        setField(it, "pendantDiyInfoId", 0)
    }
}

/**
 * The kernel learns about recalls from MSF pushes. Drop the recall pushes, strip the recall
 * section from sync pushes, pass everything else through untouched.
 *
 * The push handler does not look the same in every QQ build: QFix rewrites it, so the real
 * signature is onMsfPush(byte, String, byte[]) and the leading byte is a dummy. Pick the command
 * and the body out by type instead of by position.
 */
object AntiRecall : Feature("anti_recall") {
    private const val MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush"
    private const val SYNC_PUSH = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush"

    override fun install() {
        val proxy = need("$KERNEL.IQQNTWrapperSession\$CppProxy")
        val pushes = proxy.declaredMethods.filter { it.name == "onMsfPush" && it.parameterCount in 2..4 }
        pushes.forEach { method ->
            hook(method) { chain ->
                val args = chain.args
                val command = args.firstOrNull { it is String } as? String
                val body = args.firstOrNull { it is ByteArray } as? ByteArray
                val bodyAt = args.indexOfFirst { it is ByteArray }
                when {
                    body == null || bodyAt < 0 -> chain.proceed()
                    command == MSG_PUSH && Proto.isRecallPush(body) -> null
                    command == SYNC_PUSH -> {
                        val stripped = Proto.without(body, 8)
                        if (stripped === body) chain.proceed()
                        else chain.proceed(args.toMutableList().also { it[bodyAt] = stripped }.toTypedArray())
                    }
                    else -> chain.proceed()
                }
            }
        }
        require(pushes.isNotEmpty(), "onMsfPush")
    }
}

object MultiForward : Feature("multi_forward") {
    private val slots = arrayOf("friendLayout", "contactLayout", "troopDiscussionLayout", "multiChatLayout")

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
 * 「+」 panel as a Telegram attach menu: photos, camera, files, location, money and tools stay;
 * play-together, gifts, short video and live rooms go.
 *
 * QQ 9.2.10 keeps the fetched entries in a private ArrayList field of PlusPanelUiState.FetchCompleted
 * and hands them out through a getter, so the getter is where the list gets filtered — each call
 * takes the dropped titles out of the stored list and gives back the same list.
 */
object PlusPanel : Feature("tg_plus_panel") {
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
        val state = need("com.tencent.qqnt.pluspanel.data.PlusPanelUiState\$FetchCompleted")
        val getters = state.declaredMethods.filter {
            (List::class.java.isAssignableFrom(it.returnType)) && it.parameterCount <= 1
        }
        getters.forEach { method ->
            hook(method) { chain ->
                val result = chain.proceed()
                (result as? MutableList<Any?>)?.removeAll { dropped(it) }
                result
            }
        }
        require(getters.isNotEmpty(), "FetchCompleted list getter")
    }
}

/**
 * Telegram nick line: only the sender's name. Group level, honor, member-level tags and VIP icons
 * are separate blocks in QQ's nick slot, each asked to bind itself; those answer no, and the views
 * they already inflated are hidden.
 *
 * The question is LazyNickBlock.l(...) — a boolean that QFix rewrites with a dummy leading byte, so
 * the parameter list differs between builds. Hook it by name and let the return value do the work.
 */
object PlainNick : Feature("plain_nick") {
    private const val NICK = "com.tencent.mobileqq.aio.msglist.holder.component.nick"
    private val drop = setOf(
        "com.tencent.qqnt.aio.gradelevel.AIOTroopMemberGradeLevelBlock",
        "com.tencent.qqnt.aio.mutualmark.AIOTroopHonorNickBlock",
        "com.tencent.qqnt.aio.nick.memberlevel.AIOTroopMemberLevelBlock",
        "com.tencent.mobileqq.vas.vipicon.AIOVipIconProcessor",
        "com.tencent.mobileqq.vas.vipicon.AIOVipIconExProcessor",
        "$NICK.pit.block.AIONickIconSimpleBlock",
    )

    private fun dropped(o: Any?): Boolean {
        var c: Class<*>? = o?.javaClass
        while (c != null) {
            if (c.name in drop) return true
            c = c.superclass
        }
        return false
    }

    override fun install() {
        val view = need("$NICK.block.a").getDeclaredMethod("h")
        val questions = need("$NICK.block.LazyNickBlock").declaredMethods.filter { it.name == "l" }
        questions.forEach { method ->
            hook(method) { chain ->
                val self = chain.thisObject
                if (dropped(self)) {
                    runCatching { (view.invoke(self) as? View)?.visibility = View.GONE }
                    false
                } else {
                    chain.proceed()
                }
            }
        }
        require(questions.isNotEmpty(), "LazyNickBlock.l")
        listOf("$NICK.slot.AIONickSlotContainer" to "d", "$NICK.pit.AIONickComponentV2" to "d1").forEach { (name, method) ->
            cls(name)?.declaredMethods?.filter { it.name == method }?.forEach { deopt(it) }
        }
    }
}

object NoLightInteraction : Feature("no_light_interaction") {
    override fun install() {
        val lists = need("com.tencent.qqnt.biz.lightbusiness.lightinteraction.LIAConfigManager")
            .declaredMethods.filter { it.returnType == List::class.java }
        lists.forEach { constant(it, ArrayList<Any>()) }
        require(lists.isNotEmpty(), "LIAConfigManager lists")
    }
}

object NoDropSticker : Feature("no_drop_sticker") {
    override fun install() {
        // 9.2.10: AioAnimationConfigHolder.e() returns the egg rules.
        val c = cls("com.tencent.mobileqq.aio.animation.util.AioAnimationConfigHolder")
            ?: need("com.tencent.mobileqq.aio.animation.util.AioAnimationConfigHelper")
        val rules = c.declaredMethods.filter { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
        rules.forEach { constant(it, ArrayList<Any>()) }
        require(rules.isNotEmpty(), "AioAnimationConfig rules")
    }
}
