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

    @Test fun reinstallAfterFailureMustNotReadTheStaleVerdict() {
        // 真实事故形态：某个开关装失败被判 false 并进了缓存；热重载/重启后重装成功，
        // 若不清缓存，on() 仍答 false，用户看到开关是开的、功能却不生效。
        val prefs = CountingPrefs(mutableMapOf("连发合并" to true))
        withPrefs(prefs) {
            failed += "连发合并"
            assertFalse(on("连发合并"))

            // 重装成功：failed 里删掉它，并（像 Qself.install 那样）作废缓存
            failed -= "连发合并"
            invalidateSwitchCache()
            assertTrue("重装成功之后必须读到开", on("连发合并"))
        }
    }

    @Test fun staleVerdictSurvivesWithoutInvalidateProvingTheTestHasTeeth() {
        // 反证：不作废时确实会读到旧结论。这条不是要求的行为，是钉住「为什么必须作废」。
        val prefs = CountingPrefs(mutableMapOf("连发合并" to true))
        withPrefs(prefs) {
            failed += "连发合并"
            assertFalse(on("连发合并"))
            failed -= "连发合并"
            assertFalse("不作废缓存就一定读到旧值——这就是漏调 invalidate 的后果", on("连发合并"))
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

    @Test fun failedVerdictIsCachedSoReinstallNeedsInvalidate() {
        // 把「installOne 写 failed」和「on() 读缓存」串起来：这正是漏调 invalidate 会出事的那条链。
        val prefs = CountingPrefs(mutableMapOf("测试装失败" to true))
        withPrefs(prefs) {
            installOne(Feature("测试装失败", true, "夹具", { error("故意的") }))
            assertFalse("装失败之后开关必须答 false", on("测试装失败"))

            // 重装成功（失败项从 failed 里消失），但不作废缓存
            failed -= "测试装失败"; failures -= "测试装失败"
            assertFalse("不作废就还是 false——这条钉住「为什么 install 里必须调 invalidate」", on("测试装失败"))

            invalidateSwitchCache()
            assertTrue("作废之后才读到新结论", on("测试装失败"))
        }
    }

    @Test fun aNewInstallRoundDropsTheStaleCache() {
        // 直接打 beginInstallRound：它是「重装之后开关读到旧值」唯一出过事的那段，
        // 而且不装任何钩子，能在 JVM 上跑。删掉里面那行 invalidateSwitchCache()，这条就红。
        val values = mutableMapOf<String, Boolean>("玻璃底栏" to true)
        val prefs = CountingPrefs(values)
        withPrefs(prefs) {
            assertTrue(on("玻璃底栏"))                    // 进缓存：true
            values["玻璃底栏"] = false                     // 用户在设置页关掉
            assertTrue("缓存还在，没到新一轮安装时读的是旧值", on("玻璃底栏"))

            beginInstallRound()                            // 新一轮：清状态 + 作废缓存
            assertFalse("新一轮安装之后必须读到新值", on("玻璃底栏"))
        }
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

    @Test fun replaceLastSwapsOnlyTheLastSlot() {
        val args = listOf<Any?>("tag", 7, "OLD-NOTIFICATION")
        val out = replaceLast(args, "NEW")
        assertTrue("最后一格被换掉", out[2] == "NEW")
        assertTrue("前面几格原样", out[0] == "tag" && out[1] == 7)
        assertTrue("长度不变", out.size == 3)
        assertTrue("不改原表（chain.args 是框架给的只读表）", args[2] == "OLD-NOTIFICATION")
    }

    @Test fun replaceLastHandlesSingleArgument() {
        val out = replaceLast(listOf<Any?>("OLD"), "NEW")
        assertTrue("单参数重载也要能换", out.size == 1 && out[0] == "NEW")
    }

    @Test fun replaceLastRejectsEmptyArgsInsteadOfThrowingIndexError() {
        // 报告里宁可看见一条明确的断言失败，也不要一个 lastIndex = -1 的数组越界——
        // 后者会被 hook 的异常兜底吞成「钩子前置异常」，看不出是参数表空了。
        val threw = runCatching { replaceLast(emptyList(), "NEW") }.isFailure
        assertTrue("空参数表应当明确失败", threw)
    }

    // ── Quiet.kt 的门方法分类 ────────────────────────────────────────────────

    /**
     * 真方法替身，从仓里已有的类取：
     *   boolean 的用 LogMerge.isLegacyExport（签名 String -> Boolean）
     *   String  的用 LogMerge.timestampOf（签名 String -> String）
     * 都比 Proxy 造 Method 可靠——Method 是 final 类，代理出来的 getName() 不可控。
     */
    private val boolMethod: java.lang.reflect.Method =
        Class.forName("sumicya.qself.LogMergeKt").getDeclaredMethod("isLegacyExport", String::class.java)

    private val stringMethod: java.lang.reflect.Method =
        Class.forName("sumicya.qself.LogMergeKt").getDeclaredMethod("timestampOf", String::class.java)

    @Test fun gateClassificationSeparatesMissingClassFromMissingMethod() {
        val good = boolMethod
        val notBool = stringMethod

        val resolution = classifyGates(listOf(
            // 情形 ①：类不存在 → entries 空表
            "com.tencent.Missing" to emptyList(),
            // 情形 ②：类在、方法不存在
            "com.tencent.HasClass" to listOf<GateMethods>("goneMethod" to emptyList()),
            // 情形 ③：类在、方法在但不是 boolean
            "com.tencent.WrongSig" to listOf<GateMethods>("isShowRedPoint" to listOf(notBool)),
            // 正常命中
            "com.tencent.Ok" to listOf<GateMethods>("isShowRedPoint" to listOf(good)),
        ))

        assertTrue("缺类单列", resolution.missingClasses == listOf("com.tencent.Missing"))
        assertTrue("方法缺失与签名不符共 2 条，实得 ${resolution.missingMethods.size}",
            resolution.missingMethods.size == 2)
        assertTrue("方法缺失要说是「类在，方法不存在」",
            resolution.missingMethods.any { it.contains("goneMethod") && it.contains("类在，方法不存在") })
        assertTrue("签名不符要报出实际返回类型，实得 ${resolution.missingMethods}",
            resolution.missingMethods.any { it.contains("isShowRedPoint") && it.contains("不是 boolean") })
        assertTrue("只收 boolean 的那一个", resolution.methods.size == 1)
    }

    @Test fun gateClassificationTreatsEverythingMissingAsEmpty() {
        val resolution = classifyGates(listOf(
            "a.A" to emptyList(),
            "b.B" to listOf<GateMethods>("x" to emptyList()),
        ))
        assertTrue("一个都没解析到 → methods 为空，gates 会因此抛", resolution.methods.isEmpty())
        assertTrue("两个类都记进缺失", resolution.missingClasses.size == 1)
    }

    @Test fun gateClassificationKeepsEveryDistinctBooleanMethod() {
        // 一个类里两个不同名字的 boolean 方法，两个都要装。
        // 夹具用 Chat.kt 里两个都是 Boolean 返回的方法，别拿本类的 @Test 方法——
        // 那是 Unit，会静默落进 missingMethods。
        val chat = Class.forName("sumicya.qself.RunKey")
        val b = Class.forName("sumicya.qself.LogMergeKt").getDeclaredMethod("isLegacyExport", String::class.java)
        val c = chat.declaredMethods.first { it.returnType == java.lang.Boolean.TYPE && it.name == "equals" }
        val resolution = classifyGates(listOf("c.C" to listOf<GateMethods>("isX" to listOf(b), "isY" to listOf(c))))
        assertTrue("两个不同的 boolean 方法都要留下，实得 ${resolution.methods.size}（缺失 ${resolution.missingMethods}）",
            resolution.methods.size == 2)
    }

    // ── Glass.kt 的两个纯函数 ────────────────────────────────────────────────

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
}
