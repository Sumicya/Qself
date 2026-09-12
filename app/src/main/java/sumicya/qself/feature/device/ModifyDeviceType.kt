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
package sumicya.qself.feature.device

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import io.github.qauxv.base.IUiItemAgent
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonConfigFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.dexkit.PadUtil_getDeviceType
import kotlinx.coroutines.flow.MutableStateFlow
import sumicya.qself.adapter.device.DeviceTypeAdapter
import sumicya.qself.hostapi.CapabilityRegistry
import sumicya.qself.hostapi.CapabilityState
import sumicya.qself.hostapi.device.DeviceTypeApi

/**
 * 设备类型修改 — RFC-03 §9 batch-3, first CommonConfigFunctionHook form.
 *
 * The feature keeps the config key, the picker dialog and the value
 * summary; the enum set and the getter are opaque adapter knowledge.
 * Config compatibility: the legacy switch key ("DeviceTypeHook", from the
 * class simple name) and the legacy value key (the full
 * io.github.duzhaokun123 FQN) are both preserved verbatim.
 */
@FunctionHookEntry
@UiItemAgentEntry
object ModifyDeviceType : CommonConfigFunctionHook(
    hookKey = "DeviceTypeHook",
    targets = arrayOf(PadUtil_getDeviceType),
) {

    private const val CAPABILITY_KEY = "device.device_type"

    private const val VALUE_CONFIG_KEY = "io.github.duzhaokun123.hook.DeviceTypeHook.deviceType"

    private val api: DeviceTypeApi = DeviceTypeAdapter

    override val name: String = "设备类型修改"

    override val description: String = """
        |修改设备类型为 PHONE/PAD/FOLD
        |并不能实现多设备登陆 但能影响 UI 布局
        |重启生效
    """.trimMargin()

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.DISGUISE_AND_DEVICE_CATEGORY

    override val valueState: MutableStateFlow<String?> = MutableStateFlow(
        if (isEnabled) ConfigManager.getDefaultConfig().getString(VALUE_CONFIG_KEY) else "禁用"
    )

    override fun initOnce(): Boolean {
        val handle = api.resolveDeviceTypeSource(Initiator.getHostClassLoader())
        if (handle == null) {
            CapabilityRegistry.report(
                CAPABILITY_KEY, CapabilityState.ABSENT,
                ClassNotFoundException("device type source not resolvable"),
            )
            Log.e("$CAPABILITY_KEY: host entry not found, feature self-disabled")
            return false
        }
        val stored = ConfigManager.getDefaultConfig().getString(VALUE_CONFIG_KEY)
        val value = api.constant(handle, stored!!)
        val installed = api.installOverride(
            handle, value,
            isEnabled = { isEnabled },
            onError = { traceError(it) },
        )
        CapabilityRegistry.report(
            CAPABILITY_KEY,
            if (installed) CapabilityState.AVAILABLE else CapabilityState.DEGRADED,
        )
        return installed
    }

    override val onUiItemClickListener = { _: IUiItemAgent, activity: Activity, _: View ->
        val handle = api.resolveDeviceTypeSource(Initiator.getHostClassLoader())
        if (handle == null) {
            AlertDialog.Builder(activity)
                .setMessage("宿主不支持（设备类型源不可解析）")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else {
            val deviceTypeNames = api.constantNames(handle)
            val originalType = api.readOriginal(handle, activity) ?: "未知"
            AlertDialog.Builder(activity)
                .setTitle("原始设备类型: $originalType")
                .setItems(deviceTypeNames) { _, which ->
                    valueState.value = deviceTypeNames[which]
                    ConfigManager.getDefaultConfig().putString(VALUE_CONFIG_KEY, deviceTypeNames[which])
                    isEnabled = true
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("恢复默认") { _, _ ->
                    valueState.value = null
                    isEnabled = false
                }
                .show()
        }
        Unit
    }
}
