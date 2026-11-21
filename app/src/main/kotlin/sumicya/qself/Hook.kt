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

/**
 * 框架句柄与宿主的 ClassLoader，install 时赋值。两者都只写一次，之后只读。
 * 用 lateinit 而不是可空：装钩子这件事本身就要求它们已在位，缺了应当早崩而不是静默降级。
 */
lateinit var xposed: XposedInterface
lateinit var loader: ClassLoader

/** 只写 LSPosed 管理器的日志页与内存诊断环：宿主进程自己的 logcat 里不留本模块的名字。 */
fun log(msg: String, t: Throwable? = null) {
    runCatching { recordQselfEvent(msg, t) }
    xposed.log(Log.INFO, "Diag", msg, t)
}

fun cls(name: String): Class<*> = Class.forName(name, false, loader)

/** 正在装的功能名。hook() 把它记进钩子，运行时按开关决定走不走；null = 不受开关管。 */
var feature: String? = null

/** 本项功能已装上的钩子，装失败时按安装顺序倒着撤。只有在 installFeature 里才非空。 */
private var installing: MutableList<XposedInterface.HookHandle>? = null

/**
 * 每项功能安装原子化：一项里装了三个钩子，第三个失败就把前两个撤掉。
 * 不这么做的话开关面板显示 ✗、实际却有半个功能在生效，比整项没装更难查。
 */
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

/**
 * 包一层 Chain，记下「proceed 有没有走过」和「走完的返回值是什么」。
 *
 * 为什么需要：libxposed 默认的 PROTECTIVE 只兜住 proceed 之前抛的异常；钩子体在 proceed 之后
 * 抛异常（典型是 `.also { }` 块里）时，异常会一路抛回宿主，把 QQ 弄崩。有了这两个字段，
 * Hook.kt 里的 hook() 才能在那种情况下退回 `outcome`，让调用方拿到原返回值。
 */
private class SafeChain(private val delegate: Chain) : Chain by delegate {
    var proceeded = false
    var outcome: Any? = null

    /** 目标方法自己抛的异常，原样透传，不当作钩子的错。 */
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

/**
 * 装一个钩子。异常策略分三种：
 *  ① 目标方法自己抛 → 原样抛出，不吞。
 *  ② 钩子在 proceed 之前抛 → 记日志，退回 chain.proceed()（让宿主照原样跑）。
 *  ③ 钩子在 proceed 之后抛 → 记日志，退回 safe.outcome（把原返回值给回去）。
 * ②③ 都是为了「钩子坏了不能连累宿主」，代价是这一钩的效果静默失效——所以两条都写日志。
 *
 * @param gated true 时受 feature 开关管；false 时无条件生效。
 * @param counted false 给「每条消息/每帧都走」的钩子用：省掉一次查表与参数摘要分配。
 */
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
            val result = body(safe)
            if (counted && !isQLog) {
                runCatching { recordHookInvocation(signature, safe.thisObject, safe.args, result) }
            }
            result
        } catch (t: Throwable) {
            if (t === safe.targetFailure) throw t
            if (safe.proceeded) {
                log("钩子后置异常: ${target.declaringClass.simpleName}#${target.name}", t)
                safe.outcome
            } else {
                log("钩子前置异常: ${target.declaringClass.simpleName}#${target.name}", t)
                chain.proceed()
            }
        }
    }.also { installing?.add(it) }
}

// ── 开关 ─────────────────────────────────────────────────────────────────────

private const val PREFS = "app_cfg"
private var prefs: SharedPreferences? = null

/**
 * 开关的读缓存。
 *
 * 为什么需要：on() 在真机上是热路径——会话列表滚动时 dispatchDraw 每帧都要问一次「玻璃底栏」，
 * 每条消息要问若干次「连发合并」「气泡统一」。SharedPreferences.getBoolean 不是免费的内存读：
 * 首次要加载整个文件，之后每次也要过锁。每帧一次是白烧的。
 *
 * 什么时候失效：像设置页那样从本进程写 prefs 时，监听器会调 refreshLooks()，那里调
 * invalidateSwitchCache()。跨进程写（另一个进程里的 QQ 自己改）不在覆盖范围内——那本来也
 * 只有重启 QQ 才生效，见 README。
 */
private val switchCache = ConcurrentHashMap<String, Boolean>()

/** 清掉开关缓存。凡是「改了开关要立刻生效」的地方都该调一次。 */
fun invalidateSwitchCache() {
    switchCache.clear()
}

/** 读到没登记的开关名时每个进程只报一次，免得「代码在读、清单里没有」永远静默。 */
private val unknownSwitches = ConcurrentHashMap.newKeySet<String>()

/**
 * 开关存在应用私有 SharedPreferences「app_cfg」里，缺省全开。
 * Application 还没建起来时 store() 为 null，此时按登记表的默认值答，不当作关闭。
 */
fun on(name: String): Boolean {
    switchCache[name]?.let { return it }

    val known = featureDefaults[name]
    if (known == null && unknownSwitches.add(name)) {
        runCatching { log("开关未登记 : $name（清单里没有，按默认开启）") }
    }

    // 装失败的功能一律视为关闭：它根本没生效，报「开」会让面板与行为对不上。
    val value = if (name in failed) false else (store()?.getBoolean(name, known ?: true) ?: (known ?: true))
    switchCache[name] = value
    return value
}

/**
 * 测试替身入口：非 null 时 store() 直接用它。
 * 生产路径永远不设置它；只让单测能把「会数数的假 prefs」塞进来。
 */
internal var storeOverride: (() -> SharedPreferences?)? = null

fun store(): SharedPreferences? = storeOverride?.invoke() ?: prefs ?: runCatching {
    (Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context)
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}.getOrNull()?.also { prefs = it }

// ── 反射 ─────────────────────────────────────────────────────────────────────

/**
 * 名字满足 [pick] 的方法全部改成固定返回 [value]。
 *
 * 找法是「本类没有就往父类找，就近命中即停」——QQ 的门方法大半声明在 Api 基类上，只搜本类
 * 一个也搜不着；而子类若重写过，就该用子类那份。一个都没匹配到就当找错了类，抛出。
 * pick 写宽了会连父类的方法一起改，调用方自己当心。
 */
fun Class<*>.constant(value: Any?, pick: (Method) -> Boolean) {
    val targets = generateSequence<Class<*>>(this) { it.superclass }
        .firstNotNullOfOrNull { c -> c.declaredMethods.filter(pick).takeIf { it.isNotEmpty() } }
        .orEmpty()
    require(targets.isNotEmpty()) { "$simpleName: 没有匹配的方法" }
    targets.forEach { m -> hook(m) { value } }
}

/**
 * 按名字取方法，本类没有就往父类找。
 *
 * 为什么往上找：QQ 的挂点大半声明在 MVVM 基类上，钩子因此盖住所有子类——
 * 钩子体自己按运行时类型过滤，别指望它只落在一个类上。
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

/** 缓存未命中的哨兵：ConcurrentHashMap 不收 null，用这个占位表示「这个类没有这个字段」。 */
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
