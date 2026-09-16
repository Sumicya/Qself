plugins {
    id("build-logic.android.library-jre8")
}

android {
    namespace = "io.github.qauxv.loader.sbl"

    defaultConfig {
        buildConfigField("String", "VERSION_NAME", "\"${Common.getBuildVersionName(rootProject)}\"")
        buildConfigField("int", "VERSION_CODE", "${Common.getBuildVersionCode(rootProject)}")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Xposed API 89
    compileOnly(libs.xposed.api)
    // LSPosed API 100
    compileOnly(projects.libs.libxposed.api)
    compileOnly(libs.androidx.annotation)
    implementation(projects.loader.hookapi)
}
