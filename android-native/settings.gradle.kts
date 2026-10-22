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
        // NewPipeExtractor (YouTube trailers, as Harbor's yt-dlp).
        maven("https://jitpack.io")
    }
}

rootProject.name = "harbor-android"
include(":app")
// Generates the app's baseline profile (what to compile ahead of time) on a device.
include(":baselineprofile")
