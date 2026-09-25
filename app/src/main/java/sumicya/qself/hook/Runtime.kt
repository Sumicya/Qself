// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself.hook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import sumicya.qself.Catalog

/** Process-wide state inside QQ. Settings arrive live through LSPosed remote preferences. */
object Runtime {
    lateinit var module: XposedModule
        private set
    lateinit var loader: ClassLoader
        private set
    var isMain = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val infra = mutableListOf<XposedInterface.HookHandle>()
    private var prefs: SharedPreferences? = null
    private var resumed = WeakReference<Activity>(null)

    val features: List<Feature> = listOf(
        HomeBar.Glass, HomeBar.HideGuild, HomeBar.HideFeed,
        TgInputBar, TgTitleBar, TgDrawer,
        PlainBubble, PlainFont, NoPendant,
        AntiRecall, MultiForward, NoLightInteraction, NoDropSticker,
        SystemWebView, NoTelemetry, NoCrashReport,
        Dump,
    )

    // Held strongly: SharedPreferences only keeps weak references to listeners.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        main.post { if (key == null) syncAll() else features.firstOrNull { it.id == key }?.let(::sync) }
    }

    fun log(priority: Int, msg: String) {
        runCatching { module.log(priority, "Qself", msg) }
    }

    fun start(module: XposedModule, loader: ClassLoader, process: String) {
        this.module = module
        this.loader = loader
        isMain = process == Catalog.QQ
        prefs = runCatching { module.getRemotePreferences(Catalog.PREFS) }
            .onFailure { log(Log.WARN, "no remote preferences, using defaults: $it") }
            .getOrNull()
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        if (isMain) trackActivities()
        syncAll()
        log(Log.INFO, "started in $process")
    }

    fun stop() = onMain {
        prefs?.unregisterOnSharedPreferenceChangeListener(listener)
        features.forEach { it.disable() }
        infra.forEach { runCatching { it.unhook() } }
        infra.clear()
    }

    fun enabled(id: String): Boolean =
        prefs?.getBoolean(id, Catalog.byId[id]?.default ?: false) ?: (Catalog.byId[id]?.default ?: false)

    private fun syncAll() = features.forEach(::sync)

    private fun sync(f: Feature) {
        val want = enabled(f.id) && (isMain || !f.mainOnly)
        if (want == f.active) return
        if (want) {
            f.enable()
            resumed.get()?.let { a -> runCatching { f.onResume(a) } }
        } else {
            f.disable()
        }
    }

    private fun trackActivities() {
        val onResume = Instrumentation::class.java.getMethod("callActivityOnResume", Activity::class.java)
        infra += module.hook(onResume).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val r = chain.proceed()
            (chain.getArg(0) as? Activity)?.let(::resumed)
            r
        }
    }

    private fun resumed(a: Activity) {
        resumed = WeakReference(a)
        features.forEach { if (it.active) runCatching { it.onResume(a) } }
    }

    /** After a hot reload nobody tells us which Activity is in front; ask ActivityThread. */
    fun replayResumed() = main.post {
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null)
            val records = at.getDeclaredField("mActivities").apply { isAccessible = true }.get(thread) as Map<*, *>
            for (r in records.values) {
                r ?: continue
                val paused = r.javaClass.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(r)
                val a = r.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(r) as? Activity
                if (!paused && a != null) resumed(a)
            }
        }.onFailure { log(Log.WARN, "replay failed, waiting for next resume: $it") }
    }

    fun currentApplication(): Application? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application?
    }.getOrNull()

    /** Views may only be touched on the main thread; hot reload callbacks may not be on it. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        main.post { try { block() } finally { done.countDown() } }
        done.await(3, TimeUnit.SECONDS)
    }
}
