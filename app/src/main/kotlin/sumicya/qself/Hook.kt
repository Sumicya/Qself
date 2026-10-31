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

/** 同时写 logcat（`logcat -s Qself`）、LSPosed 管理器的日志页与内存诊断环。 */
fun log(msg: String, t: Throwable? = null) {
    runCatching { recordQselfEvent(msg, t) }
    Log.i("Qself", msg, t)
    xposed.log(Log.INFO, "Qself", msg, t)
}

fun cls(name: String): Class<*> = Class.forName(name, false, loader)

/** 正在装的功能名。hook() 把它记进钩子，运行时按开关决定走不走；null = 不受开关管。 */
var feature: String? = null
private var installing: MutableList<XposedInterface.HookHandle>? = null

/** 每项功能安装原子化：失败撤掉本项已装钩子，不能留下“显示 ✗ 但半项仍生效”。 */
fun installFeature(name: String?, body: () -> Any?) {
    check(installing == null) { "不支持嵌套安装" }
    val handles = ArrayList<XposedInterface.HookHandle>()
    installing = handles
    val previous = feature
    feature = name
    try { body() } catch (failure: Throwable) {
        handles.asReversed().forEach { handle ->
            runCatching { handle.unhook() }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    } finally { installing = null; feature = previous }
}

private class SafeChain(private val delegate: Chain) : Chain by delegate {
    var proceeded = false
    var outcome: Any? = null
    var targetFailure: Throwable? = null
    override fun proceed(): Any? {
        proceeded = true
        return try {
            delegate.proceed().also { outcome = it }
        } catch (t: Throwable) {
            targetFailure = t
            throw t
        }
    }
    override fun proceed(args: Array<out Any?>): Any? {
        proceeded = true
        return try {
            delegate.proceed(args).also { outcome = it }
        } catch (t: Throwable) {
            targetFailure = t
            throw t
        }
    }
    override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? {
        proceeded = true
        return try {
            delegate.proceedWith(thisObject, args).also { outcome = it }
        } catch (t: Throwable) {
            targetFailure = t
            throw t
        }
    }
}

/**
 * 装钩子。libxposed 默认（PROTECTIVE）只兜住 proceed 前抛出的异常；
 * SafeChain 同时兜住 proceed 后（如 .also 块内）抛出的钩子异常并退回原返回值，防止把异常抛回宿主导致崩溃。
 */
fun hook(target: Executable, gated: Boolean = true, counted: Boolean = true, body: (Chain) -> Any?): XposedInterface.HookHandle {
    val name = if (gated) feature else null
    val sig = "${target.declaringClass.name}#${target.name}"
    val isQLogSelf = target.declaringClass.name == "com.tencent.qphone.base.util.QLog"
    return xposed.hook(target).intercept { chain ->
        if (name != null && !on(name)) return@intercept chain.proceed()
        val safe = SafeChain(chain)
        try {
            val res = body(safe)
            // counted=false 给「每条消息都会走」的钩子用：省掉一次查表与一次参数摘要分配，也不占事件流。
            if (counted && !isQLogSelf) {
                runCatching { recordHookInvocation(sig, safe.thisObject, safe.args, res) }
            }
            res
        } catch (t: Throwable) {
            if (t === safe.targetFailure) throw t
            if (!safe.proceeded) {
                log("钩子前置异常: ${target.declaringClass.simpleName}#${target.name}", t)
                chain.proceed()
            } else {
                log("钩子后置异常: ${target.declaringClass.simpleName}#${target.name}", t)
                safe.outcome
            }
        }
    }.also { installing?.add(it) }
}

private var prefs: SharedPreferences? = null

/** 读到没登记的开关名时每个进程只报一次，免得「代码在读、清单里没有」永远静默（2026-10-31 两次踩到）。 */
private val unknownSwitches = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

/** 开关存在 QQ 自己的 SharedPreferences「qself」里，缺省全开；Application 还没建起来时也当开。 */
fun on(name: String): Boolean {
    val known = featureDefaults[name]
    if (known == null && unknownSwitches.add(name)) {
        runCatching { log("开关未登记 : $name（清单里没有，按默认开启）") }
    }
    return name !in failed && (store()?.getBoolean(name, known ?: true) ?: (known ?: true))
}

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

private val NO_FIELD = Any()
private val fieldCache = java.util.concurrent.ConcurrentHashMap<Pair<Class<*>, String>, Any>()

/** 反射读写字段，父类里的也认；缓存 Field 避免高频调用（如 MsgRecord 读取）反复分配 declaredFields 数组。 */
private fun Any.field(name: String): java.lang.reflect.Field? {
    val key = javaClass to name
    val cached = fieldCache[key]
    if (cached != null) return cached as? java.lang.reflect.Field
    val found = generateSequence<Class<*>>(javaClass) { it.superclass }
        .firstNotNullOfOrNull { c -> c.declaredFields.firstOrNull { it.name == name } }
        ?.apply { isAccessible = true }
    fieldCache[key] = found ?: NO_FIELD
    return found
}

fun Any.set(name: String, value: Any?) =
    (field(name) ?: throw NoSuchFieldException("${javaClass.name}#$name")).set(this, value)

fun Any.get(name: String): Any? = field(name)?.get(this)
