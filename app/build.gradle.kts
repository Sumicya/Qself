/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.material)

    // feature registry processor (@QselfFeature -> QselfFeatures)
    ksp(projects.tools.ksp)
}
