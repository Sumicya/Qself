/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

@file:Suppress("UnstableApiUsage")

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion

plugins {
    id("com.android.library")
}

/**
 * Convention for pure-Java legacy Android library modules that must keep
 * Java 8 bytecode for maximum compatibility with the host process
 * (e.g. the loader modules).
 */
extensions.findByType(CommonExtension::class)?.run {
    compileSdk {
        // "36.1" -> major=36, minor=1
        version = release(Version.compileSdkVersion.substringBefore('.').toInt()) {
            minorApiLevel = Version.compileSdkVersion.substringAfter('.').toIntOrNull()
        }
    }

    defaultConfig.apply {
        minSdk = Version.minSdk
    }

    compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
