/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.log

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny logger: mirrors to logcat (tag "Qself/<tag>") and keeps a bounded
 * in-memory ring so the settings UI can dump diagnostics without any
 * disk I/O on the hot path.
 */
object QLog {

    private const val ROOT_TAG = "Qself"
    private const val RING_SIZE = 1024

    private val ring = ArrayDeque<String>(RING_SIZE)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, msg: String) = write(Log.DEBUG, tag, msg, null)
    fun d(tag: String, msg: String, t: Throwable?) = write(Log.DEBUG, tag, msg, t)
    fun i(tag: String, msg: String) = write(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String) = write(Log.WARN, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable?) = write(Log.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write(Log.ERROR, tag, msg, t)

    fun error(t: Throwable) =
        e(ROOT_TAG, "${t.javaClass.simpleName}: ${t.message}", t)

    private fun write(level: Int, tag: String, msg: String, t: Throwable?) {
        val fullTag = "$ROOT_TAG/$tag"
        if (t == null) {
            when (level) {
                Log.DEBUG -> Log.d(fullTag, msg)
                Log.INFO -> Log.i(fullTag, msg)
                Log.WARN -> Log.w(fullTag, msg)
                else -> Log.e(fullTag, msg)
            }
        } else {
            when (level) {
                Log.DEBUG -> Log.d(fullTag, msg, t)
                Log.INFO -> Log.i(fullTag, msg, t)
                Log.WARN -> Log.w(fullTag, msg, t)
                else -> Log.e(fullTag, msg, t)
            }
        }
        synchronized(lock) {
            if (ring.size >= RING_SIZE) {
                ring.removeFirst()
            }
            ring.addLast(
                timeFormat.format(Date()) + " " +
                    if (t == null) msg else msg + " " + Log.getStackTraceString(t)
            )
        }
    }

    /** Snapshot of the ring buffer, oldest first, at most [limit] lines. */
    fun snapshot(limit: Int = 512): String {
        synchronized(lock) {
            val list = ring.toList()
            return if (list.size > limit) list.subList(list.size - limit, list.size) else list
        }.joinToString("\n")
    }

    fun dumpToFile(file: File) {
        synchronized(lock) {
            try {
                file.writeText(ring.joinToString("\n") + "\n")
            } catch (t: Throwable) {
                // diagnostics only; never crash over it
            }
        }
    }
}
