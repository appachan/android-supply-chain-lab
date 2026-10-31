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

## 検証が POM で失敗したとき

親や BOM の POM の行は、再生成 1 回では書かれないことがある。子と親が別の repository にあり、子の repository に `.asc` がないときである。例えば AGP 9.5.0 の `sdk-common`（Google Maven）は、Maven Central の `bc-jdk18on-bom` を参照する。このとき `verify` は対象のファイルと repository を示して失敗する。

```text
One artifact failed verification: bc-jdk18on-bom-1.80.2.pom (org.bouncycastle:bc-jdk18on-bom:1.80.2) from repository Google
```

次の手順で行を足す。例は `bc-jdk18on-bom` である。

1. 配布元の POM と `.asc` を取得し、`from repository` の repository に `.asc` がないことを確かめる

   ```sh
   base=https://repo.maven.apache.org/maven2/org/bouncycastle/bc-jdk18on-bom/1.80.2/bc-jdk18on-bom-1.80.2.pom
   curl -fO "$base"
   curl -fO "$base.asc"
   curl -s -o /dev/null -w '%{http_code}\n' https://dl.google.com/dl/android/maven2/org/bouncycastle/bc-jdk18on-bom/1.80.2/bc-jdk18on-bom-1.80.2.pom.asc
   ```

2. 署名を確かめる。`gradle/verification-keyring.keys` は鍵の自己署名を省いていて `gpg` に取り込めない鍵があるので、鍵サーバーの鍵を使う。表示された fingerprint が `gradle/verification-metadata.xml` の `trusted-key` にあり、その group が対象と合うことを確かめる

   ```sh
   export GNUPGHOME="$(mktemp -d)"
   gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys 7B121B76A7ED6CE6E60AD51784E913A8E3A748C0
   gpg --verify bc-jdk18on-bom-1.80.2.pom.asc bc-jdk18on-bom-1.80.2.pom
   ```

3. sha256 を計算し、`gradle/verification-metadata.xml` の `components` に行を足す。`reason` に確かめた結果を書く

   ```xml
   <component group="org.bouncycastle" name="bc-jdk18on-bom" version="1.80.2">
      <artifact name="bc-jdk18on-bom-1.80.2.pom">
         <sha256 value="5f59cc51b21d92ddc08cc93b488983ea43dd211fe8d950335ef31bcdd7b15b47" origin="Added by hand" reason="Signature verified with key 7B121B76A7ED6CE6E60AD51784E913A8E3A748C0 from the Maven Central .asc. Google Maven has no .asc for this POM, so the route through the child in Google needs this checksum. See docs/adr/0002-dependency-verification.md"/>
      </artifact>
   </component>
   ```

4. 別の空の `GRADLE_USER_HOME` で検証する

   ```sh
   GRADLE_USER_HOME="$(mktemp -d)" ./gradlew build
   ```

手で足した行は再生成で残る。署名を確かめられないときは行を足さず、更新を見送る。

### 誰が、どのブランチに足すか

自分で依存を足す PR では、PR の作成者が同じブランチに行を足す。

Dependabot の PR では、PR を確認する人が次の順番で作業する。Dependabot のブランチには直接コミットしない。`[dependabot skip]` のないコミットがあると、Dependabot が rebase を断るためである。

1. main から別のブランチを作り、上の手順で確かめた行だけを足す。再生成は実行しない。この行はまだ使われないが、`verify` は成功する
2. PR を作り、`verify` の成功の後に merge する
3. Dependabot の PR に `@dependabot rebase` をコメントする
4. `Regenerate verification metadata` と `Commit verification metadata` の結果を確かめ、再生成のコミットがあれば Approve workflows to run を押す
5. PR の `verify` の成功を確かめて merge する

この順番は実際の Dependabot の PR では試していない。手元で確かめたのは、使われない行があっても `verify` が成功すること、行が再生成で残ること、行を足すと `verify` が成功することである。手順 3 の `@dependabot rebase` が動くかは未確認で、反応がないときの代わりの手順も決まっていない。

## Gradle を更新する

Gradle の更新の PR は、merge の前に Android Studio で sync する。Gradle の更新で鍵や取得するソースが変わると、CI は成功して sync だけが失敗することがある。
