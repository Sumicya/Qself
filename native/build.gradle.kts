/*
 * Qself native hook engine (Dobby), JNI surface for diagnostics and future
 * native-level features.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.android.library)
}

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
