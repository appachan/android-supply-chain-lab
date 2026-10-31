plugins {
    id("lab.android.application")
    id("lab.android.compose")
}

android {
    namespace = "com.github.appachan.supplychainlab"

    defaultConfig {
        applicationId = "com.github.appachan.supplychainlab"
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation(project(":feature:greeting"))
    implementation(project(":shared:domain"))
    implementation(project(":shared:ui"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.tmp.commons.codec)

    constraints {
        implementation(libs.tmp.commons.codec) {
            version {
                strictly(libs.versions.tmpCommonsCodec.get())
            }
        }
    }

    testImplementation(libs.junit4)
}
