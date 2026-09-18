/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.feature

/** UI grouping for features. */
enum class FeatureCategory(val title: String) {
    MESSAGE("消息"),
    GROUP("群组"),
    FRIEND("好友"),
    QWALLET("钱包"),
    QZONE("空间"),
    NOTIFICATION("通知"),
    MEDIA("媒体"),
    UI("界面"),
    MISC("其他");

    companion object {
        fun from(value: String): FeatureCategory =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MISC
    }
}
