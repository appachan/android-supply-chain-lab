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
                        // credentials(PasswordCredentials::class) を使うと、認証情報がないときに Gradle がビルドの開始時に止まり、lib を使わないタスクも実行できない
                        // version catalog の取り込みが settings の repository の認証情報を評価するためである
                        // Dependabot は GitHub Packages の認証情報なしで、lockfile の更新のために Gradle を実行する
                        // Dependabot、fork の PR、token を持たない clone でもビルドを動かすため、同じ名前のプロパティを自前で読む
                        // 認証情報がないときは、lib を取得する時点で Username must not be null! や 401 などで失敗する
                        credentials {
                            username = providers.gradleProperty("GitHubPackagesUsername").orNull
                            password = providers.gradleProperty("GitHubPackagesPassword").orNull
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
