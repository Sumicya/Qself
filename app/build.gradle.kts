
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
        // CI 先固化完整版本；Gradle 只读取单一版本来源，不自行取时间或序号。
        // 本地出包也要显式给：QSELF_VERSION=26.10.15.1.262 ./gradlew :app:assembleDebug（退化时四段 yy.m.d.总序号），缺了当场失败。
        val releaseVersion = System.getenv("QSELF_VERSION")
            ?: error("缺少 QSELF_VERSION：出包请显式给出四段/五段版本（yy.m.d.[当日序号.]总序号），口径见 AGENTS.md")
        require(Regex("""^\d{2}\.\d{1,2}\.\d{1,2}(\.\d+){1,2}$""").matches(releaseVersion)) {
            "QSELF_VERSION 不是四段或五段格式：$releaseVersion"
        }
        versionName = releaseVersion
        versionCode = releaseVersion.substringAfterLast(".").toIntOrNull()?.coerceAtLeast(1) ?: 1
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
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val version = output.versionName.orNull ?: "0.0.0.0.0"
            output.outputFileName.set("Qself-$version.apk")
        }
    }
}
