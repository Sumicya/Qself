import java.time.ZoneId
import java.time.ZonedDateTime

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
        // 版本 = yy.m.d.当日序号.总序号。日期固定按上海时区；本地构建没有 CI 序号时为 0.0。
        val buildDate = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
        val dailySequence = System.getenv("QSELF_DAILY_SEQUENCE")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val totalSequence = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        versionCode = totalSequence.coerceAtLeast(1)
        versionName = "${buildDate.year % 100}.${buildDate.monthValue}.${buildDate.dayOfMonth}.$dailySequence.$totalSequence"
    }

    // 仓库不保存私钥。debug 使用 Android 默认签名；正式发布签名由安全存储提供。

    buildTypes {
        // R8 只裁不混淆：把没用到的 kotlin-stdlib 裁掉，崩溃栈还是明文。
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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


androidComponents {
    onVariants(selector().all()) {
        outputs.forEach { output ->
            val version = output.versionName.orNull ?: "0.0.0.0.0"
            output.outputFileName.set("Qself-$version.apk")
        }
    }
}
