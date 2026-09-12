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
package me.ketal.hook

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Defensive SimpleDateFormat construction for user-stored patterns.
 *
 * A pattern persisted by an older build (or hand-edited) can contain letters
 * the current ICU rejects - observed on device as
 * `IllegalArgumentException: Illegal pattern character 'A'` thrown from
 * ChatItemShowQQUin.formatTailMessageNt on every bubble, which killed the
 * whole ID-and-time tail. Parsing must degrade to the default format instead
 * of taking the decorator down with it.
 */
object TailTimeFormat {

    /** Pure check: would [SimpleDateFormat] reject this pattern? */
    @JvmStatic
    fun isBad(pattern: String): Boolean {
        return try {
            SimpleDateFormat(pattern, Locale.ROOT)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
    }

    /** [SimpleDateFormat] for [pattern], or for [fallback] if [pattern] is rejected. */
    @JvmStatic
    fun safe(pattern: String, fallback: String): SimpleDateFormat {
        return try {
            SimpleDateFormat(pattern, Locale.ROOT)
        } catch (e: IllegalArgumentException) {
            SimpleDateFormat(fallback, Locale.ROOT)
        }
    }
}
