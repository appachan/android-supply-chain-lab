# 1. Gradle wrapper verification

Date: 2026-10-30

## Status

Accepted

## Context

Gradle wrapper には、ビルドの最初に実行されるものが 2 つある。

- `gradle/wrapper/gradle-wrapper.jar`: リポジトリに commit されたバイナリ。`gradlew` が最初に実行する
- Gradle の配布物: `gradle-wrapper.jar` が `distributionUrl` からダウンロードして実行する zip

どちらもビルドより先に任意のコードを実行できる。また `gradle-wrapper.jar` はバイナリなので、PR の diff では内容を確認できない。

## Decision

次の 2 つを併用する。検証の対象が異なるので、片方でもう片方を代替できない。

- `gradle-wrapper.properties` に `distributionSha256Sum` を設定し、配布物を検証する
- CI の最初に Gradle 公式の [wrapper validation Action](https://github.com/gradle/actions/blob/v6.4.1/docs/wrapper-validation.md) を実行し、`gradle-wrapper.jar` を検証する

CI では Gradle User Home をキャッシュしない。`distributionSha256Sum` はダウンロードのときにしか検証されないので、配布物がキャッシュから復元されると検証が実行されないからである。このため `gradle/actions/setup-gradle` は使わない。`setup-gradle` の basic provider は `~/.gradle/wrapper` を保存すると[ドキュメント](https://github.com/gradle/actions/blob/v6.4.1/docs/setup-gradle.md#what-basic-caching-stores)に書かれている。既定の enhanced provider の扱いは確認していない。キャッシュなしの CI は毎回の依存のダウンロードで遅くなるが、このリポジトリの規模では許容する。

`setup-gradle` は既定で wrapper validation を実行する（`validate-wrappers`）ので、`setup-gradle` を使うプロジェクトで wrapper validation の Action を別に実行すると検証が重複する。

## 何を守り、何を守らないか

| 項目 | `distributionSha256Sum` | wrapper validation Action |
| --- | --- | --- |
| 検証対象 | ダウンロードした Gradle の配布物 | リポジトリ内の `gradle-wrapper.jar` |
| 比較先 | `gradle-wrapper.properties` の値 | Gradle の公式リリースの checksum の一覧 |
| 検証する場所 | `gradlew` を実行した環境 | CI |
| 検証するタイミング | 配布物が未ダウンロードのときだけ | CI の実行ごと |

`distributionSha256Sum` が守るもの:

- ダウンロード元や経路で配布物が差し替えられること。配布物が記録した値と一致しなければ、`gradlew` は Gradle を実行せず失敗する

`distributionSha256Sum` が守らないもの:

- `distributionUrl` と `distributionSha256Sum` を同時に書き換える変更。値の正しさはレビューに依存する。`gradle-wrapper.properties` の diff は読めるので、URL の host と値を確認する
- 記録する時点ですでに不正な配布物。値は記録時の配布物を固定するだけで、配布物の安全性は保証しない
- ダウンロード済みの配布物。`GRADLE_USER_HOME` の中の配布物が改ざんされていても、値を壊していても検知しない
- `gradle-wrapper.jar`、`gradlew`。検証を実行する側である `gradle-wrapper.jar` が改ざんされていれば、検証そのものを信用できない
- ビルドが使うプラグインとライブラリ（テーマ 2 以降で扱う）

wrapper validation Action が守るもの:

- `gradle-wrapper.jar` が Gradle の公式リリースのどれとも一致しないこと
- 名前の見た目が `gradle-wrapper.jar` と同じ homoglyph のファイルを紛れ込ませること

wrapper validation Action が守らないもの:

- `gradle-wrapper.jar` と `distributionUrl` の版の一致。公式リリースのどれかに一致すれば通るので、古い公式の jar への差し替えは検知しない
- `gradlew`、`gradlew.bat`、`gradle-wrapper.properties`。[実装](https://github.com/gradle/actions/blob/v6.4.1/sources/src/wrapper-validation/find.ts)は `gradle-wrapper.jar` だけを対象にする
- 配布物
- CI より前の実行。レビュー前のブランチをローカルで `./gradlew` すると、検証の前に jar が実行される
- PR によるワークフローの変更。`pull_request` は PR 側のワークフローを実行するので、PR の中で検証の step を消せる。必須チェックとレビューの設定が別に必要になる（このタスクではリポジトリ設定を変更しない）
- `allow-checksums` と `allow-snapshots` による緩和。このリポジトリでは設定しない

## Wrapper の更新手順

1. 対象の版の配布物の SHA-256 を [Gradle の公式ページ](https://gradle.org/release-checksums/) で確認する

2. 確認した値を渡して wrapper タスクを 2 回実行する

   ```sh
   ./gradlew wrapper --gradle-version <version> --distribution-type bin --gradle-distribution-sha256-sum <sha256>
   ./gradlew wrapper --gradle-version <version> --distribution-type bin --gradle-distribution-sha256-sum <sha256>
   ```

   1 回目は実行中の古い Gradle が `gradle-wrapper.properties` だけを新しくする。`gradle-wrapper.jar` は実行中の Gradle のものなので変わらない。2 回目は新しい Gradle が実行され、配布物を `distributionSha256Sum` で検証したうえで `gradle-wrapper.jar` と `gradlew` を更新する。

   `distributionSha256Sum` が設定されていると、値を渡さずに版を変えようとしたときに wrapper タスクは失敗する。古い値が残ることはない。

3. `gradle-wrapper.jar` の SHA-256 を公式の値と比較する

   ```sh
   curl -fsSL https://services.gradle.org/distributions/gradle-<version>-wrapper.jar.sha256
   sha256sum gradle/wrapper/gradle-wrapper.jar
   ```

4. `./gradlew build` を実行する
5. PR で `gradle-wrapper.properties` の diff を確認し、CI の wrapper validation の成功を確認する

## Dependabot による更新

Dependabot は `package-ecosystem: gradle` で wrapper を更新できる。`dependabot.yml` の設定と運用はテーマ 2 で扱い、このリポジトリではまだ動かしていない。ここに書く内容は公式の情報と他のリポジトリの実例による。

- [GitHub Docs](https://docs.github.com/en/code-security/dependabot/ecosystems-supported-by-dependabot/supported-ecosystems-and-repositories) には、Dependabot が Gradle を実行して `gradle/wrapper/gradle-wrapper.properties`、`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` を更新すると書かれている
- 同じページに `distributionSha256Sum` への言及はないが、[dependabot-core の実装](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle/file_parser/distributions_finder.rb)は更新の対象にしている。`distributionSha256Sum` がある場合に限り、新しい値を `gradle-distribution-sha256-sum` として [wrapper タスクに渡す](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle/file_updater/wrapper/command_builder.rb)。設定がなければ追加しない
- 新しい値は [`distributionUrl` に `.sha256` を付けた URL から取得する](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle/package/distributions_fetcher.rb)
- Dependabot の wrapper タスクの実行は 1 回だが、先に `gradle-wrapper.properties` を新しい版に書き換えているので、新しい Gradle が実行されて `gradle-wrapper.jar` も新しくなる。手元で同じ順序を再現し、9.7.1 から 9.8.2 への更新で jar が 9.8.2 の公式の値になることを確認した
- 実例: [9.3.1 から 9.4.1 への更新](https://github.com/ViaVersion/ViaAprilFools/commit/5024475)では `distributionSha256Sum` と `gradle-wrapper.jar` が更新され、jar は 9.4.1 の公式の値と一致した。[9.5.0 から 9.8.2 への更新](https://github.com/jflo/besu-graphql-plugin/pull/4)も jar は 9.8.2 の公式の値と一致した。このリポジトリには `distributionSha256Sum` の設定がなく、更新後も追加されていない

Dependabot の更新でも、次の点は人が判断する。

- `distributionSha256Sum` の新しい値は配布物と同じ host から取得されるので、host が侵害された場合の独立した確認にはならない
- `gradle-wrapper.jar` の妥当性は PR の CI の wrapper validation で確認する

## 検証結果

ローカルでは `GRADLE_USER_HOME` を分けて確認した。環境は macOS、JBR 21.0.4、Gradle 9.7.1。

| 条件 | 結果 |
| --- | --- |
| 未ダウンロード、正しい値、`./gradlew --version` | 成功（配布物をダウンロードして実行） |
| 未ダウンロード、壊した値、`./gradlew build` | 失敗（exit 1、`Verification of Gradle distribution failed!`） |
| ダウンロード済み、壊した値、`./gradlew --version` | 成功（検証されない） |

壊した値で失敗したときは、ダウンロードした zip が残らず、`.ok` のマーカーも作られなかった。次の実行でも再度検証される。

```text
Exception in thread "main" java.lang.RuntimeException: Verification of Gradle distribution failed!

Your Gradle distribution may have been tampered with.
Expected checksum: '0000000000000000000000000000000000000000000000000000000000000000'
Actual checksum:   'acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a'
```

`distributionSha256Sum` の値は `https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256`、`gradle-wrapper.jar` の値は `https://services.gradle.org/distributions/gradle-9.7.1-wrapper.jar.sha256` と一致することを確認した。

GitHub Actions では、一時ブランチに確認用の変更を push して確認した。ブランチは確認後に削除した。

| 条件 | wrapper validation | 実行 |
| --- | --- | --- |
| `gradle-wrapper.jar` の末尾に 1 byte 追加 | 失敗。JDK のセットアップとビルドの step はスキップ | [run 41065550996](https://github.com/appachan/android-supply-chain-lab/actions/runs/41065550996) |
| 9.8.2 の公式の `gradle-wrapper.jar` に差し替え（`distributionUrl` は 9.7.1 のまま）、`gradlew` にコメントを追加 | 成功。ビルドも成功 | [run 41065666800](https://github.com/appachan/android-supply-chain-lab/actions/runs/41065666800) |

2 つ目の結果は、版の不一致と `gradlew` の変更を Action が検知しないことを示している。

```text
##[error]At least one Gradle Wrapper Jar failed validation!
✗ Found unknown Gradle Wrapper JAR files:
  da9f6ba5070e87b7abe25b56e5f6d004883a5399cefb8cc6512e2f53fe10b8c3 gradle/wrapper/gradle-wrapper.jar
```

main の CI のログでは、ビルドの step が毎回 `gradle-9.7.1-bin.zip` をダウンロードしていて、`distributionSha256Sum` の検証が実行される状態になっている。ただし、値を壊したときの失敗を確認したのはローカルだけで、CI では確認していない。

## Consequences

- 配布物と `gradle-wrapper.jar` の検証が CI で毎回実行される
- CI のキャッシュを導入するときは、`~/.gradle/wrapper` が復元されないことを確認する必要がある
- 開発者の環境ではダウンロード済みの配布物は再検証されない
- wrapper validation を必須チェックにしないと、失敗した PR を merge できる
