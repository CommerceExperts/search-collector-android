plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Convenience tasks for the publishable library modules only (excludes demo-app).
// Usage:
//   ./gradlew buildLibraries     — assemble release AARs + run unit tests
//   ./gradlew assembleLibraries  — assemble release AARs only (faster, no tests)
//   ./gradlew testLibraries      — run unit tests only

val libraryModules = listOf(":library", ":library-workmanager")

tasks.register("assembleLibraries") {
    group = "build"
    description = "Assembles release AARs for all publishable library modules."
    libraryModules.forEach { dependsOn("$it:assembleRelease") }
}

tasks.register("testLibraries") {
    group = "verification"
    description = "Runs unit tests for all publishable library modules."
    libraryModules.forEach { dependsOn("$it:test") }
}

tasks.register("buildLibraries") {
    group = "build"
    description = "Assembles release AARs and runs unit tests for all publishable library modules."
    dependsOn("assembleLibraries", "testLibraries")
}
