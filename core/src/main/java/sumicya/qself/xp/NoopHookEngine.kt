/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.xp

import java.lang.reflect.Executable
import sumicya.qself.log.QLog

/**
 * Engine that refuses every hook with a clear log entry. Used when the
 * module is loaded in an environment without a usable Java-level hooking
 * API (e.g. LSPosed 10.x in v1, which gets real support in a later
 * release) so the rest of the module can boot and report diagnostics
 * instead of crashing the host.
 */
class NoopHookEngine(private val reason: String) : HookEngine {

    override val supported: Boolean
        get() = false

    override fun hook(
        executable: Executable,
        onBefore: ((HookParam) -> Unit)?,
        onAfter: ((HookParam) -> Unit)?,
        priority: Int,
    ): Handle {
        QLog.w("Hook", "hook refused ($reason): $executable")
        return Handle { }
    }
}
