// Top-level build file. Module-specific config lives in each module's build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// Kill the legacy `clean` task; Gradle's built-in `clean` per-module is what we want.
tasks.register("printVersions") {
    group = "help"
    description = "Print key tool versions for debugging build issues."
    doLast {
        println("Gradle:    ${gradle.gradleVersion}")
        println("Kotlin:    ${libs.versions.kotlin.get()}")
        println("AGP:       ${libs.versions.agp.get()}")
        println("Compose BOM: ${libs.versions.composeBom.get()}")
        println("Hilt:      ${libs.versions.hilt.get()}")
    }
}
