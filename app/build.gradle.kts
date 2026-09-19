/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// Explicit import: inside a Kotlin DSL script bare `java` is Gradle's java
// extension, not the JDK package.
import java.io.File
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sumicya.qself"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "sumicya.qself"
        minSdk = 26
        targetSdk = 36
        versionCode = 20001
        versionName = "2.0.0-alpha01"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Kotlin is compiled by AGP's built-in Kotlin support
    // (android.builtInKotlin=true), so KSP output has to be registered as an
    // AGP Kotlin source directory explicitly.
    sourceSets {
        configureEach {
            kotlin.directories +=
                layout.buildDirectory.dir("generated/ksp/$name/kotlin").get().asFile.absolutePath
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes.addAll(
            arrayOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "kotlin/**",
                "kotlin-tooling-metadata.json",
            ),
        )
    }
}

dependencies {
    implementation(projects.core)
    implementation(projects.native)
    // META-INF/xposed/module.prop (LSPosed 10.x metadata)
    implementation(projects.tools.moduleprop)

    // provided by the framework at runtime; never bundled
    compileOnly(projects.libs.libxposed.api)
    compileOnly(libs.xposed.api)

    // No AndroidX / Material on purpose: the settings screen is framework UI
    // (Activity + ListView + platform theme).

    // feature registry processor (@QselfFeature -> QselfFeatures)
    ksp(projects.tools.ksp)
}

// ---------------------------------------------------------------------------
// Module-contract self-check.
//
// The APK is the deliverable and the libxposed contract is a set of *files*
// inside it, so the things that can silently break ("installs but never
// loads") are not caught by the compiler. This task inspects the packaged
// APK: zip entries, the entry class read from META-INF/xposed/java_init.list,
// and the class definitions of every dex image (parsed straight from the DEX
// header, so it needs no build-tools and sees every dex, not just
// classes.dex). It also proves the "framework-only UI" claim by asserting
// that no AndroidX/Material class is defined.
//
// Runs automatically after assembleDebug; a violation fails the build.
// ---------------------------------------------------------------------------

/** Little-endian u32 at [offset]. */
fun dexU32(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

/** Type descriptors of every class *defined* in a dex image. */
fun dexDefinedClasses(bytes: ByteArray): List<String> {
    val classDefsSize = dexU32(bytes, 0x60)
    val classDefsOff = dexU32(bytes, 0x64)
    val stringIdsSize = dexU32(bytes, 0x38)
    val stringIdsOff = dexU32(bytes, 0x3C)
    val names = ArrayList<String>(classDefsSize)
    for (i in 0 until classDefsSize) {
        val classIdx = dexU32(bytes, classDefsOff + i * 32)
        if (classIdx < 0 || classIdx >= stringIdsSize) continue
        var p = dexU32(bytes, stringIdsOff + classIdx * 4)
        while (bytes[p].toInt() and 0x80 != 0) p++ // uleb128 character count
        p++
        val start = p
        while (bytes[p] != 0.toByte()) p++
        names.add(String(bytes, start, p - start, Charsets.UTF_8))
    }
    return names
}

val apkDebugDir = layout.buildDirectory.dir("outputs/apk/debug")

val verifyModuleApk by tasks.registering {
    group = "verification"
    description = "Checks the packaged APK against the libxposed module contract."
    doLast {
        val apk = apkDebugDir.get().asFile
            .listFiles { f: File -> f.isFile && f.name.endsWith(".apk") }
            ?.maxByOrNull { it.length() }
        if (apk == null) {
            logger.lifecycle("verifyModuleApk: no APK under ${apkDebugDir.get().asFile}, skipped")
            return@doLast
        }

        val problems = ArrayList<String>()
        val summary = StringBuilder("verifyModuleApk: ${apk.name} (${apk.length()} bytes)\n")
        ZipFile(apk).use { zip ->
            for (entry in listOf(
                "META-INF/xposed/module.prop",
                "META-INF/xposed/java_init.list",
                "META-INF/xposed/scope.list",
                "lib/arm64-v8a/libqself_hook.so",
                "lib/armeabi-v7a/libqself_hook.so",
            )) {
                if (zip.getEntry(entry) == null) problems.add("missing $entry")
            }

            val entryClass = zip.getEntry("META-INF/xposed/java_init.list")
                ?.let { zip.getInputStream(it).bufferedReader().readText() }
                ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                .orEmpty()
            if (entryClass.isEmpty()) {
                problems.add("META-INF/xposed/java_init.list has no entry class")
            }
            val descriptor = "L" + entryClass.replace('.', '/') + ";"

            val dexes = zip.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
                .sorted()
                .toList()
            if (dexes.isEmpty()) problems.add("no dex image in the APK")

            var entryIn: String? = null
            val defined = ArrayList<String>()
            for (dex in dexes) {
                val names = dexDefinedClasses(zip.getInputStream(zip.getEntry(dex)).readBytes())
                defined.addAll(names)
                if (names.contains(descriptor)) entryIn = dex
                summary.append("  $dex: ${names.size} classes defined\n")
            }
            if (entryIn == null) {
                problems.add("entry class $entryClass is not defined in any dex (${dexes.joinToString()})")
            } else {
                summary.append("  entry class $entryClass -> $entryIn\n")
            }

            val uiLibs = defined.count {
                it.startsWith("Landroidx/") || it.startsWith("Lcom/google/android/material/")
            }
            summary.append("  AndroidX/Material classes defined: $uiLibs\n")
            if (uiLibs != 0) problems.add("$uiLibs AndroidX/Material classes are bundled")

            val stubs = defined.count {
                it.startsWith("Lio/github/libxposed/") || it.startsWith("Lde/robv/android/xposed/")
            }
            summary.append("  framework API stub classes defined: $stubs\n")
            if (stubs != 0) problems.add("$stubs framework API stub classes are bundled")
        }

        for (problem in problems) summary.append("  PROBLEM: $problem\n")
        throw GradleException(summary.toString().trimEnd())
    }
}

tasks.named("assembleDebug") { finalizedBy(verifyModuleApk) }
