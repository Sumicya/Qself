/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.libxposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.annotations.XposedHooker
import java.lang.reflect.Executable
import java.lang.reflect.Member
import sumicya.qself.xp.HookEngine
import sumicya.qself.xp.MutableHookParam

/**
 * [HookEngine] backed by the libxposed interceptor API (LSPosed 10.x and any
 * other API-101 implementation).
 *
 * The module never touches a legacy `XposedBridge`; this adapter is the only
 * place that knows about a framework, which is what keeps Qself's features
 * portable between the classic, modern and native engines.
 */
class LibXposedHookEngine(private val xposed: XposedInterface) : HookEngine {

    override fun hook(
        executable: Executable,
        onBefore: ((HookEngine.HookParam) -> Unit)?,
        onAfter: ((HookEngine.HookParam) -> Unit)?,
        priority: Int,
    ): HookEngine.Handle {
        val handle = xposed.hook(executable)
            // A misbehaving hooker must never take the host down: PROTECTIVE
            // logs and continues instead. Consequence to keep in mind:
            // HookParam.setException() from an *after* handler is logged
            // rather than propagated, because the framework swallows
            // exceptions thrown after Chain.proceed().
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setPriority(priority)
            .intercept(QselfInterceptor(executable, onBefore, onAfter))
        return HookEngine.Handle { handle.unhook() }
    }

    override fun toString(): String = "libxposed (API ${XposedInterface.LIB_API})"
}

/**
 * The hooker contract a module may define for itself: libxposed looks up
 * `intercept(Chain)` on the annotated hooker class, so the method has to live
 * on a dedicated type instead of on a lambda.
 */
@XposedHooker
interface QselfHooker : XposedInterface.Hooker {
    fun intercept(chain: XposedInterface.Chain): Any?
}

/** One instance per hook; the framework invokes it for every call. */
@XposedHooker
class QselfInterceptor(
    private val executable: Executable,
    private val onBefore: ((HookEngine.HookParam) -> Unit)?,
    private val onAfter: ((HookEngine.HookParam) -> Unit)?,
) : QselfHooker {

    override fun intercept(chain: XposedInterface.Chain): Any? {
        // `Chain.getArgs()` is immutable, but HookParam exposes a mutable
        // array; keep a pristine copy to detect in-place edits and forward
        // them through proceed(args).
        val original = chain.args.toTypedArray()
        val args = original.copyOf()
        val param = object : MutableHookParam(executable, chain.thisObject, args) {}

        if (onBefore != null) {
            param.afterPhase = false
            onBefore(param)
            if (param.skipped) {
                return param.resultValue
            }
            param.throwableValue?.let { throw it }
        }

        val proceedArgs = if (args.contentEquals(original)) null else args
        val result = try {
            if (proceedArgs == null) chain.proceed() else chain.proceed(proceedArgs)
        } catch (t: Throwable) {
            param.throwableValue = t
            if (onAfter != null) {
                param.afterPhase = true
                onAfter(param)
                param.throwableValue?.takeIf { it !== t }?.let { throw it }
            }
            throw t
        }

        param.resultValue = result
        if (onAfter != null) {
            param.afterPhase = true
            onAfter(param)
        }
        return param.resultValue
    }
}

