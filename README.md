# android-supply-chain-lab

Android アプリのサプライチェーンリスクに対する理想の運用を探る検証リポジトリ。

最終的な成果は、既存の Android アプリに適用できる運用のテンプレートにする。

## 検証テーマ

1. 土台と Gradle wrapper の検証
2. `verification-metadata.xml` と Dependabot の運用
3. version catalog の指定と推移的依存のバージョンのズレの検知と固定
4. private ライブラリを GitHub Packages の Maven で配信するときの完全性の検証と provenance

テーマ 1 の判断と検証結果は [ADR-0001](docs/adr/0001-gradle-wrapper-verification.md) にまとめている。

## 構成

単一の `:app` モジュールからなる最小の Android アプリ。Kotlin と Jetpack Compose を使い、依存の版は `gradle/libs.versions.toml` で管理する。

| 項目 | 版 |
| --- | --- |
| Gradle | 9.7.1 |
| Android Gradle plugin | 9.3.3 |
| Kotlin | 2.4.21 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 29 |

版は [Kotlin の互換性表](https://kotlinlang.org/docs/gradle-configure-project.html) で Fully supported とされる範囲を優先して選んだ。Kotlin 2.4.21 の上限は Gradle 9.7.0、AGP 9.3.1 で、Gradle と AGP はそれぞれ同じ minor の修正版を使う。AGP 9.3.1 は JDK 17 で Lint がクラッシュする問題があり、9.3.2 で修正された。

Kotlin は AGP の built-in Kotlin で使うため `org.jetbrains.kotlin.android` は適用しない。KGP の版は Compose Compiler plugin の依存によって 2.4.21 に解決される。

compileSdk は Compose BOM 2026.09.00 の要求で 37 にしている。

## ビルド

```sh
./gradlew build
```
