// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.view.View
import java.lang.reflect.Modifier

// Only stable names: JNI kernel structs, public SDK entry points and unobfuscated QQ classes.
// Every feature is independent; a miss just leaves that switch inactive.

private const val KERNEL = "com.tencent.qqnt.kernel.nativeinterface"

/** Every received message loses its VIP bubble. */
object PlainBubble : Feature("plain_bubble") {
    override fun install() = hookAfterCtors(need("$KERNEL.VASMsgBubble")) {
        setField(it, "bubbleId", 0)
        setField(it, "subBubbleId", 0)
    }
}

object PlainFont : Feature("plain_font") {
    override fun install() = hookAfterCtors(need("$KERNEL.VASMsgFont")) {
        setField(it, "fontId", 0)
        setField(it, "magicFontType", 0)
    }
}

object NoPendant : Feature("no_pendant") {
    override fun install() = hookAfterCtors(need("$KERNEL.VASMsgAvatarPendant")) {
        setField(it, "pendantId", 0L)
        setField(it, "pendantDiyInfoId", 0)
    }
}

/**
 * The kernel learns about recalls from MSF pushes. Drop the recall pushes,
 * strip the recall section from sync pushes, pass everything else through.
 */
object AntiRecall : Feature("anti_recall") {
    private const val MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush"
    private const val SYNC_PUSH = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush"

    override fun install() {
        val proxy = need("$KERNEL.IQQNTWrapperSession\$CppProxy")
        val push = proxy.declaredMethods.filter { it.name == "onMsfPush" && it.parameterCount in 2..3 }
        for (m in push) hook(m) { chain ->
            val cmd = chain.getArg(0) as? String
            val body = chain.getArg(1) as? ByteArray
            when {
                body == null -> chain.proceed()
                cmd == MSG_PUSH && Proto.isRecallPush(body) -> null
                cmd == SYNC_PUSH -> {
                    val stripped = Proto.stripField(body, 8)
                    if (stripped === body) chain.proceed()
                    else chain.proceed(chain.args.toTypedArray().also { it[1] = stripped })
                }
                else -> chain.proceed()
            }
        }
        check(push.isNotEmpty(), "onMsfPush")
    }
}

object MultiForward : Feature("multi_forward") {
    private val slots = arrayOf("friendLayout", "contactLayout", "troopDiscussionLayout", "multiChatLayout")

    override fun install() {
        val m = need("com.tencent.mobileqq.activity.ForwardRecentActivity").getDeclaredMethod("initEntryHeaderView")
        hook(m) { chain ->
            val r = chain.proceed()
            val self = chain.thisObject
            if (self != null) for (s in slots) runCatching {
                val f = self.javaClass.getDeclaredField(s).apply { isAccessible = true }
                (f.get(self) as? View)?.visibility = View.VISIBLE
            }
            r
        }
    }
}

object NoLightInteraction : Feature("no_light_interaction") {
    override fun install() {
        val c = need("com.tencent.qqnt.biz.lightbusiness.lightinteraction.LIAConfigManager")
        val lists = c.declaredMethods.filter { it.returnType == List::class.java }
        lists.forEach { constant(it, ArrayList<Any>()) }
        check(lists.isNotEmpty(), "LIAConfigManager lists")
    }
}

object NoDropSticker : Feature("no_drop_sticker") {
    override fun install() {
        // 9.2.10: AioAnimationConfigHolder.e(): List holds the egg rules.
        val c = cls("com.tencent.mobileqq.aio.animation.util.AioAnimationConfigHolder")
            ?: need("com.tencent.mobileqq.aio.animation.util.AioAnimationConfigHelper")
        val rules = c.declaredMethods.filter {
            it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType)
        }
        rules.forEach { constant(it, ArrayList<Any>()) }
        check(rules.isNotEmpty(), "AioAnimationConfig rules")
    }
}

object SystemWebView : Feature("system_webview", mainOnly = false) {
    override fun install() =
        constant(need("com.tencent.smtt.sdk.QbSdk").getDeclaredMethod("getIsSysWebViewForcedByOuter"), true)
}

/** Only void/boolean entry points are silenced, so callers never see a surprising null. */
object NoTelemetry : Feature("no_telemetry", mainOnly = false) {
    override fun install() {
        var n = 0
        cls("com.tencent.beacon.event.UserAction")?.declaredMethods?.forEach { m ->
            if (!Modifier.isPublic(m.modifiers)) return@forEach
            when {
                m.name.startsWith("onUserAction") && m.returnType == Boolean::class.javaPrimitiveType -> { constant(m, false); n++ }
                m.name.startsWith("initUserAction") && m.returnType == Void.TYPE -> { constant(m, null); n++ }
            }
        }
        cls("com.tencent.beacon.event.open.BeaconReport")?.declaredMethods?.forEach { m ->
            if (m.name == "report" && m.returnType == Void.TYPE) { constant(m, null); n++ }
        }
        cls("com.tencent.mobileqq.statistics.StatisticCollector")?.declaredMethods?.forEach { m ->
            if (Modifier.isPublic(m.modifiers) && m.returnType == Void.TYPE &&
                (m.name.startsWith("collectPerformance") || m.name.startsWith("report"))
            ) { constant(m, null); n++ }
        }
        check(n > 0, "telemetry entry points")
    }
}

object NoCrashReport : Feature("no_crash_report", mainOnly = false) {
    override fun install() {
        var n = 0
        for (name in listOf("com.tencent.bugly.crashreport.CrashReport", "com.tencent.feedback.eup.CrashReport")) {
            cls(name)?.declaredMethods?.forEach { m ->
                if (Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.name.startsWith("init")) {
                    constant(m, null); n++
                }
            }
        }
        check(n > 0, "crash report init")
    }
}
