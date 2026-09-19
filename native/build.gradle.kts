/*
 * Qself native hook engine (Dobby + LSPlant), JNI surface for diagnostics and
 * the native hooking paths.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// Explicit imports: inside a Kotlin DSL script bare `java` resolves to Gradle's
// java extension, not to the JDK package.
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

/*
 * LSPlant is compiled from source and its C++23 module targets need Ninja
 * >= 1.11, while the Ninja bundled with the Android CMake package is 1.10.2.
 * Point the build at a newer one by adding this to local.properties:
 *
 *   qself.ninja.path=/usr/bin/ninja
 *
 * Falling back to a `ninja` on PATH keeps this working on machines (and CI
 * images) that already ship a recent one.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { stream -> load(stream) }
    }
}

/** First executable named [name] on PATH, without spawning a process. */
fun findOnPath(name: String): String? =
    System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.asSequence()
        ?.map { directory -> File(directory, name) }
        ?.firstOrNull { candidate -> candidate.canExecute() }
        ?.absolutePath

val ninjaOverride: String? = localProperties.getProperty("qself.ninja.path")
    ?.takeIf { it.isNotBlank() }
    ?: findOnPath("ninja")

android {
    namespace = "sumicya.qself.engine"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    // Pinned NDK: the engine is built against the version this project is
    // tested with, and LSPlant's CMake needs the NDK's C++23 toolchain.
    ndkVersion = "29.0.13599879"

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    // Static STL on purpose: this library is loaded *inside
                    // the host app (QQ ships its own libc++_shared.so), and two
                    // different libc++_shared.so revisions in one process is a
                    // classic instant-crash when a native module is injected.
                    // Self-contained is the only safe option here.
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
                if (ninjaOverride != null) {
                    arguments += "-DCMAKE_MAKE_PROGRAM=$ninjaOverride"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.0"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
