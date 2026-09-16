/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.dev

import io.github.qauxv.config.ConfigManager
import io.github.qauxv.util.hostInfo
import sumicya.qself.diagnostics.ReportMetadata
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 诊断文件 — logcat-independent sink for on-device diagnostics.
 *
 * Plain text lines in the host files dir (files/qself_diag.log), reset at
 * 256 KiB (diagnostics are ephemeral, unlike the group log). Collect with:
 * `su -c "cat /data/data/com.tencent.mobileqq/files/qself_diag.log"`.
 *
 * Governance, kept in step with the journal policy instead of growing a
 * second set of local rules: one master switch (`qself.diag_log.enabled`),
 * one redaction rule shared with the report path, one immutable thread-safe
 * timestamp formatter (the previous shared SimpleDateFormat was not safe for
 * the hook threads that write here), one bounded line length, and a single
 * lock around appends so concurrent writers cannot interleave a line.
 *
 * Never throws into host code paths.
 */
object DiagLog {

    private const val MAX_BYTES = 256 * 1024
    private const val MAX_LINE = 1500
    private const val KEY_ENABLED = "qself.diag_log.enabled"

    /** Immutable and thread-safe, unlike SimpleDateFormat. */
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    /** Last line written, so a repeated identical dump cannot crowd out everything else. */
    private var lastLine: String? = null
    private var suppressed = 0

    /** Master switch: off stops every write, including the developer dumps. */
    @JvmStatic
    var enabled: Boolean
        get() = runCatching { ConfigManager.getDefaultConfig().getBooleanOrDefault(KEY_ENABLED, true) }
            .getOrDefault(true)
        set(value) {
            runCatching { ConfigManager.getDefaultConfig().putBoolean(KEY_ENABLED, value) }
        }

    /**
     * Append one diagnostics line; truncates the file when the cap is hit.
     *
     * A line identical to the previous one is counted instead of appended: the 9.2.10 host
     * re-runs the same DexKit dump on every start, and on a real device those repeats were the
     * bulk of a 96 KiB file, hiding everything else. The next different line reports how many
     * were folded, so nothing is silently dropped.
     */
    @JvmStatic
    fun w(line: String?) {
        if (line == null || !enabled) return
        val text = sanitize(line)
        if (text.isEmpty()) return
        try {
            val f = file()
            synchronized(lock) {
                if (text == lastLine) {
                    suppressed++
                    return
                }
                lastLine = text
                if (f.length() > MAX_BYTES) f.writeText("")
                // The note belongs above the new line: it describes the line before it.
                if (suppressed > 0) {
                    f.appendText("（上一行重复 ${suppressed + 1} 次）\n")
                    suppressed = 0
                }
                f.appendText("${LocalDateTime.now().format(TIMESTAMP)} $text\n")
            }
        } catch (_: Throwable) {
            // diagnostics must never throw into host paths
        }
    }

    /** Bounded tail for the in-app view; never the whole 256 KiB at once. */
    @JvmStatic
    @JvmOverloads
    fun read(limit: Int = 12_000): String = try {
        synchronized(lock) {
            val f = file()
            if (!f.isFile) "" else {
                val text = f.readText()
                if (text.length <= limit) text else "…（仅显示末尾 $limit 字符）\n" + text.takeLast(limit)
            }
        }
    } catch (_: Throwable) {
        ""
    }

    @JvmStatic
    fun clear() {
        try {
            synchronized(lock) { file().writeText(""); lastLine = null; suppressed = 0 }
        } catch (_: Throwable) {
            // nothing to report: the file is diagnostics only
        }
    }

    @JvmStatic
    fun bytes(): Long = try { file().length() } catch (_: Throwable) { 0L }

    private fun file() = File(hostInfo.application.filesDir, "qself_diag.log")

    /**
     * One rule for every caller: a line cannot forge another line, and numeric
     * identifiers are redacted exactly like the report path does.
     */
    private fun sanitize(line: String): String {
        val flat = StringBuilder(line.length)
        for (c in line) {
            when {
                c == '\n' || c == '\r' -> flat.append(' ')
                c == '\t' || c >= ' ' -> flat.append(c)
                else -> Unit
            }
        }
        return ReportMetadata.redact(flat.substring(0, minOf(flat.length, MAX_LINE)))
    }
}
