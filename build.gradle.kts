buildscript {
    configurations.classpath { resolutionStrategy.failOnNonReproducibleResolution() }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}

allprojects { apply(from = rootProject.file("gradle/resolved-versions.gradle.kts")) }
