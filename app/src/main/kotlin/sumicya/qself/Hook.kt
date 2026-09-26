// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable
import java.lang.reflect.Method

/** 全局就这两样：框架句柄、QQ 的 ClassLoader。install 时赋值。 */
lateinit var xposed: XposedInterface
lateinit var loader: ClassLoader

/** 同时写 logcat（`logcat -s Qself`）和 LSPosed 管理器的日志页。 */
fun log(msg: String, t: Throwable? = null) {
    Log.i("Qself", msg, t)
    xposed.log(Log.INFO, "Qself", msg, t)
}

fun cls(name: String): Class<*> = Class.forName(name, false, loader)

/** 正在装的功能名。hook() 把它记进钩子，运行时按开关决定走不走；null = 不受开关管。 */
var feature: String? = null

/**
 * 装钩子。libxposed 默认（PROTECTIVE）异常模式：钩子在 proceed 前抛异常 = 这一次当没装，
 * QQ 原方法照常跑，所以钩子体不必 try/catch。不调用 proceed 就是替换原方法。
 */
fun hook(target: Executable, body: (Chain) -> Any?): XposedInterface.HookHandle {
    val name = feature
    return xposed.hook(target).intercept { chain -> if (name == null || on(name)) body(chain) else chain.proceed() }
}

private var prefs: SharedPreferences? = null

/** 开关存在 QQ 自己的 SharedPreferences「qself」里，缺省全开；Application 还没建起来时也当开。 */
fun on(name: String): Boolean = store()?.getBoolean(name, true) ?: true

fun store(): SharedPreferences? = prefs ?: runCatching {
    (Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context)
        ?.getSharedPreferences("qself", Context.MODE_PRIVATE)
}.getOrNull()?.also { prefs = it }

/** 名字满足 [pick] 的方法全部改成固定返回 [value]；一个都没匹配到就当找错了类。 */
fun Class<*>.constant(value: Any?, pick: (Method) -> Boolean) {
    val targets = declaredMethods.filter(pick)
    require(targets.isNotEmpty()) { "$simpleName: 没有匹配的方法" }
    targets.forEach { m -> hook(m) { value } }
}

fun Class<*>.method(name: String): Method =
    declaredMethods.firstOrNull { it.name == name } ?: throw NoSuchMethodException("${this.name}.$name")

/** 每个构造器跑完以后对新对象做 [body]。 */
fun Class<*>.afterNew(body: (Any) -> Unit) = declaredConstructors.forEach { c ->
    hook(c) { chain -> chain.proceed().also { body(chain.thisObject) } }
}

/** 反射写字段，父类里的也认。 */
fun Any.set(field: String, value: Any?) {
    generateSequence<Class<*>>(javaClass) { it.superclass }
        .firstNotNullOfOrNull { c -> c.declaredFields.firstOrNull { it.name == field } }
        ?.apply { isAccessible = true }?.set(this, value)
        ?: throw NoSuchFieldException("${javaClass.name}.$field")
}

fun Any.get(field: String): Any? =
    generateSequence<Class<*>>(javaClass) { it.superclass }
        .firstNotNullOfOrNull { c -> c.declaredFields.firstOrNull { it.name == field } }
        ?.apply { isAccessible = true }?.get(this)
