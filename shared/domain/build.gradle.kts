plugins { id("lab.kotlin.jvm") }

dependencies {
    implementation("commons-io:commons-io:2.21.0") {
        version {
            strictly("2.21.0")
        }
    }
}
