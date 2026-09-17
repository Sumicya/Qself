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

package io.github.qauxv.util.hotupdate

import io.github.qauxv.config.ConfigManager

object HotUpdateManager {

    const val KEY_HOT_UPDATE_CHANNEL = "KEY_HOT_UPDATE_CHANNEL"

    const val CHANNEL_DISABLED = 0
    const val CHANNEL_STABLE = 1
    const val CHANNEL_BETA = 3
    const val CHANNEL_CANARY = 4

    const val ACTION_DISABLE = 0
    const val ACTION_QUERY = 1
    const val ACTION_AUTO_UPDATE_WITH_NOTIFICATION = 2
    const val ACTION_AUTO_UPDATE_WITHOUT_NOTIFICATION = 3

    var currentChannel: Int
        get() = ConfigManager.getDefaultConfig().getIntOrDefault(KEY_HOT_UPDATE_CHANNEL, CHANNEL_DISABLED)
        set(value) {
            check(value in CHANNEL_DISABLED..CHANNEL_CANARY)
            ConfigManager.getDefaultConfig().putInt(KEY_HOT_UPDATE_CHANNEL, value)
        }

    var currentAction: Int
        get() = ConfigManager.getDefaultConfig().getIntOrDefault("KEY_HOT_UPDATE_ACTION", ACTION_QUERY)
        set(value) {
            check(value in ACTION_DISABLE..ACTION_AUTO_UPDATE_WITHOUT_NOTIFICATION)
            ConfigManager.getDefaultConfig().putInt("KEY_HOT_UPDATE_ACTION", value)
        }

    val isHotUpdateEnabled: Boolean
        get() = currentChannel > 0 && currentAction > 0

}
