pluginManagement {
    repositories {
        // Order matters: google() first for AndroidX/AGP
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Keep both; google() must be present for AndroidX CameraX
        google()
        mavenCentral()
    }
}
rootProject.name = "VideoPhotoDebug"
include(":app")

