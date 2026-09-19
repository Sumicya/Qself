/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.engine

import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * JNI surface of the native hook engine: Dobby for inline hooks, LSPlant for
 * ART-level Java method hooks, libart.so symbols resolved from the ELF image.
 *
 * The library is loaded lazily and never fatally: when anything is missing
 * (unsupported ABI, no libart symbols, LSPlant init failure) the caller falls
 * back to the framework's Java engine — see [lsplantReady].
 */
object HookNative {

    private const val LIB_NAME = "qself_hook"

    private var loadAttempted = false
    private var loaded = false

    /** True when libqself_hook.so is loaded. */
    val available: Boolean
        @Synchronized get() = ensureLoaded()

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (!loadAttempted) {
            loadAttempted = true
            loaded = try {
                System.loadLibrary(LIB_NAME)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "hook engine not available", t)
                false
            }
        }
        return loaded
    }

    private var initialized = false

    @Synchronized
    fun init(): Boolean {
        if (!ensureLoaded()) {
            return false
        }
        if (!initialized) {
            initialized = nativeInit() == 1
        }
        return initialized
    }

    /** Underlying inline-hook engine version (Dobby's), or "unavailable". */
    val version: String
        get() = if (ensureLoaded()) nativeVersion() else "unavailable"

    /** Dobby self-test: 0 = hooked, called through the trampoline and restored. */
    val selfTestResult: Int
        get() = if (init()) nativeSelfTest() else -100

    // ---- libart.so symbols ------------------------------------------------

    /**
     * Number of libart.so symbols indexed from .dynsym + .symtab. Forces the
     * resolver to run, which is also what makes LSPlant possible.
     */
    val artSymbolCount: Long
        get() = if (ensureLoaded()) nativeArtSymbolCount() else 0L

    val artSymbolStatus: String
        get() = if (ensureLoaded()) nativeArtSymbolStatus() else "unavailable"

    // ---- LSPlant ----------------------------------------------------------

    private var lsplantInitialized = false

    @Synchronized
    fun initLsplant(): Boolean {
        if (!ensureLoaded()) {
            return false
        }
        if (!lsplantInitialized) {
            lsplantInitialized = nativeLsplantInit() == 1
        }
        return lsplantInitialized
    }

    /**
     * True when native Java-method hooking is usable. Checked before the
     * module commits to the native engine.
     */
    val lsplantReady: Boolean
        get() = initLsplant()

    val lsplantStatus: String
        get() = if (ensureLoaded()) nativeLsplantStatus() else "unavailable"

    /**
     * Hooks [target]; [hooker] must declare `callback(Object[])` and [callback]
     * must be that method. Returns the backup executable (call it to run the
     * original), or null.
     */
    fun hookJava(target: Executable, hooker: Any, callback: Method): Executable? {
        if (!lsplantReady) {
            return null
        }
        return nativeHookJava(target, hooker, callback)
    }

    fun unhookJava(target: Executable): Boolean =
        ensureLoaded() && nativeUnhookJava(target)

    fun isHookedJava(target: Executable): Boolean =
        ensureLoaded() && nativeIsHookedJava(target)

    /** Deoptimize a hooked method so inlined call sites see the new body. */
    fun deoptimizeJava(target: Executable): Boolean =
        ensureLoaded() && nativeDeoptimizeJava(target)

    // ---- native -----------------------------------------------------------

    private external fun nativeInit(): Int

    private external fun nativeVersion(): String

    private external fun nativeSelfTest(): Int

    private external fun nativeArtSymbolCount(): Long

    private external fun nativeArtSymbolStatus(): String

    private external fun nativeLsplantInit(): Int

    private external fun nativeLsplantStatus(): String

    private external fun nativeHookJava(
        target: Executable,
        hooker: Any,
        callback: Method,
    ): Executable?

    private external fun nativeUnhookJava(target: Executable): Boolean

    private external fun nativeIsHookedJava(target: Executable): Boolean

    private external fun nativeDeoptimizeJava(target: Executable): Boolean

    /** Whether [executable] is a static member (LSPlant hides the receiver). */
    fun isStatic(executable: Executable): Boolean = Modifier.isStatic(executable.modifiers)

    /** Constructor is an `Executable` too; hooking it works the same way. */
    fun isConstructor(executable: Executable): Boolean = executable is Constructor<*>

    private const val TAG = "Qself/Native"
}
