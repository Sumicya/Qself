plugins {
    id("build-logic.android.library-jre8")
}

android {
    namespace = "io.github.qauxv.loader.hookapi"
}

dependencies {
    compileOnly(libs.androidx.annotation)
}
