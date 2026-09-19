@file:Suppress("UnstableApiUsage")

// Type-safe project accessors (`projects.core`, `projects.tools.ksp`).
// They produce ProjectDependency, which also keeps dependency-invocation
// names from clashing with plugin extensions (e.g. KSP's `ksp`).
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // classic Xposed API (de.robv.android.xposed:api)
        maven("https://api.xposed.info/") {
            content {
                includeGroup("de.robv.android.xposed")
            }
        }
    }
}

rootProject.name = "Qself"

include(
    ":app",
    ":core",
    ":native",
    ":tools:ksp",
    ":tools:moduleprop",
    ":libs:libxposed:api",
)
