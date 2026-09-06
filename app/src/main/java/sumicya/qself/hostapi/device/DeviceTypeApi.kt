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
package sumicya.qself.hostapi.device

import java.lang.reflect.Method

/**
 * Port: the host's device-type source (PadUtil / DeviceType enum).
 *
 * Batch-3 pilot (RFC-03 §9): first CommonConfigFunctionHook-form feature.
 * The port exposes the enum as an **opaque constant set** — names in,
 * opaque values out — so the feature's config UI never touches host
 * reflection. Values crossing this boundary are host enum constants;
 * treat them as opaque and hand them back verbatim.
 */
interface DeviceTypeApi {

    /**
     * Resolved host knowledge: the DeviceType enum class plus the static
     * getter (DexKit-resolved) whose result is overridden.
     */
    class DeviceTypeHandle(val enumClass: Class<*>, val getter: Method)

    /**
     * Pure resolution. Null when the host capability is absent
     * (DexKit target unresolvable / enum class missing).
     */
    fun resolveDeviceTypeSource(classLoader: ClassLoader): DeviceTypeHandle?

    /** Names of the available device-type constants, for UI listing. */
    fun constantNames(handle: DeviceTypeHandle): Array<String>

    /**
     * Resolve a constant by name. Throws for unknown names — callers run
     * inside the hook-init fence where failures are traced, matching the
     * legacy semantics (a broken stored config fails the init loudly
     * instead of silently blanking the getter).
     */
    fun constant(handle: DeviceTypeHandle, name: String): Any

    /**
     * Best-effort read of the original (unhooked) getter value for the
     * given invocation argument (an Activity on device). Null on failure.
     */
    fun readOriginal(handle: DeviceTypeHandle, arg: Any?): Any?

    /**
     * Install the override: when enabled, the getter returns [value].
     * The value is captured at install time on purpose — the legacy
     * behaviour ("重启生效") made config changes require a restart.
     */
    fun installOverride(
        handle: DeviceTypeHandle,
        value: Any,
        isEnabled: () -> Boolean,
        onError: (Throwable) -> Unit,
    ): Boolean
}
