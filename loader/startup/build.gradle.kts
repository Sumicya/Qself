plugins {
    id("build-logic.android.library-jre8")
}

android {
    namespace = "io.github.qauxv.startup"
}

dependencies {
    compileOnly(libs.androidx.annotation)
    compileOnly(projects.loader.hookapi)
}
