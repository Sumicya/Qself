/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.nativehook

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import sumicya.qself.engine.HookNative
import sumicya.qself.xp.HookEngine
import sumicya.qself.xp.MutableHookParam

/**
 * [HookEngine] backed by LSPlant: Java methods (and constructors) are hooked
 * by patching ART, with Dobby supplying the inline-hook primitive and
 * libart.so symbols resolved from the on-disk ELF image.
 *
 * This is the *native* path — no framework hooking API involved — which is
 * what lets Qself run outside Xposed in the end. Priority is ignored: LSPlant
 * keeps one replacement per method, and Qself installs one hook per target.
 */
class NativeHookEngine : HookEngine {

    override fun hook(
        executable: Executable,
        onBefore: ((HookEngine.HookParam) -> Unit)?,
        onAfter: ((HookEngine.HookParam) -> Unit)?,
        priority: Int,
    ): HookEngine.Handle {
        val hooker = NativeHooker(executable, onBefore, onAfter)
        val backup = HookNative.hookJava(executable, hooker, hooker.callbackMethod)
            ?: throw HookFailedException(executable)
        hooker.attachBackup(backup)
        // Call sites that already inlined the original body must be updated.
        HookNative.deoptimizeJava(executable)
        return HookEngine.Handle { HookNative.unhookJava(executable) }
    }

    override fun toString(): String = "LSPlant (native, ${HookNative.lsplantStatus})"

    class HookFailedException(executable: Executable) :
        IllegalStateException("LSPlant refused to hook $executable")
}

/**
 * Context object handed to LSPlant. Its public `callback(Object[])` is the
 * replacement body; the backup executable is stored here so the callback can
 * still run the original.
 *
 * LSPlant passes the receiver as `args[0]` for instance members and omits it
 * for static ones, so the receiver is split off before handlers see the args.
 */
class NativeHooker(
    private val executable: Executable,
    private val onBefore: ((HookEngine.HookParam) -> Unit)?,
    private val onAfter: ((HookEngine.HookParam) -> Unit)?,
) {

    val callbackMethod: Method = NativeHooker::class.java
        .getDeclaredMethod(CALLBACK_NAME, Array<Any?>::class.java)

    @Volatile
    private var backup: Executable? = null

    fun attachBackup(executable: Executable) {
        backup = executable
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    fun callback(args: Array<Any?>): Any? {
        val isStatic = HookNative.isStatic(executable)
        val receiver = if (isStatic) null else args.firstOrNull()
        val params = if (isStatic) args else args.copyOfRange(1, args.size)
        val param = MutableHookParam(executable, receiver, params)

        if (onBefore != null) {
            param.afterPhase = false
            onBefore(param)
            if (param.skipped) {
                return param.resultValue
            }
            param.throwableValue?.let { throw it }
        }

        val result = try {
            val target = backup ?: throw IllegalStateException("no backup for $executable")
            invokeBackup(target, receiver, param.args)
        } catch (t: Throwable) {
            val cause = unwrap(t)
            param.throwableValue = cause
            if (onAfter != null) {
                param.afterPhase = true
                onAfter(param)
                param.throwableValue?.takeIf { it !== cause }?.let { throw it }
            }
            throw cause
        }

        param.resultValue = result
        if (onAfter != null) {
            param.afterPhase = true
            onAfter(param)
        }
        return param.resultValue
    }

    /**
     * LSPlant hands back the original as a `Method`, including for
     * constructors — there the receiver is the instance being initialized,
     * so it is invoked instead of instantiated.
     */
    private fun invokeBackup(target: Executable, receiver: Any?, params: Array<Any?>): Any? =
        when (target) {
            is Method -> target.invoke(receiver, *params)
            is Constructor<*> -> target.newInstance(*params)
            else -> throw IllegalStateException("unsupported backup: $target")
        }

    private fun unwrap(t: Throwable): Throwable =
        if (t is InvocationTargetException && t.targetException != null) {
            t.targetException
        } else {
            t
        }

    private companion object {
        const val CALLBACK_NAME = "callback"
    }
}
