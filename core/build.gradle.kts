/*
 * Qself core runtime.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sumicya.qself.core"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Both Xposed APIs are provided by the framework at runtime; never bundled.
    compileOnly(libs.xposed.api)
    compileOnly(projects.libs.libxposed.api)

    implementation(libs.androidx.annotation)
}
