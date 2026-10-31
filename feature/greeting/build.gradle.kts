plugins {
    id("lab.android.library")
    id("lab.android.compose")
}

android { namespace = "com.github.appachan.supplychainlab.feature.greeting" }

dependencies {
    implementation(project(":shared:domain"))
    implementation(project(":shared:ui"))
}
