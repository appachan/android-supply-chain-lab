plugins {
    id("lab.android.library")
    id("lab.android.compose")
}

android { namespace = "com.github.appachan.supplychainlab.shared.ui" }

dependencies { implementation(libs.supply.chain.lab.lib) }
