// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.app.Activity
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * 一个开关：装钩子、卸钩子、报状态，就这三件事。
 *
 * 反射的名字一律写完整字面量（"com.tencent.qqnt.kernel.nativeinterface.VASMsgBubble"），
 * 不许拼字符串：tools/dexcheck.py 靠 tools/symbols.txt 一条条核，拼出来的名字核不了。
 */
abstract class Switch(val id: String, val mainOnly: Boolean = true) {
    private val handles = mutableListOf<XposedInterface.HookHandle>()

    var active = false
        private set

    /** 上次装钩子为什么失败，给报告用。 */
    var error: String? = null
        private set

    val hookCount: Int get() = handles.size

    protected abstract fun install()
    protected open fun uninstall() {}

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
            error = if (t is IllegalStateException && t.message?.contains("hook mutation") == true) {
                "钩子窗口已关闭：热重载或重启 QQ 后生效"
            } else {
                t.toString().take(160)
            }
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

    /** 这个 QQ 版本里没有这个类就返回 null。 */
    protected fun cls(name: String): Class<*>? =
        runCatching { Class.forName(name, false, Core.loader) }.getOrNull()

    /** 同上，但找不到就当成开关坏了，而不是静默失效。 */
    protected fun need(name: String): Class<*> = cls(name) ?: throw ClassNotFoundException(name)

    protected fun hook(target: Executable, body: (XposedInterface.Chain) -> Any?) {
        handles += Core.module.hook(target)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> body(chain) }
    }

    /** 直接换掉返回值，不跑原方法。 */
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

    /** 让 ART 别再把这个小方法内联进调用方，不然钩子看不见它。 */
    protected fun deopt(target: Executable) = runCatching { Core.module.deoptimize(target) }
}
