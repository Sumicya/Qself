/*
 * Qself native hook engine (Dobby), JNI surface for diagnostics and future
 * native-level features.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sumicya.qself.native"
    compileSdk = 37

    // Pinned NDK: the native engine is built against the version this
    // project is tested with (and LSPlant, wired in v1.1, needs r29+).
    ndkVersion = "29.0.13599879"

    defaultConfig {
        minSdk = 24

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
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
