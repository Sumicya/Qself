// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

/** 自由化 + 净化：不要 X5、不要统计、不要崩溃上报、不要红点和会员等级。头三个每个进程都装。 */

/** X5 内核问「外部是否强制系统 WebView」，答是。 */
fun systemWebView() =
    cls("com.tencent.smtt.sdk.QbSdk").constant(true) { it.name == "getIsSysWebViewForcedByOuter" }

/** 灯塔 (beacon) 的事件上报与 QQ 自家 StatisticCollector 全部空转；SDK 照常初始化，登录风控不受影响。 */
fun noTelemetry() {
    cls("com.tencent.beacon.event.UserAction").apply {
        constant(false) { it.returnType == java.lang.Boolean.TYPE && (it.name.startsWith("on") || it.name == "loginEvent") }
        constant(null) { it.returnType == Void.TYPE && (it.name.startsWith("onPage") || it.name == "doUploadRecords") }
    }
    cls("com.tencent.beacon.event.open.BeaconReport").apply {
        // report() 的调用方会读返回值，给一个 errorCode=1（非 0 即失败）的真对象而不是 null。
        val result = cls("com.tencent.beacon.event.open.EventResult")
            .getConstructor(Integer.TYPE, java.lang.Long.TYPE, String::class.java)
        hook(method("report")) { result.newInstance(1, 0L, "Qself") }
    }
    cls("com.tencent.mobileqq.statistics.StatisticCollector")
        .constant(null) { it.returnType == Void.TYPE && (it.name.startsWith("collectPerformance") || it.name.startsWith("report")) }
}

/** RQD/Bugly 不初始化：崩溃就按系统的来，不往腾讯传栈。 */
fun noCrashReport() =
    cls("com.tencent.feedback.eup.CrashReport").constant(null) { it.name.startsWith("initCrashReport") }

/**
 * 门方法一律答 false：类名 → 方法名们。沿父类找（见 Hook.kt 的 constant）。
 * ponytail: 单个没命中只写日志不抛 —— 每个门各管各的，少一个不影响其余；一个都没命中才算装失败。
 */
private fun gates(vararg specs: Pair<String, List<String>>) {
    val all = specs.flatMap { (c, ms) -> ms.map { c to it } }
    val hit = all.count { (c, m) ->
        val ok = runCatching {
            cls(c).constant(false) { mt -> mt.name == m && mt.returnType == java.lang.Boolean.TYPE }
        }.isSuccess
        if (!ok) log("门方法没命中: $c#$m")
        ok
    }
    require(hit > 0) { "门方法一个都没命中" }
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
