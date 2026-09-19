/*
 * Ships META-INF/xposed/module.prop (LSPosed 10.x module metadata) inside
 * the APK.
 *
 * An Android library is used on purpose: its src/main/resources entries are
 * merged into the APK root, which is how the pre-rewrite setup shipped the
 * same file. This module has no code.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "sumicya.qself.moduleprop"
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
