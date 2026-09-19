/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature

/** UI grouping for features. */
enum class FeatureCategory(val title: String) {
    UI("界面"),
    MISC("其他");

    companion object {
        fun from(value: String): FeatureCategory =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MISC
    }
}
