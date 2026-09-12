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
