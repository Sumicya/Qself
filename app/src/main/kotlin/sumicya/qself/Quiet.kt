// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

/** 自由化：不要 X5、不要统计、不要崩溃上报。每个进程都装。 */

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
