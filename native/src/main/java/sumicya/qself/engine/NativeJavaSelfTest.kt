/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.engine

import android.util.Log
import java.lang.reflect.Method

/**
 * End-to-end check of the native Java-hook path on classes this module owns:
 * LSPlant + Dobby + the libart.so symbol resolver, without any framework.
 *
 * The settings UI shows the result, which is the only way to tell "the engine
 * is wired up" from "the engine works" on a real device.
 */
object NativeJavaSelfTest {

    private const val TAG = "Qself/NativeTest"

    /** Hook target: deliberately trivial, called through reflection. */
    class Probe {
        fun value(): Int = 41
    }

    /** LSPlant calls `callback(Object[])` on the object passed to Hook(). */
    class Hooker {
        @Suppress("unused", "UNUSED_PARAMETER")
        fun callback(args: Array<Any?>): Any? = 42
    }

    /**
     * @return 0 when the hook replaced the body and unhooking restored it;
     *         negative error codes otherwise (see [explain])
     */
    fun run(): Int {
        if (!HookNative.lsplantReady) {
            return -1
        }
        val probe = Probe()
        val target: Method = try {
            Probe::class.java.getDeclaredMethod("value")
        } catch (t: Throwable) {
            Log.e(TAG, "probe method missing", t)
            return -2
        }
        val callback = try {
            Hooker::class.java.getDeclaredMethod("callback", Array<Any?>::class.java)
        } catch (t: Throwable) {
            Log.e(TAG, "callback method missing", t)
            return -3
        }
        val hooker = Hooker()
        val backup = try {
            HookNative.hookJava(target, hooker, callback)
        } catch (t: Throwable) {
            Log.e(TAG, "hook failed", t)
            null
        }
        if (backup == null) {
            return -4
        }
        // Inlined call sites would keep running the old body.
        HookNative.deoptimizeJava(target)

        val hooked = invoke(target, probe)
        val unhooked = HookNative.unhookJava(target)
        val restored = invoke(target, probe)

        if (hooked != 42) {
            return -5
        }
        if (!unhooked) {
            return -6
        }
        if (restored != 41) {
            return -7
        }
        return 0
    }

    private fun invoke(target: Method, receiver: Any): Int = try {
        target.invoke(receiver) as? Int ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "invoke failed", t)
        -1
    }

    /** Human-readable meaning of a [run] result. */
    fun explain(code: Int): String = when (code) {
        0 -> "ok"
        -1 -> "LSPlant unavailable"
        -2 -> "probe method missing"
        -3 -> "callback method missing"
        -4 -> "lsplant::Hook refused"
        -5 -> "replacement not called"
        -6 -> "unhook failed"
        -7 -> "original not restored"
        else -> "unknown ($code)"
    }
}
