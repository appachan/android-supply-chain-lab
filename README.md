# android-supply-chain-lab

Android アプリのサプライチェーンリスクに対する理想の運用を探る検証リポジトリ。

最終的な成果は、既存の Android アプリに適用できる運用のテンプレートにする。

## 検証テーマ

1. 土台と Gradle wrapper の検証
2. `verification-metadata.xml` と Dependabot の運用
3. version catalog の指定と推移的依存のバージョンのズレの検知と固定
4. private ライブラリを GitHub Packages の Maven で配信するときの完全性の検証と provenance

テーマ 1 の判断と検証結果は [ADR-0001](docs/adr/0001-gradle-wrapper-verification.md)、テーマ 2 は [ADR-0002](docs/adr/0002-dependency-verification.md)、テーマ 3 は [ADR-0003](docs/adr/0003-version-resolution.md)、テーマ 4 は [android-supply-chain-lab-lib の ADR-0001](https://github.com/appachan/android-supply-chain-lab-lib/blob/main/docs/adr/0001-private-library-distribution.md) にまとめている。

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

`:shared:ui` は GitHub Packages の `com.github.appachan:android-supply-chain-lab-lib` に依存する。public のパッケージでも匿名では読めないので、`read:packages` のある token を環境変数で渡す。Android Studio の sync にも同じ環境変数が要る。

```sh
export GITHUB_ACTOR=<GitHub のユーザー名>
export GITHUB_TOKEN=<read:packages のある token>
./gradlew build
```

token がないと `Username must not be null!` で失敗する。Actions の CI は `GITHUB_TOKEN` で読むので、シークレットは要らない。fork の PR の扱いは ADR-0002 の「GitHub Packages のライブラリ」に書く。

各 module の `gradle.lockfile` と異なるバージョンが解決されるとビルドが失敗する。依存を変えたときは、次の手順で `gradle/verification-metadata.xml` と `gradle.lockfile` を一緒に更新する。詳細は [ADR-0002](docs/adr/0002-dependency-verification.md) と [ADR-0003](docs/adr/0003-version-resolution.md) を参照。

## 依存を追加する

1. 依存を `gradle/libs.versions.toml` と build script に足す
2. Android Studio の sync が検証で止まる。`This can indicate that a dependency has been compromised` の定型の文言は、未登録の依存でも出る
3. 2 段階で再生成する。`sha256` が全成果物のハッシュを書き、`pgp,sha256` が鍵の信頼を足す。`--write-locks` は 1 段階目に必要である

   ```sh
   ./gradlew --write-verification-metadata sha256 --write-locks build
   ./gradlew --write-verification-metadata pgp,sha256 --export-keys build
   ```

4. Android Studio の sync が通ることを確かめる
5. 差分で、増えた鍵と group の範囲を確かめる。再生成は鍵の信頼を group の単位にまとめることがある。例えば okhttp を足すと、`com.squareup` 全体を信頼する行が増える。`also-trust` と `ignored-key` は検証の失敗を受け入れた行なので、残さない
6. PR を出す

## Dependabot の PR

`Regenerate verification metadata` と `Commit verification metadata` が、metadata と lockfile を PR に足す。

再生成はハッシュの不一致でも署名の失敗でも止まらず、metadata に `also-trust` か `ignored-key` を書いて続ける。`Commit verification metadata` はこれらがあると失敗する。このときは merge せず、原因を調べる。

PR を確認する人は次の順番で作業する。Dependabot のブランチには直接コミットしない。`[dependabot skip]` のないコミットがあると、Dependabot が rebase を断るためである。

1. `Regenerate verification metadata` と `Commit verification metadata` の結果を確かめ、再生成のコミットがあれば Approve workflows to run を押す。`CI` と `Regenerate verification metadata` の両方の run の承認が必要である。`verify` だけを動かすと、`mergeStateStatus` は `UNSTABLE` のままになる
2. PR の `verify` の成功と `mergeStateStatus` の `CLEAN` を確かめて merge する

PR を main に追従させるときは `@dependabot recreate` をコメントする。再生成のコミットがある PR では `@dependabot rebase` は反応しなかった。

## Gradle を更新する

Gradle の更新の PR は、merge の前に Android Studio で sync する。Gradle の更新で鍵や取得するソースが変わると、CI は成功して sync だけが失敗することがある。
