/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Minimal SharedPreferences-backed settings for the module's own process
 * (the settings UI). The authoritative, cross-process storage is
 * [SettingsBridge]; this class is the UI-side cache of it.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.createSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).commit()
    }

    fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)

    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).commit()
    }

    fun getString(key: String, defaultValue: String?): String? =
        prefs.getString(key, defaultValue)

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    val all: Map<String, *>
        get() = prefs.all

    /** Feature switch with default. */
    fun isEnabled(featureId: String, defaultEnabled: Boolean): Boolean =
        getBoolean(ENABLED_PREFIX + featureId, defaultEnabled)

    fun setEnabled(featureId: String, value: Boolean) {
        putBoolean(ENABLED_PREFIX + featureId, value)
    }

    companion object {
        const val NAME = "qself"
        const val ENABLED_PREFIX = "feature.enabled."
    }
}
