/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.hook

import io.github.libxposed.api.XposedInterface
import sumicya.qself.engine.HookNative
import sumicya.qself.libxposed.LibXposedHookEngine
import sumicya.qself.log.QLog
import sumicya.qself.nativehook.NativeHookEngine
import sumicya.qself.xp.HookEngine

/**
 * Picks the hooking backend for this process.
 *
 * The native engine (LSPlant + Dobby, no framework API) is preferred whenever
 * it comes up, because that is the path the project is moving to; the
 * framework's Java engine is the fallback, so an unsupported ART build or a
 * missing ABI degrades instead of disabling the module.
 */
object HookEngines {

    fun forModernFramework(xposed: XposedInterface): HookEngine {
        if (HookNative.lsplantReady) {
            QLog.i("Qself", "hook engine: ${HookNative.lsplantStatus} (native)")
            return NativeHookEngine()
        }
        QLog.w(
            "Qself",
            "native engine unavailable (${HookNative.lsplantStatus}); " +
                "falling back to the framework engine",
        )
        return LibXposedHookEngine(xposed)
    }
}
