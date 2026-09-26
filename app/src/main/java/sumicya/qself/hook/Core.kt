// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import sumicya.qself.BuildConfig
import sumicya.qself.Catalog

/** QQ 进程里的全局状态。开关值从 LSPosed 远程 pref 实时进来。 */
object Core {
    lateinit var module: XposedModule
        private set
    lateinit var loader: ClassLoader
        private set

    /** 这个进程的名字（QQ 主进程，或 :qzone 之类）。 */
    var process: String = ""
        private set

    val switches: List<Switch> by lazy {
        listOf(
            PlainBubble, PlainFont, PlainNick, NoPendant,
            AntiRecall, MultiForward, PlusPanel, NoLightInteraction, NoDropSticker,
            SystemWebView, NoTelemetry, NoCrashReport,
        )
    }

    /** 入口点一知道我们落在哪个进程就调用。 */
    fun onProcess(name: String) {
        process = name
    }

    private val main = Handler(Looper.getMainLooper())
    private val infra = mutableListOf<XposedInterface.HookHandle>()
    private var prefs: SharedPreferences? = null
    private var front = WeakReference<Activity>(null)
    private var reporter: BroadcastReceiver? = null
    private var context: Context? = null
    private var started = false

    /** 前台的 Activity，QQ 在后台时是 null。 */
    val activity: Activity? get() = front.get()

    private val isMain get() = process == Catalog.QQ

    /** 强引用持有：SharedPreferences 只弱引用自己的监听器。 */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // 钩子只能在包加载时装，所以这里不装：开关写完 pref，设置页会紧跟着发一次热重载。
        if (key == null) main.post { syncAll() }
    }

    fun start(module: XposedModule, loader: ClassLoader) {
        this.module = module
        this.loader = loader
        prefs = runCatching { module.getRemotePreferences(Catalog.PREFS) }
            .onFailure { log(Log.WARN, "远程 pref 拿不到，先用默认值: $it") }
            .getOrNull()
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        if (isMain) trackActivities()
        for (s in switches) if (Catalog.byId[s.id] == null) log(Log.WARN, "开关 ${s.id} 没在 Catalog 里登记")
        for (i in Catalog.items) if (switches.none { it.id == i.id }) log(Log.WARN, "Catalog 里的 ${i.id} 没有对应代码")
        syncAll()
        started = true
        log(Log.INFO, "started in $process")
        // QQ 崩了就没法进 QQ 点「生成报告」，所以整份报告同时写进 LSPosed 日志（管理器 → 日志），
        // 那份日志在 QQ 崩了之后还在。
        for (line in report().trim().lineSequence()) log(Log.INFO, line)
    }

    fun stop() {
        if (!started) return
        started = false
        onMain {
            reporter?.let { r -> runCatching { context?.unregisterReceiver(r) } }
            reporter = null
            prefs?.unregisterOnSharedPreferenceChangeListener(listener)
            switches.forEach { it.disable() }
            infra.forEach { runCatching { it.unhook() } }
            infra.clear()
            prefs = null
        }
    }

    fun isEnabled(id: String): Boolean {
        val item = Catalog.byId[id] ?: return false
        return prefs?.getBoolean(id, item.default) ?: item.default
    }

    fun log(priority: Int, message: String) = runCatching { module.log(priority, "Qself", message) }

    private fun syncAll() = switches.forEach(::sync)

    private fun sync(s: Switch) {
        val want = isMain || !s.mainOnly
        if (want && isEnabled(s.id)) s.enable() else s.disable()
    }

    private fun trackActivities() {
        val onResume = Instrumentation::class.java.getMethod("callActivityOnResume", Activity::class.java)
        infra += module.hook(onResume).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            (chain.getArg(0) as? Activity)?.let(::resumed)
            result
        }
    }

    private fun resumed(activity: Activity) {
        front = WeakReference(activity)
        listen(activity.applicationContext)
    }

    /** 热重载之后没人告诉我们前台是哪个 Activity，去问 ActivityThread。 */
    fun replayActivity() = main.post {
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null)
            val records = at.getDeclaredField("mActivities").apply { isAccessible = true }.get(thread) as Map<*, *>
            for (record in records.values) {
                record ?: continue
                val paused = record.javaClass.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(record)
                val activity = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(record) as? Activity
                if (!paused && activity != null) resumed(activity)
            }
        }.onFailure { log(Log.WARN, "找不到前台 Activity: $it") }
    }

    fun applicationClassLoader(): ClassLoader? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
    }.getOrNull().let { (it as? Application)?.classLoader ?: activity?.classLoader }

    /** QQ 在这里回应设置页的状态询问。 */
    private fun listen(ctx: Context) {
        if (reporter != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                resultData = runCatching { report() }.getOrElse { "报告失败: $it" }
            }
        }
        runCatching { ctx.registerReceiver(receiver, IntentFilter(Catalog.ACTION_REPORT), Context.RECEIVER_EXPORTED) }
            .onSuccess { reporter = receiver; context = ctx }
            .onFailure { log(Log.WARN, "报告接收器: $it") }
    }

    private fun report(): String = buildString {
        val pkg = runCatching { context?.packageManager?.getPackageInfo(Catalog.QQ, 0) }.getOrNull()
        appendLine("Qself ${BuildConfig.VERSION_NAME} · QQ ${pkg?.versionName ?: "?"} · $process")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("前台: ${activity?.javaClass?.name ?: "-"}")
        for (s in switches) {
            append(if (s.active) "✓ " else if (s.error != null) "✗ " else "· ").append(s.id)
            when {
                s.active -> if (s.hookCount > 0) append("  钩子 ").append(s.hookCount)
                s.error != null -> append("  失败: ").append(s.error)
                (isMain || !s.mainOnly) && isEnabled(s.id) -> append("  等热重载")
                else -> append("  关")
            }
            appendLine()
        }
    }

    /** View 只能在主线程碰，而热重载回调不保证在主线程。 */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        main.post { try { block() } finally { done.countDown() } }
        done.await(3, TimeUnit.SECONDS)
    }
}
