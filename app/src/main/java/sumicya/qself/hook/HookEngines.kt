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

    /**
     * The framework engine by default.
     *
     * The native engine patches ART internals, so it stays opt-in until it is
     * verified on a real device (see BootFlags.USE_NATIVE and
     * docs/VALIDATION.md): a module that boots is worth more than one that
     * hooks through LSPlant on an ART that was never tested. With the flag
     * present the native engine is preferred and the framework engine remains
     * the fallback.
     */
    fun forModernFramework(xposed: XposedInterface, preferNative: Boolean): HookEngine {
        val framework = LibXposedHookEngine(xposed)
        if (!preferNative) {
            return framework
        }
        return nativeOrNull() ?: framework.also {
            QLog.w("Qself", "native engine unavailable; using the framework engine")
        }
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
        // A working LSPlant is necessary but not sufficient: on an ART this
        // build was never verified against, DLL/inline hooking can corrupt
        // execution. The Dobby self-test (hook a marker, call through the
        // trampoline, restore) is the cheapest end-to-end proof that inline
        // hooking works *on this device*, so it gates the native engine.
        val selfTest = HookNative.selfTestResult
        if (selfTest != 0) {
            QLog.w("Qself", "native self-test failed ($selfTest); refusing the native engine")
            return null
        }
        QLog.i("Qself", "hook engine: ${HookNative.lsplantStatus} (native, self-test ok)")
        return NativeHookEngine()
    }
}
