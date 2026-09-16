/* SPDX-License-Identifier: GPL-3.0-or-later */
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
