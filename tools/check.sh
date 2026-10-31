#!/bin/bash
# 没有 Android SDK / Gradle 的机器上做类型检查 + 跑 ProtoTest（CI 之外的快速回路）。
# 第一次跑会把 kotlinc（npm）、JRE（PyPI jdk4py）、android.jar、libxposed 源码拉到 ~/.cache/qself-tc。
set -euo pipefail
T=${QSELF_TC:-$HOME/.cache/qself-tc}
R=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$T" && cd "$T"
[ -x kotlinc/bin/kotlinc ] || { npm pack kotlin-compiler@2.4.20 --silent >/dev/null 2>&1; tar xzf kotlin-compiler-2.4.20.tgz; mv package kotlinc; chmod +x kotlinc/bin/*; }
[ -x jdk4py/jdk4py/java-runtime/bin/java ] || { pip download --quiet --no-deps -d . jdk4py; mkdir -p jdk4py; (cd jdk4py && unzip -qo ../jdk4py-*.whl); chmod +x jdk4py/jdk4py/java-runtime/bin/*; }
[ -f android.jar ] || gh api "repos/Sable/android-platforms/contents/android-36/android.jar" -H "Accept: application/vnd.github.raw" > android.jar
[ -d libxposed-api ] || git clone -q --depth 1 https://github.com/Ahmoze/libxposed-api
mkdir -p stubs/io/github/libxposed/annotation stubs/androidx/annotation bc/sumicya/qself kstub
printf 'package io.github.libxposed.annotation;\npublic @interface InternalApi {}\n' > stubs/io/github/libxposed/annotation/InternalApi.java
printf 'package io.github.libxposed.annotation;\npublic @interface SinceApi { int value(); }\n' > stubs/io/github/libxposed/annotation/SinceApi.java
for a in NonNull Nullable; do printf 'package androidx.annotation;\npublic @interface %s {}\n' $a > stubs/androidx/annotation/$a.java; done
printf 'package sumicya.qself;\npublic final class BuildConfig { public static final String VERSION_NAME = "dev"; }\n' > bc/sumicya/qself/BuildConfig.java
cat > kstub/junit.kt <<'EOK'
package org.junit
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION)
annotation class Test
object Assert {
    @JvmStatic fun assertTrue(b: Boolean) { if (!b) throw AssertionError("expected true") }
    @JvmStatic fun assertFalse(b: Boolean) { if (b) throw AssertionError("expected false") }
    @JvmStatic fun assertTrue(msg: String, b: Boolean) { if (!b) throw AssertionError(msg) }
    @JvmStatic fun assertFalse(msg: String, b: Boolean) { if (b) throw AssertionError(msg) }
    @JvmStatic fun assertEquals(a: Any?, b: Any?) { if (a != b) throw AssertionError("expected $a, got $b") }
    // 替身必须和 JUnit 4.13.2 语义一致：两参 assertEquals(double, double) 在真包里就是一句 fail(...)，
    // 不看数值。这里升成编译错误 —— 本地一写就红，别再把「本地全绿、CI 必红」留到推送后才发现。
    @Deprecated("JUnit 4.13 起两参浮点断言一律失败，用 assertEquals(expected, actual, delta)",
        level = DeprecationLevel.ERROR)
    @JvmStatic fun assertEquals(a: Double, b: Double): Unit =
        throw AssertionError("Use assertEquals(expected, actual, delta) to compare floating-point numbers")
    @JvmStatic fun assertEquals(a: Double, b: Double, delta: Double) {
        if (a.compareTo(b) != 0 && Math.abs(a - b) > delta) throw AssertionError("expected $a ±$delta, got $b")
    }
    @JvmStatic fun assertSame(a: Any?, b: Any?) { if (a !== b) throw AssertionError("expected same") }
    @JvmStatic fun assertArrayEquals(a: ByteArray, b: ByteArray) { if (!a.contentEquals(b)) throw AssertionError("arrays differ: ${a.toList()} vs ${b.toList()}") }
}
EOK
cat > kstub/run.kt <<'EOK'
import org.junit.Test
fun main() {
    val t = sumicya.qself.ProtoTest()
    var n = 0
    for (m in t.javaClass.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.sortedBy { it.name }) {
        try { m.invoke(t); n++ } catch (e: java.lang.reflect.InvocationTargetException) { println("FAIL ${m.name}: ${e.cause}"); e.cause?.printStackTrace(); System.exit(1) }
    }
    println("$n tests passed")
}
EOK
export JAVA_HOME=$T/jdk4py/jdk4py/java-runtime PATH=$T/jdk4py/jdk4py/java-runtime/bin:$PATH
JAVA=$(find "$T/libxposed-api/api/src/main/java" "$T/stubs" -name "*.java")
rm -rf "$T/t"
kotlinc/bin/kotlinc -jvm-target 21 -cp android.jar -d "$T/t" "$R"/app/src/main/kotlin/sumicya/qself/*.kt "$R"/app/src/test/kotlin/sumicya/qself/*.kt kstub/junit.kt kstub/run.kt $JAVA bc/sumicya/qself/BuildConfig.java 2>&1 | tee "$T/compile-all.log"
echo "compiled: $(find "$T/t/sumicya/qself" -type f -name '*.class' | wc -l) classes"
java -cp "$T/t:$T/kotlinc/lib/kotlin-stdlib.jar" RunKt
# CI 的落点核对照样跑这两条 Python 回归：本地漏掉它们，推送后才发现等于拿 CI 当编译器。
cd "$R" && python3 -m unittest discover -s app/src/test/python -p 'test_*.py'
