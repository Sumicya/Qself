// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.app.Activity
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * One switch. install() adds hooks, disable() takes back everything it added, so flipping
 * the switch in the settings app lands in the running QQ immediately.
 */
abstract class Feature(val id: String, val mainOnly: Boolean = true) {
    private val handles = mutableListOf<XposedInterface.HookHandle>()

    var active = false
        private set

    /** Why the last install() failed, for the report. */
    var error: String? = null
        private set

    val hookCount: Int get() = handles.size

    /** One extra line in the report, e.g. how many views were hit. */
    open fun status(): String? = null

    protected abstract fun install()
    protected open fun uninstall() {}
    open fun onResume(activity: Activity) {}

    fun enable() {
        if (active) return
        try {
            install()
            active = true
            error = null
            Core.log(Log.INFO, "$id on (${handles.size} hooks)")
        } catch (t: Throwable) {
            handles.forEach { runCatching { it.unhook() } }
            handles.clear()
            error = t.toString().take(160)
            Core.log(Log.WARN, "$id failed: $t")
        }
    }

    fun disable() {
        if (!active) return
        active = false
        handles.forEach { runCatching { it.unhook() } }
        handles.clear()
        runCatching { uninstall() }.onFailure { Core.log(Log.WARN, "$id undo: $it") }
        Core.log(Log.INFO, "$id off")
    }

    /** The class of [name], or null when this QQ build does not have it. */
    protected fun cls(name: String): Class<*>? = runCatching { Class.forName(name, false, Core.loader) }.getOrNull()

    /** Same, but a miss marks the switch broken instead of silently doing nothing. */
    protected fun need(name: String): Class<*> = cls(name) ?: throw ClassNotFoundException(name)

    protected fun hook(target: Executable, body: (XposedInterface.Chain) -> Any?) {
        handles += Core.module.hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> body(chain) }
    }

    /** Replace the result, skipping the original. */
    protected fun constant(target: Method, value: Any?) = hook(target) { value }

    protected fun afterConstructed(clazz: Class<*>, body: (Any) -> Unit) {
        for (constructor in clazz.declaredConstructors) {
            hook(constructor) { chain ->
                val result = chain.proceed()
                chain.thisObject?.let(body)
                result
            }
        }
    }

    protected fun setField(obj: Any, name: String, value: Any) {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                f.set(obj, value)
                return
            }
            c = c.superclass
        }
    }

    protected fun require(condition: Boolean, what: String) {
        if (!condition || handles.isEmpty()) throw NoSuchMethodException(what)
    }

    /** Ask ART to stop inlining [target] into its callers, so a hook on a small callee is seen. */
    protected fun deopt(target: Executable) = runCatching { Core.module.deoptimize(target) }
}
