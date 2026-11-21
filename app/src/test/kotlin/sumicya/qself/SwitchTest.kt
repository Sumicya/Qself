// SPDX-License-Identifier: GPL-3.0-or-later
package sumicya.qself

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开关读缓存的行为，用会数数的假 SharedPreferences 来验。
 *
 * 为什么要这组测试：on() 在真机上是热路径（会话列表滚动时 dispatchDraw 每帧问一次）。
 * 「加了缓存」这件事本身没有判据——缓存写错会有两种坏法，两种这里都有对应用例：
 *  ① 缓存了却不失效 → 用户关掉开关不生效（比没有缓存更糟）；
 *  ② 失效了但没重新读 → 关掉后又自己打开。
 */
class SwitchTest {

    /** 只实现 on() 用得到的那几个成员；每个 getBoolean 都记账。 */
    private class CountingPrefs(private val values: MutableMap<String, Boolean>) : SharedPreferences {
        var reads = 0
            private set

        override fun getBoolean(key: String, defValue: Boolean): Boolean {
            reads++
            return values[key] ?: defValue
        }

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String, defValue: String?): String? = defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = defValue
        override fun getLong(key: String, defValue: Long): Long = defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("只读替身")
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    /** 把 store() 换成替身：装着 on() 之前，装完必须还原，免得污染同进程里别的用例。 */
    private fun <T> withPrefs(prefs: CountingPrefs, block: () -> T): T {
        val saved = storeOverride
        storeOverride = { prefs }
        invalidateSwitchCache()
        try {
            return block()
        } finally {
            storeOverride = saved
            invalidateSwitchCache()
        }
    }

    @Test fun repeatedReadsHitTheCacheOnlyOnce() {
        val prefs = CountingPrefs(mutableMapOf("玻璃底栏" to false))
        withPrefs(prefs) {
            repeat(50) { assertFalse("开关是关的，读多少次都得是 false", on("玻璃底栏")) }
        }
        assertTrue("50 次 on() 只该问 prefs 一次，实得 ${prefs.reads} 次", prefs.reads == 1)
    }

    @Test fun invalidateMakesTheNextReadGoBackToPrefs() {
        val prefs = CountingPrefs(mutableMapOf("玻璃底栏" to true))
        withPrefs(prefs) {
            assertTrue(on("玻璃底栏"))
            assertTrue("只该问一次，实得 ${prefs.reads}", prefs.reads == 1)

            // 用户在设置页关掉了它
            prefs.getAll() as MutableMap<String, Boolean>
            invalidateSwitchCache()
            assertTrue("清缓存后应当重新读 prefs，此时仍是旧值 true", on("玻璃底栏"))
            assertTrue("失效后应再问一次，累计是 2，实得 ${prefs.reads}", prefs.reads == 2)
        }
    }

    @Test fun valueChangeIsVisibleAfterInvalidate() {
        val values = mutableMapOf<String, Boolean>("玻璃底栏" to true)
        val prefs = CountingPrefs(values)
        withPrefs(prefs) {
            assertTrue(on("玻璃底栏"))
            values["玻璃底栏"] = false
            assertTrue("没失效前读到的是缓存值，这是缓存的代价", on("玻璃底栏"))
            invalidateSwitchCache()
            assertFalse("失效后必须读到新值，否则关开关不生效", on("玻璃底栏"))
        }
    }

    @Test fun unregisteredSwitchFallsBackToDefaultWithoutCachePoisoning() {
        val prefs = CountingPrefs(mutableMapOf())
        withPrefs(prefs) {
            // 清单里没有的名字，按默认开启，且不会因为「读到 null」把别的开关带坏。
            assertTrue(on("不存在的开关名"))
            assertTrue(on("玻璃底栏"))
        }
    }

    @Test fun failedFeatureReadsAsOffEvenIfPrefsSaysOn() {
        val prefs = CountingPrefs(mutableMapOf("连发合并" to true))
        failed += "连发合并"
        try {
            withPrefs(prefs) {
                assertFalse("装失败的功能必须报关，否则面板与行为对不上", on("连发合并"))
            }
        } finally {
            failed -= "连发合并"
        }
    }
}
