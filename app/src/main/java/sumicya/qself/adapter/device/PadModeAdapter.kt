/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */
package sumicya.qself.adapter.device

import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import sumicya.qself.hostapi.device.PadModeApi
import top.linl.util.reflect.FieldUtils

/**
 * Batch-6 adapter: owns the AppSetting spoof point and the two obfuscation
 * version tables (method name, field names). The tables are pure functions
 * of (hostIsTim, versionCode) and pinned on the JVM.
 */
object PadModeAdapter : PadModeApi {

    /**
     * The AppSetting appId reader method name. The original condition was
     * `requireMinQQVersion(QQ_9_2_30) -> "e"; else -> "f"` — QQ-only, so TIM
     * (any version) and QQ below 9.2.30 share "f".
     */
    fun appSettingReadMethodName(hostIsTim: Boolean, versionCode: Long): String =
        if (!hostIsTim && versionCode >= QQVersion.QQ_9_2_30) "e" else "f"

    /**
     * The TABLET-side appId static field name, in the original when-order
     * (TIM checked first). All TIM builds reaching this feature satisfy
     * >= 4.0.95 through the availability gate, so TIM collapses to one row.
     */
    fun tabletAppIdFieldName(hostIsTim: Boolean, versionCode: Long): String = when {
        hostIsTim -> "g"
        versionCode >= QQVersion.QQ_9_3_20 -> "g"
        versionCode >= QQVersion.QQ_9_3_5 -> "b"
        versionCode >= QQVersion.QQ_9_2_65 -> "f"
        versionCode >= QQVersion.QQ_9_2_30 -> "g"
        versionCode >= QQVersion.QQ_9_2_15 -> "h"
        versionCode >= QQVersion.QQ_9_1_50 -> "g"
        else -> "f"
    }

    /**
     * The PHONE-side field name. The original code destructured this value
     * but never used it (only the tablet value was assigned); it is kept as
     * documented knowledge and pinned by tests.
     */
    fun phoneAppIdFieldName(hostIsTim: Boolean, versionCode: Long): String = when {
        hostIsTim -> "f"
        versionCode >= QQVersion.QQ_9_3_20 -> "f"
        versionCode >= QQVersion.QQ_9_3_5 -> "a"
        versionCode >= QQVersion.QQ_9_2_65 -> "e"
        versionCode >= QQVersion.QQ_9_2_30 -> "f"
        versionCode >= QQVersion.QQ_9_2_15 -> "g"
        versionCode >= QQVersion.QQ_9_1_50 -> "f"
        else -> "e"
    }

    override fun installForcePadAppId(
        classLoader: ClassLoader,
        hostIsTim: Boolean,
        hostVersionCode: Long,
    ): Boolean {
        val appSettingClass = classLoader.loadClass("com.tencent.common.config.AppSetting")
        val methodName = appSettingReadMethodName(hostIsTim, hostVersionCode)
        // faithful to the original ezx findMethod: the first declared
        // int-returning method with that name; absence throws
        val target = appSettingClass.declaredMethods.firstOrNull {
            it.returnType == Int::class.javaPrimitiveType && it.name == methodName
        } ?: throw IllegalStateException("AppSetting.$methodName():int not found")
        val tabletField = tabletAppIdFieldName(hostIsTim, hostVersionCode)
        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                param.result = FieldUtils.getStaticFieId<Int>(appSettingClass, tabletField)
            }
        })
        return true
    }
}
