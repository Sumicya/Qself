// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import java.lang.reflect.Modifier

object SystemWebView : Feature("system_webview", mainOnly = false) {
    override fun install() =
        constant(need("com.tencent.smtt.sdk.QbSdk").getDeclaredMethod("getIsSysWebViewForcedByOuter"), true)
}

/** Only void and boolean entry points are silenced, so no caller ever sees a surprising null. */
object NoTelemetry : Feature("no_telemetry", mainOnly = false) {
    override fun install() {
        var count = 0
        cls("com.tencent.beacon.event.UserAction")?.declaredMethods?.forEach { m ->
            if (!Modifier.isPublic(m.modifiers)) return@forEach
            when {
                m.name.startsWith("onUserAction") && m.returnType == Boolean::class.javaPrimitiveType -> { constant(m, false); count++ }
                m.name.startsWith("initUserAction") && m.returnType == Void.TYPE -> { constant(m, null); count++ }
            }
        }
        // report(BeaconEvent) 返回 EventResult，硬返 null 会让调用方 NPE，所以掐的是初始化：
        // 没 start 过的 beacon 自己就会拒收（EventResult.ERROR_CODE_NOT_ENABLE）。
        cls("com.tencent.beacon.event.open.BeaconReport")?.declaredMethods?.forEach { m ->
            if (m.name == "start" && m.returnType == Void.TYPE) { constant(m, null); count++ }
        }
        cls("com.tencent.mobileqq.statistics.StatisticCollector")?.declaredMethods?.forEach { m ->
            if (Modifier.isPublic(m.modifiers) && m.returnType == Void.TYPE &&
                (m.name.startsWith("collectPerformance") || m.name.startsWith("report"))
            ) { constant(m, null); count++ }
        }
        require(count > 0, "telemetry entry points")
    }
}

object NoCrashReport : Feature("no_crash_report", mainOnly = false) {
    override fun install() {
        var count = 0
        for (name in listOf("com.tencent.bugly.crashreport.CrashReport", "com.tencent.feedback.eup.CrashReport")) {
            cls(name)?.declaredMethods?.forEach { m ->
                if (Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.name.startsWith("init")) {
                    constant(m, null)
                    count++
                }
            }
        }
        require(count > 0, "crash report init")
    }
}
