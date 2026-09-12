/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics

import android.os.Process
import io.github.qauxv.BuildConfig
import io.github.qauxv.config.ConfigManager
import io.github.qauxv.util.SyncUtils
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Local metadata only. Never lets a diagnostic failure affect a hook's result. */
object FeatureJournal {
    private const val PREFIX = "qself.feature_journal."
    private const val MAX_CHARS = 90000
    private val sources = listOf("main", "msf", "other")
    private val dropped = AtomicLong()
    private val errors = AtomicLong()
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "Qself-feature-journal").apply { isDaemon = true } },
        { _, _ -> dropped.incrementAndGet() })
    private val source get() = when { SyncUtils.isMainProcess() -> "main"; SyncUtils.isTargetProcess(SyncUtils.PROC_MSF) -> "msf"; else -> "other" }
    var enabled: Boolean
        get() = runCatching { ConfigManager.getDefaultConfig().getBooleanOrDefault(PREFIX + "enabled", true) }.getOrDefault(false)
        set(value) { ConfigManager.getDefaultConfig().putBoolean(PREFIX + "enabled", value) }
    private fun epoch() = ConfigManager.getCache().getStringOrDefault(PREFIX + "epoch", "0")
    private fun <T> locked(block: () -> T): T = ReportFileLock.withLock(
        File(requireNotNull(ConfigManager.getCache().file?.parentFile), "qself_feature_journal.lock")) { block() }
    @JvmStatic fun toggle(id: String, before: Boolean, after: Boolean) {
        if (before != after) record("SWITCH", id, "$before->$after")
    }
    @JvmStatic fun error(id: String, error: Throwable) {
        // No Throwable.message, argument values, accounts, commands or filenames.
        val frames = error.stackTrace.take(6).joinToString(";") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        record("ERROR", id, "${error.javaClass.name} $frames")
    }
    @JvmStatic fun record(phase: String, id: String, detail: String = "") {
        try {
            if (!enabled) return
            val generation = epoch()
            val process = source
            val line = "${System.currentTimeMillis()} pid=${Process.myPid()} build=${BuildConfig.VERSION_NAME} $phase ${id.take(180)} ${detail.take(900)}"
            worker.execute {
                try { locked {
                    if (!enabled || epoch() != generation) return@locked
                    val cache = ConfigManager.getCache()
                    val raw = cache.getStringOrDefault(PREFIX + process, "[]")
                    val old = if (raw.length <= MAX_CHARS) runCatching { JSONArray(raw) }.getOrDefault(JSONArray()) else JSONArray()
                    val next = JSONArray()
                    for (i in maxOf(0, old.length() - 95) until old.length()) next.put(old.optString(i).take(1200))
                    next.put(line)
                    cache.putString(PREFIX + process, next.toString().takeIf { it.length <= MAX_CHARS } ?: JSONArray().put(line).toString())
                } } catch (_: Throwable) { errors.incrementAndGet() }
            }
        } catch (_: Throwable) { errors.incrementAndGet() }
    }
    /** Report/clear called on an IO worker, never a hook callback. */
    fun report(): String = locked {
        buildString {
            appendLine("功能记录 v1 · 本地元数据 · 毫秒时间戳（UTC epoch）")
            appendLine("记录=$enabled 当前进程队列丢弃=${dropped.get()} 写入异常=${errors.get()}")
            appendLine("INIT_END=true 仅代表初始化返回成功；不代表功能执行或网络送达。缺失 END 也不能单独证明崩溃。")
            appendLine("范围：通用功能基类初始化/错误、设置开关；不捕获原生崩溃或所有自定义实现。每进程类别最多96条。")
            sources.forEach { process ->
                appendLine("\n[$process]")
                val raw = ConfigManager.getCache().getStringOrDefault(PREFIX + process, "[]")
                val lines = if (raw.length <= MAX_CHARS) runCatching { JSONArray(raw) }.getOrDefault(JSONArray()) else JSONArray()
                for (i in 0 until lines.length()) appendLine(lines.optString(i))
            }
        }
    }
    fun clear() = locked {
        val cache = ConfigManager.getCache()
        cache.putString(PREFIX + "epoch", java.util.UUID.randomUUID().toString())
        sources.forEach { cache.remove(PREFIX + it) }
    }
}
