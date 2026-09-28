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
    return xposed.hook(target).intercept { chain ->
        if (name == null || name !in failed && on(name)) body(chain) else chain.proceed()
    }
}

private var prefs: SharedPreferences? = null

/** 开关存在 QQ 自己的 SharedPreferences「qself」里，缺省全开；Application 还没建起来时也当开。 */
fun on(name: String): Boolean = store()?.getBoolean(name, true) ?: true

fun store(): SharedPreferences? = prefs ?: runCatching {
    (Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context)
        ?.getSharedPreferences("qself", Context.MODE_PRIVATE)
}.getOrNull()?.also { prefs = it }

/**
 * 名字满足 [pick] 的方法全部改成固定返回 [value]；一个都没匹配到就当找错了类。
 * 本类没有就往父类找（QQ 的门方法大半声明在 Api 基类上，只搜本类一个也搜不着），
 * 就近命中即停 —— 子类重写过就用子类那份。pick 写宽了会连父类的方法一起改，自己当心。
 */
fun Class<*>.constant(value: Any?, pick: (Method) -> Boolean) {
    val targets = generateSequence<Class<*>>(this) { it.superclass }
        .firstNotNullOfOrNull { c -> c.declaredMethods.filter(pick).takeIf { it.isNotEmpty() } }
        .orEmpty()
    require(targets.isNotEmpty()) { "$simpleName: 没有匹配的方法" }
    targets.forEach { m -> hook(m) { value } }
}

/**
 * 名字对上的方法。本类没有就往父类找：QQ 这些挂点大半声明在 MVVM 基类上，
 * 钩子因此盖住所有子类 —— 钩子体自己按运行时类型过滤，别指望它只落在一个类上。
 */
fun Class<*>.method(name: String): Method {
    var c: Class<*>? = this
    while (c != null) {
        c.declaredMethods.firstOrNull { it.name == name }?.let { return it }
        c = c.superclass
    }
    throw NoSuchMethodException("${this.name}#$name")
}

/** 每个构造器跑完以后对新对象做 [body]。 */
fun Class<*>.afterNew(body: (Any) -> Unit) = declaredConstructors.forEach { c ->
    hook(c) { chain -> chain.proceed().also { body(chain.thisObject) } }
}

/** 反射读写字段，父类里的也认。 */
private fun Any.field(name: String) = generateSequence<Class<*>>(javaClass) { it.superclass }
    .firstNotNullOfOrNull { c -> c.declaredFields.firstOrNull { it.name == name } }?.apply { isAccessible = true }

fun Any.set(name: String, value: Any?) =
    (field(name) ?: throw NoSuchFieldException("${javaClass.name}#$name")).set(this, value)

fun Any.get(name: String): Any? = field(name)?.get(this)
