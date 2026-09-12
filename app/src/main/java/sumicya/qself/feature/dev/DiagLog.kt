/* SPDX-License-Identifier: GPL-3.0-or-later */
package sumicya.qself.feature.dev

import io.github.qauxv.util.hostInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志 — logcat-independent sink for on-device diagnostics.
 *
 * Plain text lines in the host files dir (files/qself_diag.log), reset at
 * 256 KiB (diagnostics are ephemeral, unlike the group log). Collect with:
 * `su -c "cat /data/data/com.tencent.mobileqq/files/qself_diag.log"`.
 * Never throws into host code paths.
 */
object DiagLog {

    private const val MAX_BYTES = 256 * 1024

    private val TS = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** Append one diagnostics line; truncates the file when the cap is hit. */
    @JvmStatic
    fun w(line: String) {
        try {
            val f = File(hostInfo.application.filesDir, "qself_diag.log")
            if (f.length() > MAX_BYTES) {
                f.writeText("")
            }
            f.appendText("${TS.format(Date())} ${line.take(1500)}\n")
        } catch (_: Throwable) {
            // diagnostics must never throw into host paths
        }
    }
}
