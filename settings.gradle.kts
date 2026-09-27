pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    includeBuild("build-logic/convention")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "android_app"

include(
    ":app",
    ":core-protocol",
    ":core-ble",
    ":core-session",
    ":core-media",
    ":feature-control",
    ":feature-gps",
    ":feature-debug",
)
