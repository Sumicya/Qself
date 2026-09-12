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

import io.github.qauxv.util.dexkit.DexKit
import io.github.qauxv.util.dexkit.PadUtil_getDeviceType
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import sumicya.qself.hostapi.device.DeviceTypeApi
import sumicya.qself.hostapi.device.DeviceTypeApi.DeviceTypeHandle
import java.lang.reflect.InvocationTargetException

/**
 * Adapter for the host device-type source (RFC-03 §9 batch-3).
 *
 * Getter via DexKit [PadUtil_getDeviceType]; enum via direct FQN
 * com.tencent.common.config.pad.DeviceType. Enum constants cross the
 * port as opaque values; valueOf is invoked reflectively, preserving
 * the legacy throwing behaviour for unknown names.
 */
object DeviceTypeAdapter : DeviceTypeApi {

    private const val DEVICE_TYPE_ENUM = "com.tencent.common.config.pad.DeviceType"

    override fun resolveDeviceTypeSource(classLoader: ClassLoader): DeviceTypeHandle? =
        runCatching {
            val getter = DexKit.requireMethodFromCache(PadUtil_getDeviceType)
            val enumClass = classLoader.loadClass(DEVICE_TYPE_ENUM)
            DeviceTypeHandle(enumClass, getter)
        }.getOrNull()

    override fun constantNames(handle: DeviceTypeHandle): Array<String> =
        handle.enumClass.enumConstants.map { (it as Enum<*>).name }.toTypedArray()

    override fun constant(handle: DeviceTypeHandle, name: String): Any = try {
        handle.enumClass.getMethod("valueOf", String::class.java).invoke(null, name)
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }

    override fun readOriginal(handle: DeviceTypeHandle, arg: Any?): Any? =
        runCatching {
            XposedBridge.invokeOriginalMethod(handle.getter, null, arrayOf(arg))
        }.getOrNull()

    override fun installOverride(
        handle: DeviceTypeHandle,
        value: Any,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean = runCatching {
        XposedBridge.hookMethod(handle.getter, object : XC_MethodHook(50) {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    if (isEnabled()) {
                        param.result = value
                    }
                } catch (e: Throwable) {
                    onError(e)
                }
            }
        })
        true
    }.getOrElse {
        onError(it)
        false
    }
}
