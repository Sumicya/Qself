import java.time.LocalDate

plugins {
    id("com.android.application")
}

android {
    namespace = "sumicya.qself"
    compileSdk = 37

    defaultConfig {
        applicationId = "sumicya.qself"
        minSdk = 36 // 只对着 Android 16 / QQ 9.2.10 写，不做旧版本降级
        targetSdk = 37
        // 版本 = 构建日期.CI 构建号（本地编译没 run 号就是 .0）。vc 就是 run 号本身：
        // 会比装着的旧包小（判降级），模块是手动装的，卸了重装就行。
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = LocalDate.now().let { "${it.year % 100}.${it.monthValue}.${it.dayOfMonth}." } +
            (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
    }

    // 固定签名（app/qself.p12，密码 qself）：每次 CI 出的包都能直接覆盖安装，热重载才接得上。
    signingConfigs.getByName("debug") {
        storeFile = file("qself.p12")
        storePassword = "qself"
        keyAlias = "qself"
        keyPassword = "qself"
    }

    buildTypes {
        // R8 只裁不混淆：把没用到的 kotlin-stdlib 裁掉，崩溃栈还是明文。
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true // 只为日志里那一句版本号
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // LSPosed 在 QQ 进程里提供，绝不打进 APK。
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
