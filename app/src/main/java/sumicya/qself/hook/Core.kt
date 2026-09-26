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

/** Process-wide state inside QQ. Settings come in live through LSPosed remote preferences. */
object Core {
    lateinit var module: XposedModule
        private set
    lateinit var loader: ClassLoader
        private set

    /** Process name of this process (main QQ process, or e.g. :qzone). */
    var process: String = ""
        private set

    /** Called by the entry point as soon as the framework tells us where we landed. */
    fun onProcess(name: String) {
        process = name
    }

    private val main = Handler(Looper.getMainLooper())
    private val infra = mutableListOf<XposedInterface.HookHandle>()
    private var prefs: SharedPreferences? = null
    private var front = WeakReference<Activity>(null)
    private var reporter: BroadcastReceiver? = null
    private var started = false

    val features: List<Feature> by lazy {
        listOf(
            TabBar.Glass, TabBar.HideGuild, TabBar.HideFeed,
            InputBar.Tg, GlassTitle, GlassChat, TgTitleBar, TgDrawer,
            PlainBubble, PlainFont, PlainNick, NoPendant,
            PlusPanel, MultiForward, AntiRecall, NoLightInteraction, NoDropSticker,
            SystemWebView, NoTelemetry, NoCrashReport,
            Dump,
        )
    }

    /** The Activity in front, or null while QQ is in the background. */
    val activity: Activity? get() = front.get()

    private val isMain get() = process == Catalog.QQ

    /** Held strongly: SharedPreferences keeps only weak references to its listeners. */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        main.post { if (key == null) syncAll() else Catalog.byId[key]?.let { sync(feature(it.id)) } }
    }

    fun start(module: XposedModule, loader: ClassLoader) {
        this.module = module
        this.loader = loader
        prefs = runCatching { module.getRemotePreferences(Catalog.PREFS) }
            .onFailure { log(Log.WARN, "remote preferences unavailable, using defaults: $it") }
            .getOrNull()
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        if (isMain) trackActivities()
        syncAll()
        started = true
        log(Log.INFO, "started in $process")
    }

    fun stop() {
        if (!started) return
        started = false
        onMain {
            reporter?.let { r -> runCatching { context?.unregisterReceiver(r) } }
            reporter = null
            prefs?.unregisterOnSharedPreferenceChangeListener(listener)
            features.forEach { it.disable() }
            GlassKit.clearAll()
            Views.clearAll()
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

    private fun feature(id: String) = features.firstOrNull { it.id == id }

    private fun syncAll() = features.forEach(::sync)

    private fun sync(f: Feature?) {
        f ?: return
        val want = isMain || !f.mainOnly
        if (want && isEnabled(f.id)) {
            f.enable()
            activity?.let { a -> runCatching { f.onResume(a) } }
        } else {
            f.disable()
        }
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
        features.forEach { if (it.active) runCatching { it.onResume(activity) } }
    }

    /** After a hot reload nobody tells us which Activity is in front; ask ActivityThread. */
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
        }.onFailure { log(Log.WARN, "could not find the front Activity: $it") }
    }

    fun applicationClassLoader(): ClassLoader? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
    }.getOrNull().let { (it as? Application)?.classLoader ?: activity?.classLoader }

    /** QQ answers a status request from the settings app here. */
    private var context: Context? = null

    private fun listen(ctx: Context) {
        if (reporter != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                resultData = runCatching { report() }.getOrElse { "报告失败: $it" }
            }
        }
        runCatching { ctx.registerReceiver(receiver, IntentFilter(Catalog.ACTION_REPORT), Context.RECEIVER_EXPORTED) }
            .onSuccess { reporter = receiver; context = ctx }
            .onFailure { log(Log.WARN, "report receiver: $it") }
    }

    private fun report(): String = buildString {
        val pkg = runCatching { context?.packageManager?.getPackageInfo(Catalog.QQ, 0) }.getOrNull()
        appendLine("Qself ${BuildConfig.VERSION_NAME} · QQ ${pkg?.versionName ?: "?"} · ${process}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("前台: ${activity?.javaClass?.name ?: "-"}")
        for (f in features) {
            append(if (f.active) "✓ " else if (f.error != null) "✗ " else "· ").append(f.id)
            when {
                f.active -> {
                    if (f.hookCount > 0) append("  钩子 ").append(f.hookCount)
                    f.status()?.let { append(" · ").append(it) }
                }
                f.error != null -> append("  失败: ").append(f.error)
                else -> append("  关")
            }
            appendLine()
        }
    }

    /** Views may only be touched on the main thread, and hot reload callbacks need not be on it. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        main.post { try { block() } finally { done.countDown() } }
        done.await(3, TimeUnit.SECONDS)
    }
}
