plugins {
    id("com.android.application")
}

android {
    namespace = "sumicya.qself"
    compileSdk = 37

    defaultConfig {
        applicationId = "sumicya.qself"
        minSdk = 31 // RenderEffect
        targetSdk = 37
        versionCode = 300
        versionName = "0.3.0"
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
