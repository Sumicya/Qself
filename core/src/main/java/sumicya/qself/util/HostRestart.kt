/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.util

import android.os.Process
import sumicya.qself.log.QLog
import java.util.concurrent.TimeUnit

/**
 * Restarting the host, the two ways Qself needs it.
 *
 * A switch can only take effect at host startup: patch application, crash
 * reporting and the upgrade check all run before the first window appears, so
 * by the time a settings screen can be drawn it is already too late for those
 * hooks. What can go away is the *manual* part.
 */
object HostRestart {

    private const val TAG = "HostRestart"

    /**
     * From inside the host process (the in-QQ panel): end it. Same uid, no
     * root, no `su` — the launcher starts it again with the new settings.
     */
    fun killSelf() {
        QLog.i(TAG, "restarting the host process")
        Process.killProcess(Process.myPid())
    }

    /**
     * From the module's own process (the standalone settings screen): the
     * host is someone else's uid, so this needs root. Returns a message for
     * the UI, never throws.
     */
    fun forceStop(packageName: String): String {
        val command = "am force-stop $packageName"
        return try {
            val process = ProcessBuilder("su", "0", "-c", command).start()
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return "重启失败：su 超时"
            }
            val errors = process.errorStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() == 0) {
                QLog.i(TAG, "force-stopped $packageName")
                "已停止 QQ，重新打开即可生效"
            } else {
                QLog.w(TAG, "force-stop failed: $command -> ${process.exitValue()} $errors")
                "重启失败：${if (errors.isEmpty()) "exit=${process.exitValue()}" else errors}"
            }
        } catch (t: Throwable) {
            QLog.w(TAG, "force-stop unavailable", t)
            "重启失败：${t.javaClass.simpleName}（需要 root）"
        }
    }
}
