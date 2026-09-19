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
// The APK is the deliverable and the libxposed contract lives *inside* it, so
// "installs but never loads" is not something the compiler can catch. This task
// inspects the packaged APK after assembleDebug:
//
//   * the zip entries the framework looks for (META-INF/xposed/*, both ABIs);
//   * the entry class from META-INF/xposed/java_init.list, searched in *every*
//     dex image (the naive `classes.dex`-only search reports a false negative
//     as soon as the build produces more than one dex);
//   * that no AndroidX/Material class is bundled - the machine-checkable half
//     of the "framework-only UI" claim;
//   * that the compileOnly framework stubs are not bundled, which would shadow
//     the framework's real implementation at runtime.
//
// Class definitions are read with the SDK's dexdump (authoritative, and it
// ignores nothing); without it the task falls back to a raw byte search for
// the entry descriptor and reports the strict checks as skipped.
//
// A violation fails the build.
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

val apkDebugDir = layout.buildDirectory.dir("outputs/apk/debug")
val verifyWorkDir = layout.buildDirectory.dir("apk-verify")

/** Class descriptors defined in [dex], via dexdump. */
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
        val summary = StringBuilder("verifyModuleApk: ${apk.name} (${apk.length()} bytes)\n")
        summary.append("  dexdump: ${dexdumpBinary?.absolutePath ?: "(not found)"}\n")

        // var on purpose: Kotlin forbids initialising a captured `val` inside a lambda.
        var entryClass = ""
        val descriptors = LinkedHashMap<String, List<String>>()
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

            entryClass = zip.getEntry("META-INF/xposed/java_init.list")
                ?.let { zip.getInputStream(it).bufferedReader().readText() }
                ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
                .orEmpty()
            if (entryClass.isEmpty()) problems.add("java_init.list declares no entry class")
            val descriptor = descriptorFor(entryClass)

            val dexes = zip.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
                .sorted()
                .toList()
            if (dexes.isEmpty()) problems.add("no dex image in the APK")

            for (dex in dexes) {
                val bytes = zip.getInputStream(zip.getEntry(dex)).readBytes()
                val file = File(outDir, dex)
                file.writeBytes(bytes)
                if (dexdumpBinary != null) {
                    descriptors[dex] = dexdumpClasses(dexdumpBinary, file, outDir)
                }
                // Raw fallback signal, and cheap enough to always compute.
                val rawHit = String(bytes, Charsets.ISO_8859_1).contains(descriptor)
                summary.append("  $dex: ${bytes.size} bytes, raw descriptor hit: $rawHit\n")
            }
        }

        val defined = descriptors.values.flatten()
        if (dexdumpBinary == null) {
            summary.append("  SKIPPED class-definition checks (no dexdump in the SDK)\n")
        } else {
            var entryIn: String? = null
            for ((dex, names) in descriptors) {
                if (names.contains(descriptorFor(entryClass))) entryIn = dex
                summary.append("  $dex: ${names.size} classes defined\n")
            }
            if (entryIn == null) {
                problems.add("entry class $entryClass is not defined in any dex (${descriptors.keys.joinToString()})")
            } else {
                summary.append("  entry class $entryClass -> $entryIn\n")
            }

            val ours = defined.filter { it.startsWith("Lsumicya/qself/") }
            summary.append("  sumicya/qself classes defined: ${ours.size}\n")
            for (name in ours.sorted()) summary.append("    $name\n")

            val uiLibs = defined.count {
                it.startsWith("Landroidx/") || it.startsWith("Lcom/google/android/material/")
            }
            summary.append("  AndroidX/Material classes defined: $uiLibs\n")
            if (uiLibs != 0) problems.add("$uiLibs AndroidX/Material classes are bundled")

            val stubs = defined.filter {
                it.startsWith("Lio/github/libxposed/") || it.startsWith("Lde/robv/android/xposed/")
            }
            summary.append("  framework API stub classes defined: ${stubs.size}\n")
            for (name in stubs.sorted()) summary.append("    $name\n")
            if (stubs.isNotEmpty()) problems.add("${stubs.size} framework API stub classes are bundled")
        }

        for (problem in problems) summary.append("  PROBLEM: $problem\n")
        throw GradleException(summary.toString().trimEnd())
    }
}

/** "a.b.C" -> "La/b/C;". */
fun descriptorFor(fqcn: String): String = "L" + fqcn.replace('.', '/') + ";"

// matching + configureEach: AGP 9 registers its assemble* tasks lazily, so
// tasks.named("assembleDebug") would not find anything at configuration time.
tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(verifyModuleApk)
}
