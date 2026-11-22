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
        try {
            return block()
        } finally {
            storeOverride = saved
        }
    }

    @Test fun valueChangeTakesEffectOnTheNextRead() {
        // on() 不缓存：改了 prefs 下一次读就得是新值，否则关开关不生效。
        val values = mutableMapOf<String, Boolean>("玻璃底栏" to true)
        withPrefs(CountingPrefs(values)) {
            assertTrue(on("玻璃底栏"))
            values["玻璃底栏"] = false
            assertFalse("改了 prefs 之后下一次读必须变", on("玻璃底栏"))
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

    @Test fun reinstallAfterFailureMustNotReadTheStaleVerdict() {
        // 真实事故形态：装失败被判 false；热重载后重装成功，若失败判定没清掉，
        // on() 仍答 false，用户看到开关是开的、功能却不生效。
        val prefs = CountingPrefs(mutableMapOf("连发合并" to true))
        withPrefs(prefs) {
            failed += "连发合并"
            assertFalse(on("连发合并"))

            // 重装成功：failed 里删掉它
            failed -= "连发合并"
            assertTrue("重装成功之后必须读到开", on("连发合并"))
        }
    }

    @Test fun installOneRecordsFailureAndSuccessWithoutDuplicates() {
        // 直接走产品路径 installOne，不用任何替身。
        val boom = Feature("测试装失败", true, "夹具", { error("故意的") })
        val ok = Feature("测试装成功", true, "夹具", { })

        val before = failed.size
        assertTrue("失败的那项要写进 failed", installOne(boom).contains("✗测试装失败"))
        assertTrue("失败的项必须在 failed 里", "测试装失败" in failed)
        assertTrue("失败原因要留档", failures["测试装失败"]?.contains("故意的") == true)

        assertTrue("成功的那项报告里是 ✓", installOne(ok).contains("✓测试装成功"))
        assertTrue("成功的不该进 failed", "测试装成功" !in failed)
        assertTrue("failed 只多了一条", failed.size == before + 1)

        failed -= "测试装失败"; failures -= "测试装失败"
    }




    @Test fun aNewInstallRoundClearsPreviousFailureVerdicts() {
        val prefs = CountingPrefs(mutableMapOf("连发合并" to true))
        withPrefs(prefs) {
            failed += "连发合并"
            failures["连发合并"] = "上一轮的失败"
            assertFalse(on("连发合并"))

            beginInstallRound()
            assertTrue("新一轮把上一轮的失败判定清掉了，就该按 prefs 答", on("连发合并"))
            assertTrue("失败原因也一并清掉", "连发合并" !in failures)
        }
    }

    // ── Notify.kt 重写时抽出来的纯函数 ────────────────────────────────────────

    @Test fun washKeepsAlphaAndOnlyMixesRgb() {
        // 注释写的是「保留 c 自己的透明度」——这条测试就是那句话的判据。
        for (alpha in listOf(0x00, 0x40, 0x80, 0xC8, 0xFF)) {
            val c = (alpha shl 24) or 0xFFFFFF
            val out = wash(c, 0xFF448AFF.toInt())
            assertTrue("alpha ${alpha} 应当原样保留，实得 ${(out ushr 24) and 0xFF}",
                (out ushr 24) and 0xFF == alpha)
        }
    }

    @Test fun washReturnsInputUnchangedWhenMonetIsOff() {
        val c = 0xC8F5F5F8.toInt()
        assertTrue("accent 为 null（壁纸取色关着）时原样返回", wash(c, null) == c)
    }

    @Test fun washMixesExactlyTwentyTwoPercent() {
        // 精确值断言，不是「明显变暗」那种宽断言。
        // 0.22 改成 0.5 时这条会红；上一版写的是 `channel < 240`，0.5 也过，等于没牙。
        // 255 + (0 - 255) * 0.22 = 255 - 56.1 → toInt 截断成 199（Kotlin 的 toInt 向零取整）
        val out = wash(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        for (shift in listOf(16, 8, 0)) {
            val channel = (out ushr shift) and 0xFF
            assertTrue("通道应当正好是 199（0.22 混合），实得 ${channel}", channel == 199)
        }
        assertTrue("alpha 不受影响", (out ushr 24) and 0xFF == 0xFF)
    }

    @Test fun washIsIdempotentInTheSenseThatMixingTheSameColorKeepsIt() {
        // wash(c, c 的 rgb) 应当等于 c 的 rgb —— 混合目标就是自己时不该漂移。
        val c = 0x80ABCDEF.toInt()
        val out = wash(c, c)
        assertTrue("把自己当 accent 混进去，rgb 应当不变（实得 ${out.toUInt().toString(16)}）",
            (out and 0xFFFFFF) == (c and 0xFFFFFF))
        assertTrue("alpha 仍然保留", (out ushr 24) and 0xFF == 0x80)
    }

    // ── Perf.kt 的 CostMeter ─────────────────────────────────────────────────

    @Test fun costMeterCountsEveryCallAndAveragesPerCall() {
        CostMeter.reset()
        // add 的第二个参数是「开始时刻」，它算的是 nanoTime - start，所以传「现在」得到约 0。
        // 想要确定的耗时，就传一个过去的时刻。
        val past = System.nanoTime() - 2_000_000L   // 2ms 前
        repeat(4) { CostMeter.add(COST_GLASS, past) }
        val line = CostMeter.line()
        assertTrue("应当报出这一环，实得「$line」", line.contains("玻璃合成"))
        assertTrue("次数应当是 4，实得「$line」", line.contains("4 次"))
        CostMeter.reset()
        assertTrue("reset 之后不该再有这一环", !CostMeter.line().contains("玻璃合成"))
    }

    @Test fun costMeterIgnoresOutOfRangeSlots() {
        CostMeter.reset()
        // 越界的槽应当被安静忽略，而不是抛 ArrayIndexOutOfBounds——那是绘制路径里的崩溃。
        CostMeter.add(-1, System.nanoTime())
        CostMeter.add(999, System.nanoTime())
        assertTrue("越界槽不该产生输出", CostMeter.line().isEmpty())
    }

    @Test fun costMeterTakeResetsAndReturnsTheSameText() {
        CostMeter.reset()
        CostMeter.add(COST_LIST, System.nanoTime() - 1_000_000L)
        val taken = CostMeter.take()
        assertTrue("take 应当返回刚才那一段的归因", taken.contains("消息几何"))
        assertTrue("take 之后计数被清零", CostMeter.line().isEmpty())
    }

    @Test fun costMeterCountsAreNotLostWhenTwoThreadsAdd() {
        /*
         * 诚实说明：这条测的是「计数不丢」，**不是**「没有撕裂读」。
         *
         * 撕裂读（一个线程读到另一个线程写了一半的 long）在 64 位 JVM 上复现不出来——
         * 我把 AtomicLong 改回 LongArray 连跑三次，101 条照样全绿。真机 32 位进程上
         * `nanos[slot] += x` 才是三步非原子操作。
         *
         * 所以这一条的判据只能是：多线程并发 add 之后，总次数等于调用次数。
         * 原实现用 LongArray 时，`counts[slot]++` 也会丢更新——那一条是真测得到的。
         */
        CostMeter.reset()
        val threads = 4
        val perThread = 5_000
        val pool = (1..threads).map {
            Thread { repeat(perThread) { CostMeter.add(COST_HEADER, System.nanoTime()) } }
        }
        pool.forEach { it.start() }
        pool.forEach { it.join(10_000) }

        val line = CostMeter.line()
        val expected = threads * perThread
        assertTrue("总次数应当是 $expected，实得「$line」", line.contains("$expected 次"))
    }
}
