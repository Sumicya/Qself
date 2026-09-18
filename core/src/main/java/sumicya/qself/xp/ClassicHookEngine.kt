/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.xp

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import sumicya.qself.log.QLog

/**
 * [HookEngine] backed by the classic Xposed API (XposedBridge). This works
 * on Xposed, EdXposed and LSPosed 1.x — the environments that expose
 * `de.robv.android.xposed`.
 */
class ClassicHookEngine : HookEngine {

    override fun hook(
        executable: Executable,
        onBefore: ((HookParam) -> Unit)?,
        onAfter: ((HookParam) -> Unit)?,
        priority: Int,
    ): Handle {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                onBefore?.invoke(BeforeView(param))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                onAfter?.invoke(AfterView(param))
            }
        }
        return try {
            val unhooker = when (executable) {
                is java.lang.reflect.Method -> XposedBridge.hookMethod(executable, priority, callback)
                is Constructor<*> -> XposedBridge.hookConstructor(executable, priority, callback)
                else -> throw IllegalArgumentException("not a method or constructor: $executable")
            }
            Handle { runCatching { unhooker.unhook() } }
        } catch (t: Throwable) {
            QLog.e("Hook", "hook failed: $executable", t)
            throw IllegalStateException("failed to hook $executable", t)
        }
    }

    private class BeforeView(private val param: MethodHookParam) : HookParam {
        override val member: java.lang.reflect.Member get() = param.method
        override val thisObject: Any? get() = param.thisObject
        override val args: Array<Any?> get() = param.args
        override val isAfter: Boolean get() = false
        override val result: Any? get() = null
        override val exception: Throwable? get() = null

        override fun skip(result: Any?) {
            // setting the result in a before-hook replaces the invocation
            param.result = result
        }

        override fun setException(throwable: Throwable?) {
            // no-op before the original call; the after-handler can throw
        }
    }

    private class AfterView(private val param: MethodHookParam) : HookParam {
        override val member: java.lang.reflect.Member get() = param.method
        override val thisObject: Any? get() = param.thisObject
        override val args: Array<Any?> get() = param.args
        override val isAfter: Boolean get() = true
        override val result: Any? get() = param.result
        override val exception: Throwable? get() = param.throwable

        override fun skip(result: Any?) {
            // report only: the original execution is already done
        }

        override fun setException(throwable: Throwable?) {
            param.throwable = throwable
        }
    }
}
