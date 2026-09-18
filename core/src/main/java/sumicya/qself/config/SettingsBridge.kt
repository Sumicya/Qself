/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.config

import org.json.JSONObject
import sumicya.qself.log.QLog
import java.io.File

/**
 * Cross-process settings storage.
 *
 * The authoritative copy is a single JSON file in the host app's files dir:
 * `<hostFilesDir>/.qself/settings.json`.
 *
 * - The runtime (host process) reads and writes it directly — same uid.
 * - The settings UI (module process) cannot cross uids on a non-rooted
 *   device, so it goes through a `su` file bridge. Every target user of a
 *   QQ Xposed module has root (LSPosed/KernelSU), which makes this the
 *   simplest correct design; if `su` is unavailable the UI falls back to a
 *   local cache and shows a warning.
 *
 * Toggles take effect when the host process next starts (v1 contract —
 * no live reload, no file I/O on hot paths).
 */
class SettingsBridge(
    private val hostPackage: String,
    private val hostFilesDir: File?,
    private val localCache: Settings?,
) {

    val sharedFile: File? = hostFilesDir?.let { File(it, "qself/settings.json") }

    private val inMemory: MutableMap<String, Boolean> = LinkedHashMap()
    private val lock = Any()

    /**
     * Load the authoritative copy into memory. Returns false when nothing
     * readable was found (UI then relies on [localCache]).
     */
    @Synchronized
    fun load(): Boolean {
        val text = readShared() ?: return false
        return parse(text)
    }

    private fun parse(text: String): Boolean {
        return try {
            val json = JSONObject(text)
            synchronized(lock) {
                inMemory.clear()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key.startsWith(ENABLED_PREFIX)) {
                        inMemory[key] = json.getBoolean(key)
                    }
                }
            }
            true
        } catch (t: Throwable) {
            QLog.w("Settings", "failed to parse shared settings", t)
            false
        }
    }

    private fun readShared(): String? {
        val file = sharedFile ?: return null
        return try {
            when {
                file.exists() && file.canRead() -> file.readText()
                file.exists() -> suRead(file)
                else -> null
            }
        } catch (t: Throwable) {
            QLog.w("Settings", "failed to read shared settings", t)
            null
        }
    }

    /**
     * Persist a switch. Updates the in-memory copy, the local UI cache and
     * the shared file (best effort — a failure here only means the change
     * waits for the next successful sync).
     */
    @Synchronized
    fun setEnabled(featureId: String, value: Boolean) {
        val key = ENABLED_PREFIX + featureId
        synchronized(lock) {
            inMemory[key] = value
        }
        localCache?.setEnabled(featureId, value)
        writeShared()
    }

    @Synchronized
    fun isEnabled(featureId: String, defaultEnabled: Boolean): Boolean {
        val key = ENABLED_PREFIX + featureId
        synchronized(lock) {
            inMemory[key]?.let { return it }
        }
        return localCache?.isEnabled(featureId, defaultEnabled) ?: defaultEnabled
    }

    @Synchronized
    private fun writeShared() {
        val file = sharedFile ?: return
        try {
            val json = JSONObject()
            synchronized(lock) {
                inMemory.forEach { (k, v) -> json.put(k, v) }
            }
            val text = json.toString()
            if (canWriteDirectly(file)) {
                file.parentFile?.mkdirs()
                file.writeText(text)
            } else {
                suWrite(file, text)
            }
            localCache?.all?.forEach { (k, v) ->
                if (k is String && k.startsWith(ENABLED_PREFIX) && v is Boolean) {
                    synchronized(lock) { inMemory[k] = v }
                }
            }
        } catch (t: Throwable) {
            QLog.w("Settings", "failed to write shared settings", t)
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
        // shell-escape: the path we control contains no single quotes
        val cmd = "mkdir -p '${file.parentFile?.absolutePath}' && printf %s '$text' > '${file.absolutePath}'"
        execSu(arrayOf("sh", "-c", cmd))
    }

    private var lastStdout: String = ""

    /** Runs `su -c <args joined>`; returns the exit code or null if su is unavailable. */
    private fun execSu(args: Array<String>): Int? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "0", "-c", args.joinToString(" ") { shellQuote(it) }))
            lastStdout = process.inputStream.bufferedReader().use { it.readText() }
            process.errorStream.close()
            val code = process.waitFor()
            if (code == 127) {
                // su binary not found
                null
            } else {
                code
            }
        } catch (t: Throwable) {
            QLog.d("Settings", "su bridge unavailable", t)
            null
        }
    }

    private fun shellQuote(value: String): String =
        if (value.isEmpty()) "''" else value.replace("'", "'\\''")

    companion object {
        const val ENABLED_PREFIX = "feature.enabled."
    }
}
