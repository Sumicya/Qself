import java.time.LocalDate
import java.time.ZoneId

plugins {
    id("com.android.application")
}

// 版本 = 提交日（Asia/Shanghai）.CI 序号。CI 上 QSELF_VERSION 由工作流从**提交时间**算好喂进来，
// 一次固定，重试、跨日重试都不变；本地构建没有它，日期取当天、尾号 .0（本地包不是发行版本）。
// versionCode = 本工作流的 run 号，单调递增；已发布最大值 168（2026-10-02），
// 工作流删除重建会让 run 号回到 1，重建后先核验新序号大于它再发。
val qselfCi = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val qselfVersion: String = System.getenv("QSELF_VERSION")
    ?: LocalDate.now(ZoneId.of("Asia/Shanghai")).let { "${it.year % 100}.${it.monthValue}.${it.dayOfMonth}.0" }

android {
    namespace = "sumicya.qself"
    compileSdk = 37

    defaultConfig {
        applicationId = "sumicya.qself"
        minSdk = 36 // 只对着 Android 16 写，不判版本、不写降级分支
        targetSdk = 37
        versionName = qselfVersion
        versionCode = qselfCi ?: 1
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
