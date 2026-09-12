/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.diagnostics

import android.os.Process
import io.github.qauxv.config.ConfigManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One bounded writer per process; fixed MMKV keys shared by the main and MSF processes. */
internal object ReportDiagnosticsStore {
    private const val ENABLED = "qself.report_diagnostics.enabled"
    private const val GENERATION = "qself.report_diagnostics.generation"
    private const val EPOCH = "qself.report_diagnostics.epoch"
    private const val PREFIX = "qself.report_diagnostics.snapshot."
    private const val MAX_SNAPSHOT = 80_000
    private val sources = listOf("main", "msf")
    private val dropped = AtomicLong()
    private val failures = AtomicLong()
    private val skipped = AtomicLong()
    private val observerFailures = AtomicLong()

    fun noteSkipped() { skipped.incrementAndGet() }
    fun noteObserverFailure() { observerFailures.incrementAndGet() }
    private val worker by lazy {
        ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128),
            { task -> Thread(task, "Qself-ReportDiagnostics").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    }

    var enabled: Boolean
        get() = ConfigManager.getDefaultConfig().getBooleanOrDefault(ENABLED, false)
        set(value) {
            val config = ConfigManager.getDefaultConfig()
            config.putBoolean(ENABLED, false)
            config.putString(GENERATION, UUID.randomUUID().toString())
            if (value) config.putBoolean(ENABLED, true)
        }

    fun generation(): String = ConfigManager.getDefaultConfig().getStringOrDefault(GENERATION, "")

    fun epoch(): String = ConfigManager.getDefaultConfig().getStringOrDefault(EPOCH, "")

    /** Call on an IO worker. A new epoch hides old data and invalidates queued/in-flight events. */
    @Synchronized
    fun clear(): String {
        val epoch = UUID.randomUUID().toString()
        ConfigManager.getDefaultConfig().putString(EPOCH, epoch)
        for (source in sources) ConfigManager.getCache().remove(PREFIX + source)
        dropped.set(0)
        failures.set(0)
        skipped.set(0)
        observerFailures.set(0)
        return epoch
    }

    fun record(source: String, phase: String, details: String, expectedEpoch: String = epoch(),
               timestamp: Long = System.currentTimeMillis(), observedCall: Boolean = false,
               expectedGeneration: String = generation()) {
        if (source !in sources || expectedEpoch.isEmpty()) return
        val pid = Process.myPid()
        // All parameters here are already metadata, never a host object or hook parameter.
        try {
            worker.execute {
                try {
                    if (!enabled || epoch() != expectedEpoch || generation() != expectedGeneration) return@execute
                    persist(source, expectedEpoch, timestamp, pid, phase, details, observedCall, expectedGeneration)
                } catch (_: Throwable) {
                    failures.incrementAndGet() // Never forward failures/messages to host or AppCenter.
                }
            }
        } catch (_: Throwable) {
            dropped.incrementAndGet() // Saturation must not block a QQ sending thread.
        }
    }

    @Synchronized
    private fun persist(source: String, expectedEpoch: String, timestamp: Long, pid: Int, phase: String,
                        details: String, observedCall: Boolean, expectedGeneration: String) {
        if (!enabled || epoch() != expectedEpoch || generation() != expectedGeneration) return
        val store = ConfigManager.getCache()
        val snapshot = readSnapshot(source, expectedEpoch)
        val array = snapshot.optJSONArray("lines") ?: JSONArray()
        val old = (0 until minOf(array.length(), ReportMetadata.MAX_LINES)).map { array.optString(it) }
        val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestamp))
        val line = "$date pid=$pid $phase $details"
        val lines = ReportMetadata.append(old, line)
        snapshot.put("epoch", expectedEpoch)
        snapshot.put("lines", JSONArray(lines))
        snapshot.put("observed", snapshot.optLong("observed") + if (observedCall) 1 else 0)
        snapshot.put("droppedInWriter", dropped.get())
        snapshot.put("writerFailures", failures.get())
        snapshot.put("skippedCalls", skipped.get())
        snapshot.put("observerFailures", observerFailures.get())
        if (phase == "INSTALL") snapshot.put("installation", line)
        // An old writer may race another process clearing the epoch. Its snapshot is tagged
        // with the old epoch and is never presented as part of the new observation window.
        if (enabled && epoch() == expectedEpoch && generation() == expectedGeneration) store.putString(PREFIX + source, snapshot.toString())
    }

    private fun readSnapshot(source: String, epoch: String): JSONObject {
        val raw = ConfigManager.getCache().getStringOrDefault(PREFIX + source, "")
        if (raw.length > MAX_SNAPSHOT) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrNull()
            ?.takeIf { it.optString("epoch") == epoch } ?: JSONObject()
    }

    /** Read off the UI thread. The result contains no payload, credentials or exception messages. */
    fun report(): String {
        val epoch = epoch()
        return buildString {
            appendLine("当前查看进程即时诊断：队列丢弃=${dropped.get()} 写入异常=${failures.get()} 跳过=${skipped.get()} 观察异常=${observerFailures.get()}")
            for (source in sources) {
                val snapshot = readSnapshot(source, epoch)
                appendLine("\n[$source] 已记录入口（采样后）=${snapshot.optLong("observed")}")
                appendLine("队列丢弃=${snapshot.optLong("droppedInWriter")} 写入异常=${snapshot.optLong("writerFailures")}")
                appendLine("限流/在途上限跳过=${snapshot.optLong("skippedCalls")} 观察异常=${snapshot.optLong("observerFailures")}")
                appendLine(snapshot.optString("installation", "本观察窗口无安装记录：未初始化、未覆盖或尚未运行；不代表没有上报。"))
                val lines = snapshot.optJSONArray("lines") ?: JSONArray()
                val safe = ReportMetadata.append(
                    (0 until minOf(lines.length(), ReportMetadata.MAX_LINES)).map { lines.optString(it) }, "")
                safe.forEach { appendLine(it) }
            }
        }
    }
}
