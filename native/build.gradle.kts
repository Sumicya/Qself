/*
 * Qself native hook engine (Dobby), JNI surface for diagnostics and future
 * native-level features.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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
val localProperties = java.util.Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

/** First executable named [name] on PATH, without spawning a process. */
fun findOnPath(name: String): String? =
    System.getenv("PATH")
        ?.split(java.io.File.pathSeparator)
        ?.asSequence()
        ?.map { java.io.File(it, name) }
        ?.firstOrNull { it.canExecute() }
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

    // Pinned NDK: the native engine is built against the version this
    // project is tested with (and LSPlant, wired in v1.1, needs r29+).
    ndkVersion = "29.0.13599879"

    defaultConfig {
        minSdk = 26

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
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
