/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// Explicit import: inside a Kotlin DSL script bare `java` is Gradle's java
// extension, not the JDK package.
import java.io.File
import java.util.Properties
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
        // CI appends the commit so an installed build is identifiable from the
        // diagnostics card ("did I actually install the fix?" cost a round trip).
        versionName = "2.0.0-alpha01" + (System.getenv("GITHUB_SHA")?.take(7)?.let { "-$it" } ?: "")
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
// The APK is the deliverable and the libxposed contract lives *inside* it, so
// "installs but never loads" is not something the compiler can catch. This task
// inspects the packaged APK after assembleDebug:
//
//   * the zip entries the framework looks for (META-INF/xposed/*, both ABIs);
//   * the entry class from META-INF/xposed/java_init.list, searched in *every*
//     dex image - the naive `classes.dex`-only search reports a false negative
//     as soon as the build produces more than one dex (this project ships ~19);
//   * that no AndroidX/Material class is bundled: the machine-checkable half of
//     the "framework-only UI" claim;
//   * that the compileOnly framework stubs are not bundled (they would shadow
//     the framework's real implementation at runtime).
//
// Class definitions come from the SDK's dexdump; without one the task falls
// back to scanning the raw dex bytes for the entry descriptor and reports the
// strict checks as skipped.
//
// A violation fails the build; on success the report goes to the build log.
// ---------------------------------------------------------------------------

/** sdk.dir from local.properties, or the usual environment variables. */
val sdkDirectory: File? = run {
    val props = Properties()
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) localProps.inputStream().use { props.load(it) }
    val candidate = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: props.getProperty("sdk.dir")
    candidate?.let { File(it) }?.takeIf { it.isDirectory }
}

/** Newest build-tools dexdump, if the SDK has one. */
val dexdumpBinary: File? = sdkDirectory
    ?.resolve("build-tools")
    ?.listFiles { f: File -> f.isDirectory }
    ?.sortedBy { it.name }
    ?.asReversed()
    ?.map { File(it, "dexdump") }
    ?.firstOrNull { it.canExecute() }

/** "a.b.C" -> "La/b/C;". */
fun descriptorFor(fqcn: String): String = "L" + fqcn.replace('.', '/') + ";"

/** Class descriptors *defined* in [dex], via dexdump. */
fun dexdumpClasses(dexdump: File, dex: File, outDir: File): List<String> {
    val text = File(outDir, dex.name + ".dexdump.txt")
    val process = ProcessBuilder(dexdump.absolutePath, dex.absolutePath)
        .redirectErrorStream(true)
        .redirectOutput(text)
        .start()
    process.waitFor()
    if (!text.exists()) return emptyList()
    return text.readLines().mapNotNull { line ->
        if (!line.contains("Class descriptor")) {
            null
        } else {
            line.substringAfter("'", "").substringBefore("'").takeIf { it.startsWith("L") }
        }
    }
}

val apkDebugDir = layout.buildDirectory.dir("outputs/apk/debug")
val verifyWorkDir = layout.buildDirectory.dir("apk-verify")

val verifyModuleApk = tasks.register("verifyModuleApk") {
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
        val outDir = verifyWorkDir.get().asFile.apply { mkdirs() }

        val problems = ArrayList<String>()
        var entryClass = ""
        var entryIn: String? = null
        var rawHitIn: String? = null
        var dexSizes = ""
        var uiLibs = 0
        var stubs = 0
        var ourClasses = emptyList<String>()
        var dexdumpUsed = false
        var packedLibsSummary = ""

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

            // The module is injected into the host process: shipping our own
            // libc++_shared.so next to the host's copy is a classic instant
            // crash (same soname, different revision). The engine is built
            // with a static STL on purpose - keep it that way.
            val packedLibs = zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .sorted()
                .toList()
            for (lib in packedLibs) {
                if (lib.contains("libc++_shared")) {
                    problems.add("shared STL bundled ($lib) - the host ships its own copy")
                }
            }
            packedLibsSummary = packedLibs.joinToString(", ")

            entryClass = zip.getEntry("META-INF/xposed/java_init.list")
                ?.let { zip.getInputStream(it).bufferedReader().readText() }
                ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                .orEmpty()
            if (entryClass.isEmpty()) {
                problems.add("java_init.list declares no entry class")
            }
            val descriptor = descriptorFor(entryClass)

            val dexes = zip.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
                .sorted()
                .toList()
            if (dexes.isEmpty()) problems.add("no dex image in the APK")

            val sizes = ArrayList<String>()
            val defined = ArrayList<String>()
            for (dex in dexes) {
                val bytes = zip.getInputStream(zip.getEntry(dex)).readBytes()
                sizes.add("$dex:${bytes.size}")
                if (String(bytes, Charsets.ISO_8859_1).contains(descriptor)) rawHitIn = dex
                if (dexdumpBinary != null) {
                    val file = File(outDir, dex)
                    file.writeBytes(bytes)
                    val names = dexdumpClasses(dexdumpBinary, file, outDir)
                    dexdumpUsed = true
                    if (names.contains(descriptor)) entryIn = dex
                    defined.addAll(names)
                }
            }
            dexSizes = sizes.joinToString(" ")

            if (dexdumpBinary == null) {
                if (rawHitIn == null) problems.add("entry class $entryClass not found in any dex (raw scan)")
            } else {
                if (entryIn == null) {
                    problems.add("entry class $entryClass is not defined in any dex (${dexes.joinToString()})")
                }
                ourClasses = defined.filter { it.startsWith("Lsumicya/qself/") }.sorted()
                uiLibs = defined.count {
                    it.startsWith("Landroidx/") || it.startsWith("Lcom/google/android/material/")
                }
                if (uiLibs != 0) problems.add("$uiLibs AndroidX/Material classes are bundled")
                stubs = defined.count {
                    it.startsWith("Lio/github/libxposed/") || it.startsWith("Lde/robv/android/xposed/")
                }
                if (stubs != 0) problems.add("$stubs framework API stub classes are bundled")
            }
        }

        val report = StringBuilder()
        report.append("verifyModuleApk: ${apk.name} (${apk.length()} bytes)\n")
        for (problem in problems) report.append("  PROBLEM: $problem\n")
        val where = entryIn ?: rawHitIn?.let { "raw hit in $it" } ?: "NOT FOUND"
        report.append("  entry class: $entryClass -> $where\n")
        report.append("  dexdump: ${if (dexdumpUsed) dexdumpBinary?.absolutePath else "unavailable (class-definition checks skipped)"}\n")
        report.append("  dexes: $dexSizes\n")
        report.append("  packed libs: $packedLibsSummary\n")
        report.append("  androidx/material classes: $uiLibs, framework stubs: $stubs\n")
        if (entryIn == null) {
            report.append("  qself classes defined: ${ourClasses.size}\n")
            for (name in ourClasses.take(24)) report.append("    $name\n")
        }

        if (problems.isEmpty()) {
            logger.lifecycle(report.toString().trimEnd())
        } else {
            throw GradleException(report.toString().trimEnd())
        }
    }
}

// matching + configureEach: AGP 9 registers its assemble* tasks lazily, so
// tasks.named("assembleDebug") would not find anything at configuration time.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(verifyModuleApk)
}
