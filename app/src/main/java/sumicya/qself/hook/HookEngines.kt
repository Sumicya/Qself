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

    fun forModernFramework(xposed: XposedInterface): HookEngine =
        nativeOrNull() ?: LibXposedHookEngine(xposed).also {
            QLog.w("Qself", "native engine unavailable; using the framework engine")
        }

    /**
     * The native engine, or null when it cannot come up (no library for this
     * ABI, libart symbols missing, LSPlant init refused). Loading and
     * initialising are logged here, because this is the first place the
     * process touches native code.
     */
    fun nativeOrNull(): HookEngine? {
        QLog.i("Qself", "native library available: ${HookNative.available}")
        if (!HookNative.lsplantReady) {
            QLog.w("Qself", "native engine not ready: ${HookNative.lsplantStatus}")
            return null
        }
        QLog.i("Qself", "hook engine: ${HookNative.lsplantStatus} (native)")
        return NativeHookEngine()
    }
}
