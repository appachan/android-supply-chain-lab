# android-supply-chain-lab

Android アプリのサプライチェーンリスクに対する理想の運用を探る検証リポジトリ。

最終的な成果は、既存の Android アプリに適用できる運用のテンプレートにする。

## 検証テーマ

1. 土台と Gradle wrapper の検証
2. `verification-metadata.xml` と Dependabot の運用
3. version catalog の指定と推移的依存のバージョンのズレの検知と固定
4. private ライブラリを GitHub Packages の Maven で配信するときの完全性の検証と provenance

テーマ 1 の判断と検証結果は [ADR-0001](docs/adr/0001-gradle-wrapper-verification.md)、テーマ 2 は [ADR-0002](docs/adr/0002-dependency-verification.md)、テーマ 3 は [ADR-0003](docs/adr/0003-version-resolution.md) にまとめている。

## 構成

Kotlin と Jetpack Compose を使う最小の Android アプリ。依存の版は `gradle/libs.versions.toml` で管理する。

| module | 種類 |
| --- | --- |
| `:app` | アプリ |
| `:feature:greeting` | Android library |
| `:shared:ui` | Android library |
| `:shared:domain` | Kotlin JVM |
| `build-logic` | convention plugin の included build |

モジュールは [rss-reader](https://github.com/appachan/rss-reader) と同じ種類の構成を確かめるためのもので、中身は最小限にしている。

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

各 module の `gradle.lockfile` と異なるバージョンが解決されるとビルドが失敗する。依存を変えたときは、次の手順で `gradle/verification-metadata.xml` と `gradle.lockfile` を一緒に更新する。詳細は [ADR-0002](docs/adr/0002-dependency-verification.md) と [ADR-0003](docs/adr/0003-version-resolution.md) を参照。

## 依存を追加する

1. 依存を `gradle/libs.versions.toml` と build script に足す
2. Android Studio の sync が検証で止まる。`This can indicate that a dependency has been compromised` の定型の文言は、未登録の依存でも出る
3. 再生成のスクリプトを実行する

   ```sh
   ./scripts/regenerate-dependency-metadata.sh
   ```

4. Android Studio の sync が通ることを確かめる
5. 差分で、増えた鍵と group の範囲を確かめる。再生成は鍵の信頼を group の単位にまとめることがある。例えば okhttp を足すと、`com.squareup` 全体を信頼する行が増える
6. PR を出す

## Gradle を更新する

Gradle の更新の PR は、merge の前に Android Studio で sync する。Gradle の更新で鍵や取得するソースが変わると、CI は成功して sync だけが失敗することがある。
