/*
 * Qself — the module APK (Xposed entry + settings UI + features).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "sumicya.qself"
    compileSdk = 37

    defaultConfig {
        applicationId = "sumicya.qself"
        minSdk = 24
        targetSdk = 36
        versionCode = 20001
        versionName = "2.0.0-alpha01"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":native"))
    // META-INF/xposed/module.prop (LSPosed 10.x metadata)
    implementation(project(":tools:moduleprop"))

    // provided by the framework at runtime; never bundled
    compileOnly(project(":libs:libxposed:api"))
    compileOnly(libs.xposed.api)

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)
}

ksp(project(":tools:ksp"))
