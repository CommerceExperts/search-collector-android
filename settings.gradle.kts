pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Kotlin version pinned here so IntelliJ can discover it without adding kotlin("jvm") to every module
    plugins {
        kotlin("android") version "2.0.21" apply false
        kotlin("plugin.serialization") version "2.0.21" apply false
        kotlin("plugin.compose") version "2.0.21" apply false
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // gradle/libs.versions.toml is picked up automatically as the "libs" catalog
}

rootProject.name = "search-collector-android"

include(":library")
include(":library-workmanager")
include(":demo-app")
