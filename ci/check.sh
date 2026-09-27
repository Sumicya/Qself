#!/bin/bash
# 没有 Android SDK / Gradle 的机器上做类型检查 + 跑 ProtoTest（CI 之外的快速回路）。
# 第一次跑会把 kotlinc（npm）、JRE（PyPI jdk4py）、android.jar、libxposed 源码拉到 ~/.cache/qself-tc。
set -e
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
        try { m.invoke(t); n++ } catch (e: java.lang.reflect.InvocationTargetException) { println("FAIL ${m.name}: ${e.cause}"); System.exit(1) }
    }
    println("$n tests passed")
}
EOK
export JAVA_HOME=$T/jdk4py/jdk4py/java-runtime PATH=$T/jdk4py/jdk4py/java-runtime/bin:$PATH
JAVA=$(find "$T/libxposed-api/api/src/main/java" "$T/stubs" -name "*.java")
rm -rf "$T/out" "$T/t"
kotlinc/bin/kotlinc -jvm-target 21 -cp android.jar -d "$T/out" "$R"/app/src/main/kotlin/sumicya/qself/*.kt "$R"/app/src/test/kotlin/sumicya/qself/*.kt kstub/junit.kt $JAVA bc/sumicya/qself/BuildConfig.java 2>&1 | grep -v "^$" || true
echo "compiled: $(ls "$T/out/sumicya/qself" 2>/dev/null | wc -l) classes"
kotlinc/bin/kotlinc -nowarn -jvm-target 21 -cp android.jar -d "$T/t" "$R"/app/src/main/kotlin/sumicya/qself/Chat.kt "$R"/app/src/main/kotlin/sumicya/qself/Hook.kt "$R"/app/src/test/kotlin/sumicya/qself/ProtoTest.kt kstub/junit.kt kstub/run.kt $JAVA 2>&1 | grep -v "^$" || true
java -cp "$T/t:$T/kotlinc/lib/kotlin-stdlib.jar" RunKt
