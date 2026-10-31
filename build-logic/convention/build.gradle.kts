buildscript {
    configurations.classpath { resolutionStrategy.failOnNonReproducibleResolution() }
}

plugins {
    `kotlin-dsl`
}

configurations.configureEach { resolutionStrategy.failOnNonReproducibleResolution() }

dependencies {
    compileOnly("commons-lang:commons-lang:2.5")
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "lab.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "lab.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "lab.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("kotlinJvm") {
            id = "lab.kotlin.jvm"
            implementationClass = "KotlinJvmConventionPlugin"
        }
    }
}
