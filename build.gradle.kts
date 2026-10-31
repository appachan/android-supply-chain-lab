buildscript {
    configurations.classpath { resolutionStrategy.failOnNonReproducibleResolution() }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// lock と failOn は同じ configuration で併用できない。理由は docs/adr/0003-version-resolution.md を参照
subprojects {
    configurations.configureEach {
        if (name.endsWith("CompileClasspath", ignoreCase = true) || name.endsWith("RuntimeClasspath", ignoreCase = true)) {
            resolutionStrategy.activateDependencyLocking()
        } else {
            resolutionStrategy.failOnNonReproducibleResolution()
        }
    }
}
