/*
 * Packages META-INF/xposed/module.prop (LSPosed 10.x module metadata) into
 * the APK. A plain jar is the reliable way to ship META-INF entries —
 * Android source sets do not package src/main/resources.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
