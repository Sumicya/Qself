// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import java.lang.reflect.Modifier

/** 别用腾讯 X5 内核。 */
object SystemWebView : Switch("system_webview", mainOnly = false) {
    override fun install() = constant(
        need("com.tencent.smtt.sdk.QbSdk").getDeclaredMethod("getIsSysWebViewForcedByOuter"),
        true,
    )
}

/**
 * 上报入口只掐 void 和 boolean 的：非 void 的方法返 null 会把调用方 NPE。
 *
 * BeaconReport.report(BeaconEvent) 返回 EventResult，所以掐的是它前面的 start —— 没初始化过的
 * 灯塔自己就会拒收（EventResult 里有 ERROR_CODE_NOT_ENABLE 这个码）。
 */
object NoTelemetry : Switch("no_telemetry", mainOnly = false) {
    override fun install() {
        var count = 0
        cls("com.tencent.beacon.event.UserAction")?.declaredMethods?.forEach { m ->
            if (!Modifier.isPublic(m.modifiers)) return@forEach
            when {
                m.name.startsWith("onUserAction") && m.returnType == Boolean::class.javaPrimitiveType -> {
                    constant(m, false)
                    count++
                }
                m.name.startsWith("initUserAction") && m.returnType == Void.TYPE -> {
                    constant(m, null)
                    count++
                }
            }
        }
        cls("com.tencent.beacon.event.open.BeaconReport")?.declaredMethods?.forEach { m ->
            if (m.name == "start" && m.returnType == Void.TYPE) {
                constant(m, null)
                count++
            }
        }
        cls("com.tencent.mobileqq.statistics.StatisticCollector")?.declaredMethods?.forEach { m ->
            if (Modifier.isPublic(m.modifiers) && m.returnType == Void.TYPE &&
                (m.name.startsWith("collectPerformance") || m.name.startsWith("report"))
            ) {
                constant(m, null)
                count++
            }
        }
        require(count > 0, "上报入口")
    }
}

/** 崩溃收集连初始化都别想。 */
object NoCrashReport : Switch("no_crash_report", mainOnly = false) {
    override fun install() {
        val inits = need("com.tencent.feedback.eup.CrashReport").declaredMethods
            .filter { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.name.startsWith("init") }
        inits.forEach { constant(it, null) }
        require(inits.isNotEmpty(), "崩溃上报入口")
    }
}
