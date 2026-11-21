// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

/**
 * 自由化 + 净化：不要 X5、不要统计、不要崩溃上报、不要红点和会员等级。
 *
 * 「每进程都装」指前三个（[systemWebView] / [noTelemetry] / [noCrashReport]）：
 * 它们由 Qself.kt 的功能表按 main=false 登记，每个进程都会跑一遍。
 */

/** X5 内核问「外部是否强制系统 WebView」，答是。 */
fun systemWebView() =
    cls("com.tencent.smtt.sdk.QbSdk").constant(true) { it.name == "getIsSysWebViewForcedByOuter" }

/** 灯塔 (beacon) 的事件上报与 QQ 自家 StatisticCollector 全部空转；SDK 照常初始化，登录风控不受影响。 */
fun noTelemetry() {
    cls("com.tencent.beacon.event.UserAction").apply {
        // 布尔门：问「能不能上报」，答 false。
        constant(false) { it.returnType == java.lang.Boolean.TYPE && (it.name.startsWith("on") || it.name == "loginEvent") }
        // void 门：直接上报的动作，答什么都不会被读，返回 null。
        constant(null) { it.returnType == Void.TYPE && (it.name.startsWith("onPage") || it.name == "doUploadRecords") }
    }

    // report() 的调用方会读返回值，给一个 errorCode=1（非 0 即失败）的真对象而不是 null——
    // 返回 null 的话调用方多半会 NPE，那是把「不上报」变成「崩溃」。
    cls("com.tencent.beacon.event.open.BeaconReport").apply {
        val result = cls("com.tencent.beacon.event.open.EventResult")
            .getConstructor(Integer.TYPE, java.lang.Long.TYPE, String::class.java)
        hook(method("report")) { result.newInstance(1, 0L, "") }
    }

    cls("com.tencent.mobileqq.statistics.StatisticCollector")
        .constant(null) { it.returnType == Void.TYPE && (it.name.startsWith("collectPerformance") || it.name.startsWith("report")) }
}

/** RQD/Bugly 不初始化：崩溃就按系统的来，不往腾讯传栈。 */
fun noCrashReport() =
    cls("com.tencent.feedback.eup.CrashReport")
        .constant(null) { it.name.startsWith("initCrashReport") && it.returnType == Void.TYPE }

// ── 门方法 ───────────────────────────────────────────────────────────────────

/**
 * 一组「门方法」：类名 → 方法名们，一律答 false。
 *
 * 三种没命中的情形必须分开报，因为处置完全不同：
 *   ① 类不存在      —— QQ 这一版没有这个类，多半是功能被删了，属于正常退化；
 *   ② 类在、方法不在 —— 名字抄错了，或者 QQ 改了名，属于**我这边**的问题；
 *   ③ 类在、方法在但不是 boolean —— 签名对不上，同样是**我这边**的问题。
 * 原先三种都报同一句「门方法未命中」，排查时分不清该怪 QQ 还是怪自己。
 *
 * 单个没命中只写日志不抛：每个门各管各的，少一个不影响其余。
 * 但一个都没命中就抛——那说明整项功能完全没生效，面板该显示 ✗ 而不是假装开着。
 */
/**
 * 门方法的解析结果。抽成数据类是为了能在 JVM 上测「三种没命中怎么分类」——
 * 那段判断原先和反射调用搅在一起，一行测试也写不了。
 */
internal data class GateResolution(
    val methods: List<java.lang.reflect.Method>,   // 只收返回 boolean 的
    val missingClasses: List<String>,
    val missingMethods: List<String>,
)

/**
 * 纯判定：给定「类名 → 该类里按名字找到的方法及其返回类型」，分出三种情形。
 *
 * 不做任何反射，只看传进来的信息，所以能直接单测。真实解析在 [gates] 里，
 * 把 Reflection 的结果喂进来就行。
 */
/** 一个门方法的名字与它在类里找到的全部同名重载。 */
internal typealias GateMethods = Pair<String, List<java.lang.reflect.Method>>

/** 一个类的解析输入；类不存在时第二项为空表。 */
internal typealias GateSpec = Pair<String, List<GateMethods>>

internal fun classifyGates(specs: List<GateSpec>): GateResolution {
    val methods = LinkedHashSet<java.lang.reflect.Method>()
    val missingClasses = ArrayList<String>()
    val missingMethods = ArrayList<String>()
    for ((className, entries) in specs) {
        if (entries.isEmpty()) {
            missingClasses += className
            continue
        }
        for ((methodName, found) in entries) {
            if (found.isEmpty()) {
                missingMethods += "$className#$methodName（类在，方法不存在）"
                continue
            }
            val bools = found.filter { it.returnType == java.lang.Boolean.TYPE }
            if (bools.isEmpty()) {
                val actual = found.map { it.returnType.simpleName }.distinct().joinToString("/")
                missingMethods += "$className#$methodName（方法在，但返回 $actual，不是 boolean）"
                continue
            }
            methods += bools
        }
    }
    return GateResolution(methods.toList(), missingClasses, missingMethods)
}

private fun gates(vararg specs: Pair<String, List<String>>) {
    // 反射只做一遍：把「类 → 方法名 → 找到的 Method」收集好，分类交给纯函数。
    // 分开的好处是「三种没命中怎么分」能直接单测，不用真类。
    val gathered = specs.map { (className, methodNames) ->
        val type = runCatching { cls(className) }.getOrNull()
        // 类不存在时 entries 留空，classifyGates 那边归到 missingClasses。
        val entries = if (type == null) emptyList() else methodNames.map { methodName ->
            // 沿父类找：QQ 的门方法大半声明在 Api 基类上，只搜本类一个也搜不着。
            val found = generateSequence<Class<*>>(type) { it.superclass }
                .map { c -> c.declaredMethods.filter { it.name == methodName } }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            methodName to found
        }
        className to entries
    }

    val resolution = classifyGates(gathered)
    for (name in resolution.missingClasses) {
        log("门方法的类不存在：$name（QQ 这一版没有，按功能退化处理）")
    }
    for (detail in resolution.missingMethods) log("门方法对不上：$detail")

    require(resolution.methods.isNotEmpty()) {
        "门方法一个都没命中：缺类 ${resolution.missingClasses.size} 个、方法对不上 ${resolution.missingMethods.size} 个"
    }
    // 先解析完再安装；安装异常交 installFeature 统一回滚。
    resolution.methods.forEach { method -> hook(method) { false } }
}

/** 群、资料卡、游戏中心、乐吧、虚拟形象的红点和引导气泡：门方法一律答「不显示」。 */
fun noRedDot() = gates(
    "com.tencent.mobileqq.troop.utils.api.impl.TroopUtilsApiImpl" to
        listOf("isShowRedPoint", "isShowKingTeamRedPoint", "isTroopTagNeedRedDot"),
    "com.tencent.mobileqq.gamecenter.api.impl.GameCenterRedPointConfigApiImpl" to
        listOf("canShowSpecialRedPoint", "isJumpByRedPoint"),
    "com.tencent.mobileqq.minigame.api.impl.MiniGameMetaGuideBubbleApiImpl" to
        listOf("shouldShowLebaGuideBubble", "hasGuideBubbleRedTouch"),
    "com.tencent.mobileqq.profilecard.api.impl.ProfileCardApiImpl" to
        listOf("showProfileRedPointGuide", "showProfileZplanUserGuide"),
    "com.tencent.mobileqq.zplan.aio.impl.ZPlanBadgeManagerImpl" to
        listOf("isEntranceShow", "canShowTips"),
)

/** 资料卡上的会员等级图标：SVIP / VIP / 大会员 / 大会员年费一律判成不是。 */
fun plainCard() = gates(
    "com.tencent.mobileqq.profilecard.component.content.ElegantProfileAccountLevelComponent" to
        listOf("isSuperVip", "isQQVip", "isBigClubVip", "isBigClubYearVip"),
)
