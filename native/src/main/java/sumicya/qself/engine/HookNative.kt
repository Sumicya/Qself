/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.engine

import android.util.Log

/**
 * JNI surface of the native hook engine (Dobby).
 *
 * Loaded lazily: a failure (e.g. an unsupported ABI) must never take the
 * module down — the Java-level hooking path is independent of this.
 */
object HookNative {

    private const val LIB_NAME = "qself_hook"

    /** True when libqself_hook.so loaded. */
    val available: Boolean = try {
        System.loadLibrary(LIB_NAME)
        true
    } catch (t: Throwable) {
        Log.w("Qself/Native", "hook engine not available", t)
        false
    }

    private var initialized = false

    @Synchronized
    fun init(): Boolean {
        if (!available) {
            return false
        }
        if (!initialized) {
            initialized = nativeInit() == 1
        }
        return initialized
    }

    /** Underlying engine version (Dobby's), or "unavailable". */
    val version: String
        get() = if (available) nativeVersion() else "unavailable"

    /** Self-test result: 0 = the engine hooked and restored correctly. */
    val selfTestResult: Int
        get() = if (init()) nativeSelfTest() else -100

    private external fun nativeInit(): Int

    private external fun nativeVersion(): String

    private external fun nativeSelfTest(): Int
}
