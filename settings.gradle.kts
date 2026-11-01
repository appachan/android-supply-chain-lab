pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
buildscript {
    configurations.all { resolutionStrategy.failOnNonReproducibleResolution() }
}
dependencyResolutionManagement {
    // 再生成のジョブは token を持たないので、取得済みのディレクトリを指定する
    val libRepositoryDirectory = providers.gradleProperty("libRepositoryDirectory")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        // Dependabot が読むので URL はリテラルで書く。docs/adr/0002-dependency-verification.md を参照
        exclusiveContent {
            forRepository {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/appachan/android-supply-chain-lab-lib")
                    if (libRepositoryDirectory.isPresent) {
                        setUrl(libRepositoryDirectory.get())
                    } else {
                        credentials {
                            username = providers.environmentVariable("GITHUB_ACTOR").orNull
                            password = providers.environmentVariable("GITHUB_TOKEN").orNull
                        }
                    }
                }
            }
            filter { includeModule("com.github.appachan", "android-supply-chain-lab-lib") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "android-supply-chain-lab"

include(":app")
include(":feature:greeting")
include(":shared:domain")
include(":shared:ui")
