/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.native

import sumicya.qself.log.QLog

/**
 * JNI surface of the native hook engine (LSPlant + Dobby).
 *
 * Loaded lazily: a failure here (e.g. a device without a supported ABI)
 * must never take the module down — the Java-level Xposed path is
 * independent.
 */
object HookNative {

    private const val LIB_NAME = "qself_hook"

    val available: Boolean = try {
        System.loadLibrary(LIB_NAME)
        true
    } catch (t: Throwable) {
        QLog.w("Native", "hook engine not available", t)
        false
    }

    val initialized: Boolean
        get() = available && init()

    /** Run the engine self-test. 0 = ok. */
    val selfTestResult: Int
        get() = if (initialized) nativeSelfTest() else -100

    val version: String
        get() = if (available) nativeVersion() else "unavailable"

    fun init(): Boolean {
        if (!available) {
            return false
        }
        return nativeInit() == 1
    }

    private external fun nativeInit(): Int

    private external fun nativeVersion(): String

    private external fun nativeSelfTest(): Int

    private external fun nativePltHook(target: Long, replace: Long, origOut: Long): Int

    private external fun nativePltUnhook(target: Long): Int
}
