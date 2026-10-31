
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
        // 本地出包也必须显式给五段号，缺失即失败；不允许四段或常数回退。
        val releaseVersion = System.getenv("QSELF_VERSION")
            ?: error("缺少 QSELF_VERSION：出包请显式给出五段版本 yy.m.d.当日序号.总序号")
        require(Regex("""^\d{2}\.\d{1,2}\.\d{1,2}(\.\d+){2}$""").matches(releaseVersion)) {
            "QSELF_VERSION 不是五段格式：$releaseVersion"
        }
        versionName = releaseVersion
        val total = releaseVersion.substringAfterLast(".").toIntOrNull()
        require(total != null && total > 0) { "总提交数必须为正整数且不超过 Android versionCode 上限" }
        versionCode = total
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

    // 将源码移植的许可随 APK 一同分发，不添加任何运行库。
    sourceSets["main"].assets.srcDir(rootProject.file("third_party"))

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
            val version = output.versionName.orNull ?: error("缺少变体版本号，禁止常数回退")
            output.outputFileName.set("Qself-$version.apk")
        }
    }
}
