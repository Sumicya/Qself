/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */
package sumicya.qself.feature.chat

import io.github.qauxv.util.hostInfo
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 群日志存储 — my-feature-set 06 B 类（灰字扩展功能化）。
 *
 * Bounded rolling JSONL in the host files dir: one line per captured
 * gray-tip event, rotated at 512 KiB (previous generation kept as .old).
 * The recorder is GrayTipCapture; the viewer is its settings entry
 * (click the item to read the tail). Never throws into host code paths.
 */
object GroupLogStore {

    private const val MAX_BYTES = 512 * 1024

    private val LOCK = Any()

    private fun file(): File = File(hostInfo.application.filesDir, "qself_group_log.jsonl")

    /** Append one event; data is capped to keep lines bounded. */
    @JvmStatic
    fun append(source: String, data: String) {
        synchronized(LOCK) {
            try {
                val f = file()
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, f.name + ".old")
                    old.delete()
                    f.renameTo(old)
                }
                val row = JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("s", source)
                    .put("d", data.take(2000))
                f.appendText(row.toString() + "\n")
            } catch (_: Throwable) {
                // logging must never throw into host paths
            }
        }
    }

    /** Newest-first rendered tail of at most [n] entries. */
    @JvmStatic
    fun readTail(n: Int): List<String> {
        synchronized(LOCK) {
            return try {
                val all = file().readLines()
                val window = if (all.size > n) all.takeLast(n) else all
                val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                window.mapNotNull { line ->
                    runCatching {
                        val o = JSONObject(line)
                        "${fmt.format(Date(o.getLong("t")))} [${o.getString("s")}] ${o.getString("d")}"
                    }.getOrNull()
                }.reversed()
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    /** Wipe the store (both generations). */
    @JvmStatic
    fun clear() {
        synchronized(LOCK) {
            try {
                file().delete()
                File(file().parentFile, file().name + ".old").delete()
            } catch (_: Throwable) {
            }
        }
    }
}
