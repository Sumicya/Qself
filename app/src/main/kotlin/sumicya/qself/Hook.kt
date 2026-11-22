// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** 框架句柄与宿主的 ClassLoader，install 时赋值。 */
lateinit var xposed: XposedInterface
lateinit var loader: ClassLoader

/** 只写 LSPosed 日志页与内存诊断环。xposed 可能还没赋值，两条通道各自兜住，别让报错路径再出错。 */
fun log(msg: String, t: Throwable? = null) {
    runCatching { recordQselfEvent(msg, t) }
    runCatching { xposed.log(Log.INFO, "Diag", msg, t) }
}

fun cls(name: String): Class<*> = Class.forName(name, false, loader)

/** 正在装的功能名。hook() 把它记进钩子，运行时按开关决定走不走；null = 不受开关管。 */
var feature: String? = null

/** 本项功能已装上的钩子，装失败时倒着撤。 */
private var installing: MutableList<XposedInterface.HookHandle>? = null

/** 每项功能安装原子化：一项里第三个钩子失败就把前两个撤掉，不留半个功能。 */
fun installFeature(name: String?, body: () -> Any?) {
    check(installing == null) { "不支持嵌套安装" }
    val handles = ArrayList<XposedInterface.HookHandle>()
    installing = handles
    val previous = feature
    feature = name
    try {
        body()
    } catch (failure: Throwable) {
        handles.asReversed().forEach { handle ->
            runCatching { handle.unhook() }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    } finally {
        installing = null
        feature = previous
    }
}

/** 记下 proceed 走没走过、返回值是什么，供 hook() 在异常时退回。 */
private class SafeChain(private val delegate: Chain) : Chain by delegate {
    var proceeded = false
    var outcome: Any? = null

    var targetFailure: Throwable? = null

    private inline fun <T> record(block: () -> T): T {
        proceeded = true
        return try {
            block().also { outcome = it }
        } catch (t: Throwable) {
            targetFailure = t
            throw t
        }
    }

    override fun proceed(): Any? = record { delegate.proceed() }
    override fun proceed(args: Array<out Any?>): Any? = record { delegate.proceed(args) }
    override fun proceedWith(thisObject: Any, args: Array<out Any?>): Any? =
        record { delegate.proceedWith(thisObject, args) }
}

/** 装钩子。目标自己抛的异常原样透传；钩子自己抛的记日志并退回（前置走 proceed、后置给原返回值）。 */
fun hook(
    target: Executable,
    gated: Boolean = true,
    counted: Boolean = true,
    body: (Chain) -> Any?,
): XposedInterface.HookHandle {
    val gate = if (gated) feature else null
    val signature = "${target.declaringClass.name}#${target.name}"
    // QLog 自己就是日志通道，记它会自激。
    val isQLog = target.declaringClass.name == "com.tencent.qphone.base.util.QLog"

    return xposed.hook(target).intercept { chain ->
        if (gate != null && !on(gate)) return@intercept chain.proceed()

        val safe = SafeChain(chain)
        try {
            body(safe).also { if (counted && !isQLog) runCatching { recordHookInvocation(signature, safe.thisObject, safe.args, it) } }
        } catch (t: Throwable) {
            if (t === safe.targetFailure) throw t
            log("钩子${if (safe.proceeded) "后置" else "前置"}异常: ${target.declaringClass.simpleName}#${target.name}", t)
            if (safe.proceeded) safe.outcome else chain.proceed()
        }
    }.also { installing?.add(it) }
}

// ── 开关 ─────────────────────────────────────────────────────────────────────

private const val PREFS = "app_cfg"
private var prefs: SharedPreferences? = null

/** 读到没登记的开关名时每个进程只报一次，免得「代码在读、清单里没有」永远静默。 */
private val unknownSwitches = ConcurrentHashMap.newKeySet<String>()

internal var storeOverride: (() -> SharedPreferences?)? = null // 测试替身；生产路径永不设置

fun on(name: String): Boolean {
    val known = featureDefaults[name]
    if (known == null && unknownSwitches.add(name)) runCatching { log("开关未登记 : $name（按默认开启）") }
    return name !in failed && (store()?.getBoolean(name, known ?: true) ?: (known ?: true))
}

fun store(): SharedPreferences? = storeOverride?.invoke() ?: prefs ?: runCatching {
    (Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context)
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}.getOrNull()?.also { prefs = it }

// ── 反射 ─────────────────────────────────────────────────────────────────────

/** 名字满足 pick 的方法改成固定返回 value。本类没有就往父类找，就近命中即停。 */
fun Class<*>.constant(value: Any?, pick: (Method) -> Boolean) {
    val targets = generateSequence<Class<*>>(this) { it.superclass }
        .firstNotNullOfOrNull { c -> c.declaredMethods.filter(pick).takeIf { it.isNotEmpty() } }
        .orEmpty()
    require(targets.isNotEmpty()) { "$simpleName: 没有匹配的方法" }
    targets.forEach { m -> hook(m) { value } }
}

/** 按名字取方法，本类没有就往父类找。 */
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

/** 缓存未命中的哨兵（ConcurrentHashMap 不收 null）。 */
private val NO_FIELD = Any()

/** key 是 (声明所在类, 字段名) 而不是 (实例类, 字段名)：子类实例读父类字段时共用同一条缓存。 */
private val fieldCache = ConcurrentHashMap<Pair<Class<*>, String>, Any>()

private fun Any.field(name: String): Field? {
    // 先按实例类猜一次命中的情形最多（字段就声明在自己类上），命中就不用走父类链。
    val own = javaClass
    fieldCache[own to name]?.let { return it as? Field }

    var c: Class<*>? = own
    while (c != null) {
        val cached = fieldCache[c to name]
        if (cached != null) return cached as? Field
        val found = c.declaredFields.firstOrNull { it.name == name }?.apply { isAccessible = true }
        if (found != null) {
            fieldCache[c to name] = found
            return found
        }
        fieldCache[c to name] = NO_FIELD
        c = c.superclass
    }
    return null
}

fun Any.set(name: String, value: Any?) =
    (field(name) ?: throw NoSuchFieldException("${javaClass.name}#$name")).set(this, value)

fun Any.get(name: String): Any? = field(name)?.get(this)
