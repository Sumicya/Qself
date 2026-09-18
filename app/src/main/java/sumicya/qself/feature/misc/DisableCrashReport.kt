/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature.misc

import sumicya.qself.ProcessKind
import sumicya.qself.annotation.QselfFeature
import sumicya.qself.feature.FeatureCategory
import sumicya.qself.feature.FeatureContext
import sumicya.qself.feature.SwitchFeature
import sumicya.qself.xp.Hooks

@QselfFeature(
    id = "misc.disable_crash_report",
    name = "禁用崩溃日志上报",
    summary = "阻止 QQ 上报可能带有模块信息的崩溃日志",
    category = "misc",
    enabledByDefault = true,
)
object DisableCrashReport : SwitchFeature() {

    override val id: String = "misc.disable_crash_report"
    override val name: String = "禁用崩溃日志上报"
    override val summary: String = "阻止 QQ 上报可能带有模块信息的崩溃日志"
    override val category: FeatureCategory = FeatureCategory.MISC
    override val experimental: Boolean = false
    override val targetProcesses: Set<ProcessKind> = ProcessKind.entries.toSet()
    override val defaultEnabled: Boolean = true

    override fun initOnce(ctx: FeatureContext): Boolean {
        val host = ctx.host

        // QQCrashReportManager was added in a newer QQ and is not obfuscated
        val crashManager = host.resolve("com.tencent.qqperf.monitor.crash.QQCrashReportManager")
        if (crashManager != null) {
            val init = crashManager.declaredMethods.singleOrNull {
                it.isPublic &&
                    it.returnType == Void.TYPE &&
                    !it.isStatic &&
                    it.parameterTypes.size == 2
            }
            init?.let {
                it.isAccessible = true
                Hooks.beforeIfEnabled(this, it) { it.skip() }
            }
        } else {
            // fallback: StatisticCollector.c(String) (TIM 2.3.1.x / QQ 8.0.0.x)
            val statistic = host.resolve("com.tencent.mobileqq.statistics.StatisticCollector")
            statistic?.declaredMethods?.singleOrNull {
                it.isPublic &&
                    it.returnType == Void.TYPE &&
                    !it.isStatic &&
                    it.parameterTypes.size == 1 &&
                    it.name == "c" &&
                    it.parameterTypes[0] == String::class.java
            }?.let {
                it.isAccessible = true
                Hooks.beforeIfEnabled(this, it) { it.skip() }
            }
        }
        return true
    }
}
