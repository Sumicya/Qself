/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.config

import org.json.JSONObject
import sumicya.qself.log.QLog
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cross-process settings storage.
 *
 * The authoritative copy is a single JSON file in the host app's files dir:
 * `<hostFilesDir>/qself/settings.json`.
 *
 * - The runtime (host process) reads and writes it directly — same uid, and
 *   it never shells out: an Xposed hook must not make QQ's startup wait on a
 *   root prompt.
 * - The settings UI (module process) cannot cross uids, so it goes through a
 *   `su` bridge. Every user of a QQ Xposed module has root (LSPosed /
 *   KernelSU), which makes this the simplest correct design; without `su`
 *   the UI falls back to a local cache and says so in the diagnostics row.
 *
 * Toggles take effect when the host process next starts (v1 contract — no
 * live reload, no file I/O on hook paths).
 */
class SettingsBridge(
    private val hostPackage: String,
    private val hostFilesDir: File?,
    private val localCache: Settings?,
    /** True only for the module-side bridge: allows `su` reads and writes. */
    private val useSuBridge: Boolean = false,
) {

    val sharedFile: File? = hostFilesDir?.let { File(it, "qself/settings.json") }

    private val inMemory: MutableMap<String, Boolean> = LinkedHashMap()
    private val lock = Any()

    /** Serialises the blocking file/`su` work away from the caller's thread. */
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qself-settings-io").apply { isDaemon = true }
    }

    /**
     * Load the authoritative copy into memory. Blocking (may run `su`), so
     * the UI calls it off the main thread. Returns false when nothing
     * readable was found (callers then rely on [localCache]).
     */
    @Synchronized
    fun load(): Boolean {
        val text = readShared() ?: return false
        return parse(text)
    }

    private fun parse(text: String): Boolean {
        return try {
            val json = JSONObject(text)
            val loaded = LinkedHashMap<String, Boolean>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.startsWith(ENABLED_PREFIX)) {
                    loaded[key] = json.getBoolean(key)
                }
            }
            synchronized(lock) {
                inMemory.clear()
                inMemory.putAll(loaded)
            }
            // Keep the UI cache a mirror of the authoritative copy, so a
            // later write that cannot re-read the file still round-trips
            // every switch instead of clobbering the ones it never saw.
            mirrorToLocalCache(loaded)
            true
        } catch (t: Throwable) {
            QLog.w("Settings", "failed to parse shared settings", t)
            false
        }
    }

    private fun readShared(): String? {
        val file = sharedFile ?: return null
        // Same uid (host process): plain read. Different uid (module UI
        // process): the path is not even stat-able, so exists() must not gate
        // the su bridge — an absent-looking file is exactly the case it is
        // there for.
        val direct = try {
            if (file.exists() && file.canRead()) file.readText() else null
        } catch (t: Throwable) {
            QLog.d("Settings", "direct read failed", t)
            null
        }
        if (direct != null) {
            return direct
        }
        if (!useSuBridge) {
            return null
        }
        return suRead(file).also {
            if (it == null) {
                QLog.d("Settings", "shared settings unreadable (no su?)")
            }
        }
    }

    private fun mirrorToLocalCache(values: Map<String, Boolean>) {
        val cache = localCache ?: return
        for ((key, value) in values) {
            cache.setEnabled(key.removePrefix(ENABLED_PREFIX), value)
        }
    }

    /**
     * Persist a switch. The in-memory copy and the UI cache are updated
     * immediately (the switch must not wait for root), while the shared file
     * is written on a background thread.
     */
    @Synchronized
    fun setEnabled(featureId: String, value: Boolean) {
        val key = ENABLED_PREFIX + featureId
        synchronized(lock) {
            inMemory[key] = value
        }
        localCache?.setEnabled(featureId, value)
        submitWrite()
    }

    @Synchronized
    fun isEnabled(featureId: String, defaultEnabled: Boolean): Boolean {
        val key = ENABLED_PREFIX + featureId
        synchronized(lock) {
            inMemory[key]?.let { return it }
        }
        return localCache?.isEnabled(featureId, defaultEnabled) ?: defaultEnabled
    }

    /** Runs [writeShared] on the IO thread; failures stay in the log. */
    private fun submitWrite() {
        io.execute {
            try {
                writeShared()
            } catch (t: Throwable) {
                QLog.w("Settings", "failed to write shared settings", t)
            }
        }
    }

    @Synchronized
    private fun writeShared() {
        val file = sharedFile ?: return
        // Fold the local cache in first: the file mirrors every switch this
        // process knows about, not just the one just toggled.
        localCache?.all?.forEach { (k, v) ->
            if (k is String && k.startsWith(ENABLED_PREFIX) && v is Boolean) {
                synchronized(lock) { inMemory[k] = v }
            }
        }
        val json = JSONObject()
        synchronized(lock) {
            inMemory.forEach { (k, v) -> json.put(k, v) }
        }
        val text = json.toString()
        val direct = !useSuBridge || canWriteDirectly(file)
        if (direct) {
            file.parentFile?.mkdirs()
            file.writeText(text)
        } else {
            suWrite(file, text)
        }
    }

    private fun canWriteDirectly(file: File): Boolean {
        val dir = file.parentFile ?: return false
        return dir.exists() && dir.canWrite()
    }

    private fun suRead(file: File): String? {
        val exit = execSu(arrayOf("cat", file.absolutePath)) ?: return null
        if (exit != 0) return null
        return lastStdout
    }

    private fun suWrite(file: File, text: String) {
        val dir = file.parentFile?.absolutePath ?: return
        // Everything is single-quoted for the root shell, so JSON quotes,
        // spaces and newlines survive untouched.
        val cmd = "mkdir -p ${shellQuote(dir)} && printf %s ${shellQuote(text)} > ${shellQuote(file.absolutePath)}"
        val code = execSu(arrayOf("sh", "-c", cmd))
        if (code != 0) {
            QLog.w("Settings", "su write failed (exit=$code)")
        }
    }

    private var lastStdout: String = ""

    /**
     * Runs `su 0 -c <command>`; returns the exit code, or null when `su` is
     * unavailable. Capped by [SU_TIMEOUT_SECONDS] so a pending root prompt
     * cannot hang the caller forever.
     */
    private fun execSu(args: Array<String>): Int? {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "0", "-c", args.joinToString(" ") { shellQuote(it) }),
            )
            val stdout = process.inputStream.bufferedReader()
            val stderr = process.errorStream.bufferedReader()
            if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                stdout.close()
                stderr.close()
                QLog.w("Settings", "su timed out after ${SU_TIMEOUT_SECONDS}s")
                return null
            }
            lastStdout = stdout.use { it.readText() }
            val errors = stderr.use { it.readText() }
            if (errors.isNotEmpty()) {
                QLog.d("Settings", "su stderr: ${errors.trim()}")
            }
            val code = process.exitValue()
            if (code == 127) null else code
        } catch (t: Throwable) {
            QLog.d("Settings", "su bridge unavailable", t)
            null
        }
    }

    private fun shellQuote(value: String): String =
        if (value.isEmpty()) "''" else "'" + value.replace("'", "'\\''") + "'"

    companion object {
        const val ENABLED_PREFIX = "feature.enabled."
        private const val SU_TIMEOUT_SECONDS = 10L
    }
}
