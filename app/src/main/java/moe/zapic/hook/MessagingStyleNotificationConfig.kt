/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2023 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package moe.zapic.hook

import io.github.qauxv.config.ConfigManager

internal object MessagingStyleNotificationConfig {

    private const val DISABLE_SUB_CHANNEL_CONFIG_KEY = "MessagingStyleNotification.disableConversationSubChannel"
    private const val DISABLE_BUBBLE_CONFIG_KEY = "MessagingStyleNotification.disableBubble"

    var disableConversationSubChannel: Boolean
        get() = getConfig().getBooleanOrDefault(DISABLE_SUB_CHANNEL_CONFIG_KEY, false)
        set(value) {
            getConfig().putBoolean(DISABLE_SUB_CHANNEL_CONFIG_KEY, value)
        }

    var disableBubble: Boolean
        get() = getConfig().getBooleanOrDefault(DISABLE_BUBBLE_CONFIG_KEY, false)
        set(value) {
            getConfig().putBoolean(DISABLE_BUBBLE_CONFIG_KEY, value)
        }

    private fun getConfig(): ConfigManager {
        return ConfigManager.getDefaultConfig()
    }
}
