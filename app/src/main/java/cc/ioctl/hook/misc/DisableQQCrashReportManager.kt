/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2023 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package cc.ioctl.hook.misc

import cc.ioctl.util.HookUtils
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.SyncUtils
import xyz.nextalone.util.isPublic
import xyz.nextalone.util.isStatic

@FunctionHookEntry
@UiItemAgentEntry
object DisableQQCrashReportManager : CommonSwitchFunctionHook(defaultEnabled = true, targetProc = SyncUtils.PROC_ANY) {
    // 因为被点名批评了，所以默认开启，以避免潜在的不必要的麻烦
    override val name = "禁用崩溃日志上报"
    override val description = "禁用 QQCrashReportManager 的崩溃日志上报功能，防止上报可能带有模块信息的崩溃日志"

    override val uiItemLocation = FunctionEntryRouter.Locations.DebugCategory.DEBUG_CATEGORY
    override val isApplicationRestartRequired = true
    override val isAvailable = true

    override fun initOnce(): Boolean {
        // com/tencent/qqperf/monitor/crash/QQCrashReportManager is added in QQ 8.?.?
        // and the class name is not obfuscated
        val kQQCrashReportManager = Initiator.load("com.tencent.qqperf.monitor.crash.QQCrashReportManager")
        if (kQQCrashReportManager != null) {
            val initCrashReport = kQQCrashReportManager.declaredMethods.single {
                it.isPublic && it.returnType == Void.TYPE && !it.isStatic && it.parameterTypes.size == 2
            }
            HookUtils.hookBeforeAlways(this, initCrashReport) {
                it.result = null
            }
        } else {
            val kStatisticCollector = Initiator.load("com.tencent.mobileqq.statistics.StatisticCollector")
            if (kStatisticCollector != null) {
                // for TIM 2.3.1.1834_1072 and QQ 8.0.0.4000_1024
                // there should be [abcd] 4 methods, select 'c'
                // public void .+\(String str\)
                val initCrashReport = kStatisticCollector.declaredMethods.single {
                    it.isPublic && it.returnType == Void.TYPE && !it.isStatic && it.parameterTypes.size == 1 &&
                        it.name == "c" &&
                        it.parameterTypes[0] == java.lang.String::class.java
                }
                HookUtils.hookBeforeAlways(this, initCrashReport) {
                    it.result = null
                }
            }
        }
        return true
    }

}
