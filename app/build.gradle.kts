import java.time.Instant
import java.time.ZoneId

plugins {
    id("com.android.application")
}

android {
    namespace = "sumicya.qself"
    compileSdk = 37

    defaultConfig {
        applicationId = "sumicya.qself"
        minSdk = 36 // 只对着 Android 16 写：RenderEffect / AGSL 都不用判版本
        targetSdk = 37
        // 版本 = 构建日（Asia/Shanghai）.CI 序号。CI 上 QSELF_VERSION 由工作流从提交时间算好喂进来，
        // 重试、跨日重试都不变；本地自己按提交时间算，没有 run 号就 .0。
        // versionCode 就是 run 号本身：单调递增（模块是手动覆盖装的，够用）。
        versionName = System.getenv("QSELF_VERSION") ?: run {
            val epoch = runCatching {
                val out = java.io.ByteArrayOutputStream()
                exec { commandLine("git", "show", "-s", "--format=%ct", "HEAD"); standardOutput = out }
                out.toString().trim().toLong()
            }.getOrDefault(System.currentTimeMillis() / 1000)
            val d = Instant.ofEpochSecond(epoch).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
            "${d.year % 100}.${d.monthValue}.${d.dayOfMonth}.0"
        }
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
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
