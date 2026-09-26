// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.app.Activity
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * One switch. enable() installs hooks, disable() removes every one of them,
 * so a toggle in the settings app applies to the running QQ immediately.
 */
abstract class Feature(val id: String, val mainOnly: Boolean = true) {
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    var active = false
        private set
    /** Why the last enable() failed, for the self-check report. */
    var error: String? = null
        private set
    val hookCount get() = handles.size

    /** One-line state shown in the self-check report, beyond on/off. */
    open fun status(): String? = null

    protected val cl: ClassLoader get() = Runtime.loader

    fun enable() {
        if (active) return
        try {
            install()
            active = true
            error = null
            Runtime.log(Log.INFO, "$id on (${handles.size} hooks)")
        } catch (t: Throwable) {
            unhookAll()
            error = t.toString().take(160)
            Runtime.log(Log.WARN, "$id failed: $t")
        }
    }

    fun disable() {
        if (!active) return
        active = false
        unhookAll()
        runCatching { uninstall() }
        Runtime.log(Log.INFO, "$id off")
    }

    private fun unhookAll() {
        handles.forEach { runCatching { it.unhook() } }
        handles.clear()
    }

    protected abstract fun install()
    protected open fun uninstall() {}
    open fun onResume(activity: Activity) {}

    protected fun cls(name: String): Class<*>? =
        runCatching { Class.forName(name, false, cl) }.getOrNull()

    protected fun need(name: String): Class<*> = Class.forName(name, false, cl)

    protected fun hook(target: Executable, body: (XposedInterface.Chain) -> Any?) {
        handles += Runtime.module.hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> body(chain) }
    }

    /** Ask ART to stop inlining [m] into its callers' compiled code, so hooks on small callees are seen. */
    protected fun deopt(m: Executable) {
        runCatching {
            Runtime.module.javaClass.getMethod("deoptimize", Executable::class.java).invoke(Runtime.module, m)
        }
    }

    /** Replace the result, skipping the original. */
    protected fun constant(target: Method, value: Any?) = hook(target) { value }

    protected fun hookAfterCtors(clazz: Class<*>, body: (Any) -> Unit) {
        for (c in clazz.declaredConstructors) hook(c) { chain ->
            val r = chain.proceed()
            chain.thisObject?.let(body)
            r
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

    protected fun check(hooked: Boolean, what: String) {
        if (!hooked || handles.isEmpty()) throw NoSuchMethodException(what)
    }
}
