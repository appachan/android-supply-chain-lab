# 3. Version resolution

Date: 2026-10-30

## Status

Accepted

## Context

`gradle/libs.versions.toml` に書いた版と、推移的依存の影響で実際に解決される版はズレることがある。次の仮説を実験で確かめ、ズレを検知または固定する方式を選ぶ。

- version catalog は別名の定義で、制約ではない
- Gradle の既定の競合解決は最も高い版を選ぶので、ズレは上方向に起きる

結果として、仮説の前半は正しく、後半は既定の動作としては正しいが例外がある。

- catalog の項目を宣言しただけでは解決結果は変わらない（E1）
- 上方向のズレはライブラリでも plugin の classpath でも再現した（E2、E5）
- 下方向は `strictly`、`enforcedPlatform`、dependency locking のほか、利用者が何も書かなくても起きる。plugin の classpath では Gradle が `kotlin-stdlib` などを同梱の Kotlin の版に strict に固定している（E7）

## Decision

検知を基本にし、固定は例外にする。

1. `checkResolvedVersions` を `check` に組み込み、catalog の宣言と解決結果のズレで CI を失敗させる（[gradle/resolved-versions.gradle.kts](../../gradle/resolved-versions.gradle.kts)）
2. `failOnNonReproducibleResolution()` を全 configuration と plugin の classpath に設定し、動的な版と changing な版を禁止する
3. dependency locking は採用しない
4. `strictly`、`enforcedPlatform` は常用しない。推移的依存を上げる必要があるときは、catalog の項目と `constraints` を使う
5. ズレを検知したら、原因を確認して catalog を解決結果に合わせて上げる。上げても解決結果は変わらない。合わせられない理由があるときだけ固定を検討する

locking を採用しない主な理由は 3 つある。Dependabot が `buildscript-gradle.lockfile` を更新しないので AGP や Kotlin の更新 PR の CI が必ず失敗すること、catalog より高い lock は黙って catalog を上書きすること、`failOnDynamicVersions` と併用できないことである。推移的依存の変化を PR で見る役割は、テーマ 2 で main に入った `verification-metadata.xml` が担う（[論点 5](#論点-5-verification-metadataxml-との相互作用)）。この前提が崩れたら locking を再検討する。

## 論点 1: 再現

環境は macOS、JBR 21.0.4、Gradle 9.7.1、AGP 9.3.3。変更前のベースラインは 0605bc0 で、`checkResolvedVersions` を入れる前の構成で確認した。手順は[再現手順](#再現手順)に書く。

### ライブラリ

| 実験 | 変更 | 結果 |
| --- | --- | --- |
| E1 | catalog に `androidx-core-ktx`（1.13.1）を追加。依存には宣言しない | 解決結果は 1.18.0 のまま。競合の候補に 1.13.1 は現れない |
| E2 | E1 の項目を `implementation` に宣言 | `1.13.1 -> 1.18.0`。ビルドは成功し、警告もない |
| E3 | E2 の版を `{ strictly = "1.13.1" }` に変更 | 1.13.1 に下がる（`by ancestor`）。`dependencies` は成功するが `compileDebugKotlin` が失敗 |
| E4 | `androidx.tracing:tracing` に `strictly("1.0.0")` を宣言 | 1.2.0 から 1.0.0 に下がる。`build`（lint を含む）と R8 が成功 |

E2 の `dependencyInsight`:

```text
androidx.core:core-ktx:1.18.0
   Selection reasons:
      - By constraint
      - By conflict resolution: between versions 1.18.0, 1.13.1, 1.2.0 and 1.5.0

androidx.core:core-ktx:1.18.0
+--- androidx.activity:activity:1.13.0
...
```

E3 の失敗:

```text
Kotlin compiler: MISSING_DEPENDENCY_SUPERCLASS
  Cannot access 'androidx.core.app.PictureInPictureProvider' which is a supertype of 'com.github.appachan.supplychainlab.MainActivity'.
```

### plugin の classpath

`dependencyInsight` は buildscript の `classpath` を指定できない（`configuration 'classpath' not found`）。`DependencyInsightReportTask` を自分で登録すると指定できるので、`resolvedPluginInsight` として追加した。

| 実験 | 変更 | 結果 |
| --- | --- | --- |
| E5 | なし（現状） | `kotlin-gradle-plugin:2.2.10 -> 2.4.21`。AGP の要求は 2.2.10 で、2.4.21 は Compose Compiler plugin の依存 |
| E6 | catalog の `kotlin` を `2.2.0` に変更 | KGP、`compose-compiler-gradle-plugin`、`kotlin-compose-compiler-plugin-embeddable` がすべて 2.2.10。`compileDebugKotlin` は成功 |
| E7 | classpath に `kotlin-test:2.4.21` を追加 | `kotlin-stdlib:2.4.21 -> 2.4.0`。理由は `Pinned to the embedded Kotlin` |
| E8 | classpath に `kotlin-stdlib:2.4.21` を直接宣言 | 解決が失敗（`Cannot find a version ... that satisfies the version constraints`） |

E5 の `resolvedPluginInsight`:

```text
org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21
   Selection reasons:
      - By constraint
      - By conflict resolution: between versions 2.4.21 and 2.2.10

org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21
\--- org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21
     \--- org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:2.4.21
          \--- classpath

org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10 -> 2.4.21
\--- com.android.tools.build:gradle:9.3.3
```

E6 では marker の `org.jetbrains.kotlin.plugin.compose.gradle.plugin` だけが 2.2.0 のままで、実装は 2.2.10 になる。marker の版だけを見てもズレは見つからない。`kotlin` の版は KGP の版を直接決めておらず、KGP は Compose Compiler plugin の依存と AGP の依存の競合で決まる。

E7 の固定は Gradle が行う。[`EmbeddedKotlinProvider`](https://github.com/gradle/gradle/blob/882e8c01e179238c2b983203ea0e180cac5eb576/platforms/dsl/kotlin-dsl/src/main/kotlin/org/gradle/kotlin/dsl/support/EmbeddedKotlinProvider.kt#L41-L51) が `strictly` の制約を追加しており、Gradle 9.7.1 が同梱する Kotlin は [2.4.0](https://docs.gradle.org/9.7.1/userguide/upgrading_version_9.html) である。現状の classpath の `kotlin-stdlib` は 2.4.0 で、KGP 2.4.21 と同じ版ではない。

### 既定は最も高い版、ただし例外がある

[Gradle のドキュメント](https://docs.gradle.org/9.7.1/userguide/graph_resolution.html)は既定で最も高い版を選ぶと説明している。低い版が選ばれたのは次の場合である。

| 場合 | 確認 | 動作 |
| --- | --- | --- |
| `strictly` を宣言した | E3、E4 | [宣言した依存の `strictly` は版を下げられる](https://docs.gradle.org/9.7.1/userguide/dependency_versions.html) |
| `enforcedPlatform` | 古い BOM で `ui` が 1.8.2 になる | 実験 M6 |
| dependency locking | lock が catalog より低いとき | [lock は `strictly` と同様に扱われる](https://docs.gradle.org/9.7.1/userguide/dependency_locking.html)。実験 M5 |
| Gradle の Kotlin の固定 | E7 | plugin の classpath のみ。利用者の宣言は不要 |

なお同じ Gradle のドキュメントの[競合解決の節](https://docs.gradle.org/9.7.1/userguide/graph_resolution.html)には `strictly` が最も高い版より低いと解決が失敗すると書かれている。E3、E4 のとおり、project が直接宣言した `strictly` では失敗せず下がる。

## 論点 2: 方式の比較

ズレのない main の構成で失敗するものを誤検知として数えた。rss-reader はコピーで確認した。

| 方式 | 上方向のズレ | 下方向のズレ | 誤検知 | Android の variant と configuration | plugin の classpath |
| --- | --- | --- | --- | --- | --- |
| `strictly` | 固定（M1）。失敗はコンパイル時まで出ない | 引き起こす側 | なし | 依存ごとに宣言 | catalog の plugin では効かない。`classpath` に直接書くと解決が失敗 |
| `require` / `prefer` | 効果なし（M2） | 効果なし | なし | 依存ごとに宣言 | 効果なし |
| `failOnVersionConflict()` | 検知 | 検知しない | 多い。lab で `debugRuntimeClasspath` 44 module、plugin の classpath 18 module、rss-reader で 97 module | `configurations.configureEach` で全体に適用できるが、全部失敗する | `buildscript` の `configurations.classpath` に設定 |
| `failOnChangingVersions()` と `failOnDynamicVersions()` | 効果なし（M4） | 効果なし | なし（lab、rss-reader とも `build` 成功） | `configurations.configureEach` で全体に適用できる | 同左 |
| dependency locking | 変化を検知。lock 作成時に既にあるズレは記録されるだけ | catalog を lock より下げても黙って lock の版になる | なし | `lockAllConfigurations()` で全体に適用。lint、UTP、Kotlin compiler の configuration も lock され、lab で 303 行、rss-reader で計 4,739 行 | 別に宣言が必要。`buildscript-gradle.lockfile` が増える |
| BOM / `platform` | 効果なし。BOM を超えて上がる（M6） | 効果なし | なし | 宣言した configuration の範囲 | 効果なし |
| `enforcedPlatform` | 固定。ただしビルドが壊れた（M6） | 引き起こす側 | なし | 宣言した configuration の範囲 | 未確認 |
| 自前の検査 | 検知 | 検知 | なし。rss-reader の 1 件は実際のズレ | `*CompileClasspath`、`*RuntimeClasspath` を列挙 | marker の実装を比較 |

ビルド時間は、ウォームなデーモンで `./gradlew build --no-configuration-cache --no-build-cache --rerun-tasks` を 3 構成で交互に 6 回ずつ実行した。

| 構成 | タスク数 | 最小 | 中央値 | 最大 |
| --- | --- | --- | --- | --- |
| main | 95 | 22.3 秒 | 30.4 秒 | 47.4 秒 |
| 採用した方式 | 97 | 12.4 秒 | 19.2 秒 | 23.8 秒 |
| dependency locking | 95 | 26.7 秒 | 28.9 秒 | 41.8 秒 |

同じ構成の揺らぎが方式の差より大きいので、`build` 全体の差は判定できない。採用した方式が速く見えるのは方式の効果ではない。別の処理が同時に動いていた可能性があり、この数字で速さの順序は判断しない。

`checkResolvedVersions` 単体の時間は安定していた。ウォームなデーモンで lab は 5 回中 1 回目が 2.05 秒、以降は約 0.7 秒、rss-reader のコピーは約 1 秒だった。`build` に比べて小さく、CI のコストとしては無視できる。

`failOnVersionConflict()` は失敗するので `build` の時間を測っていない。`strictly`、`require`、`prefer`、BOM は解決の入力が変わるだけなので測っていない。

### 実験の詳細

M1（`strictly`）: E3 のほかに plugin で 2 つ試した。catalog の `kotlin` を `{ strictly = "2.2.0" }` にすると、解決は成功し KGP は 2.2.10 のままで、効果がない。`classpath("org.jetbrains.kotlin:kotlin-gradle-plugin") { version { strictly("2.2.0") } }` は、`kotlin-gradle-plugins-bom:2.2.10` の制約と衝突して解決が失敗する。

M2（`require` / `prefer`）: `core-ktx` の結果は次のとおり。下がったのは `strictly` だけである。

| 宣言 | 解決結果 |
| --- | --- |
| `{ prefer = "1.13.1" }` | 1.18.0 |
| `{ require = "1.13.1" }` | 1.18.0 |
| `{ require = "[1.13,2.0[", prefer = "1.13.1" }` | 1.18.0 |
| `{ strictly = "[1.13,1.17]", prefer = "1.13.1" }` | 1.13.1 |

M3（`failOnVersionConflict()`）: 利用者の宣言に関係なく、推移的依存どうしの競合でも失敗する。plugin の classpath では `guava` や `asm` のように catalog に関係のない module が並ぶ。`dependencyInsight` と `dependencies` は `FAILED` の表示や競合の情報を出すが exit 0 で終わる。

M4（`failOnNonReproducibleResolution()`）: [`failOnDynamicVersions()` と `failOnChangingVersions()` を合わせたもの](https://docs.gradle.org/9.7.1/javadoc/org/gradle/api/artifacts/ResolutionStrategy.html)。`core-ktx:1.+` と `isChanging = true` はどちらも `Resolution strategy disallows usage of ...` で失敗した。E2 のズレには反応しない。

M5（dependency locking）:

- `./gradlew dependencies :app:dependencies --write-locks` で `app/gradle.lockfile`、`buildscript-gradle.lockfile`、`settings-gradle.lockfile` が作られる
- catalog を上げると lock の `strictly` と衝突して解決が失敗する（`Dependency version enforced by Dependency Locking`）。AGP の更新も同様で、どちらも PR の CI で検知できる
- catalog を下げると失敗しない。`activity` を lock の 1.12.4 より低い 1.12.0 にすると `1.12.0 -> 1.12.4` になり、ビルドは成功する。[ドキュメントどおりの動作](https://docs.gradle.org/9.7.1/userguide/dependency_locking.html)だが、catalog の値がそのまま嘘になる
- `activity` を 1.12.4 から 1.13.0 に上げたときの lock の diff には `navigationevent` の 1.0.2 から 1.0.0 への変化が現れた。推移的依存の変化が見えるのは locking の利点である
- `failOnDynamicVersions` と同時に使うと `Both dependency locking and fail on dynamic versions are enabled. You must choose between the two modes.` で失敗する

M6（BOM / `platform`）: Compose BOM を 2025.06.00 にする。`platform` では BOM の `ui` 1.8.2 を超えて 1.9.2 に解決された。BOM は `activity-compose` 1.13.0 の要求を止めない。`enforcedPlatform` では 1.8.2 に固定されるが、`runtime-annotation` が 1.9.0 のまま残り `assembleDebug` が重複クラスで失敗した。

```text
Duplicate class androidx.compose.runtime.StableMarker found in modules runtime-annotation.aar (androidx.compose.runtime:runtime-annotation-android:1.9.0) and runtime-release.aar (androidx.compose.runtime:runtime-android:1.8.2)
```

[`enforcedPlatform`](https://docs.gradle.org/9.7.1/userguide/platforms.html) は依存グラフの版を上書きするので注意が必要とされている。[Compose BOM](https://developer.android.com/develop/ui/compose/bom) は版の対応表で、個別のライブラリに版を指定して上書きできる。

### 自前の検査

`checkResolvedVersions` はグラフだけを解決し、次の 3 種類の不一致で失敗する。

- project が直接宣言した依存の解決結果と catalog の版の不一致
- catalog に載っている platform（BOM）の制約の版と解決結果の不一致
- catalog の plugin の marker が要求する実装の版と解決結果の不一致

解決できなかった依存も失敗として報告する。

最初の実装は graph のすべての module を catalog と比べていた。lab では問題がなかったが、rss-reader のコピーでは `core-ktx declared 1.18.0, resolved 1.2.0` のような誤った報告が 12 種類出た。ある module が直接宣言していない catalog の項目が、推移的に低い版で見えているだけで、最終的な app の classpath には関係しない。比較を直接の宣言に絞ったところ、実際のズレ 1 件だけが残った。

検査が見るのは `*CompileClasspath` と `*RuntimeClasspath` で、debug と release の差も分かる。

| シナリオ | 変更 | 結果 |
| --- | --- | --- |
| S0 | なし | 成功 |
| S1 | 直接の依存を推移的依存より低くする（`core-ktx` 1.13.1） | 失敗。`androidx.core:core-ktx: declared 1.13.1, resolved 1.18.0` |
| S2 | catalog に足すが依存に宣言しない | 成功（対象外） |
| S3 | `kotlin = "2.2.0"` | 失敗。`compose-compiler-gradle-plugin: requested 2.2.0 by ...compose.gradle.plugin, resolved 2.2.10` |
| S4 | catalog は 1.18.0、build script で `strictly("1.13.1")` | 失敗。`declared 1.18.0, resolved 1.13.1`（下方向） |
| S5 | Compose BOM を 2025.06.00 に下げる | 失敗。`requested 1.8.2 by androidx.compose:compose-bom, resolved 1.9.2` が 30 行 |
| S6 | `implementation` は 1.3.0、`debugImplementation` は 1.4.0 | 失敗。報告は debug の configuration だけで、release は含まない |
| S7 | catalog の `tracing` 1.3.0 を `constraints` で宣言 | 成功。推移的依存が 1.3.0 に上がる |
| S8 | build script に `core-ktx:1.13.1` を直接書く | 成功（対象外） |
| S9 | catalog を `1.+` にする | 失敗。`not resolved`（`failOnNonReproducibleResolution` による） |
| S1 の修正 | S1 の catalog を 1.18.0 に上げる | 成功 |

検査は configuration cache に対応している。2 回目は `Configuration cache entry reused.` で動作した。

検査の限界:

- catalog を通さず build script に直接書いた版（S8）
- catalog に宣言していない推移的依存の変化
- 依存として宣言されていない catalog の項目（S2）。依存に影響しないので害はない
- settings の classpath、`build-logic` の classpath、detached configuration

## 論点 3: 検知と固定の使い分け

検知して人が判断する場面:

- 依存の更新で別の依存が上がったとき。上がった版を確認し、catalog を合わせる
- BOM を超えて上がったとき。意図した組み合わせかを確認する
- plugin の classpath のズレ。Kotlin と Compose Compiler のように組み合わせに制約があるものは、catalog の値と実際の版が違うままにしない

固定する場面:

- 脆弱性や不具合のために推移的依存を上げるとき。下げる方向の固定ではないので `strictly` は要らない。catalog に項目を足して `constraints` で宣言する（S7）。検査の対象になり、Dependabot も更新できる

  ```kotlin
  dependencies {
      constraints {
          implementation(libs.androidx.tracing) { because("CVE-XXXX-XXXXX") }
      }
  }
  ```

- 特定の版に既知の問題があり、上がることを止める必要があるとき。これだけが `strictly` の用途である。理由を `because` に書き、解除の条件を決める

推移的依存の要求より低い版に固定する危険性:

- 失敗が保証されない。E3 はコンパイルで失敗したが、E4 は lint と R8 まで成功した
- E4 の `tracing` 1.0.0 は、`activity` など今回の app の依存が呼ぶメソッドをすべて持っていた。一方で Gradle cache の中の `androidx.test:monitor:1.8.0` は 1.0.0 にない `Trace.forceEnableAppTracing()` を呼んでいた。この依存を使うテストでは、ビルドが通ったまま実行時に `NoSuchMethodError` になり得る。実行はしていない
- 一式で揃える必要があるライブラリが割れる。S4 では `core-ktx` を 1.13.1 にしても `core` は 1.16.0 に解決された
- 上方向の修正も止める。`strictly` を置いた module は、Dependabot の対象からも外れる（論点 4）
- 解決される版が変わるので、`verification-metadata.xml` の更新が必要になる（論点 5）

## 論点 4: Dependabot との相性

GitHub Docs は Gradle で [`build.gradle(.kts)`、`gradle/libs.versions.toml`、`gradle.lockfile` を更新できる](https://docs.github.com/en/code-security/dependabot/ecosystems-supported-by-dependabot/supported-ecosystems-and-repositories)と説明している。実装は [dependabot-core の fdbe8b3](https://github.com/dependabot/dependabot-core/tree/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle) で確認した。

| 方式 | Dependabot が更新できるか | PR の内容 |
| --- | --- | --- |
| 通常の版、`version.ref` | できる | catalog の 1 行 |
| rich version（`strictly`、`require`、`prefer`） | できない。[`dependencies_for_declarations`](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle/file_parser.rb#L335-L337) が文字列と `ref` 以外を除外する。`[versions]` 側に rich version を書いた場合も[テストで除外が確認されている](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/spec/dependabot/gradle/file_parser_spec.rb#L1232-L1250) | PR が来ない |
| BOM | できる。BOM の項目だけが対象。版のない `material3` などは[除外される](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle/file_parser.rb#L366) | BOM の 1 行。中の module の変化は PR に現れない |
| `constraints` と catalog | できる | catalog の 1 行 |
| `gradle.lockfile` | できる。GitHub Docs には Gradle を実行せずに更新できるとあるが、実装は root と subproject の `gradle.lockfile` を取得し、[`dependabotResolveAll --write-locks`](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle/file_updater/lockfile_updater.rb#L59-L65) を実行する | catalog と lockfile |
| `buildscript-gradle.lockfile`、`settings-gradle.lockfile` | できない。取得するのは [`gradle.lockfile` だけ](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle/file_fetcher.rb#L19) | 含まれない |
| `failOnVersionConflict()`、`failOnNonReproducibleResolution()`、自前の検査 | 関係しない。Dependabot は catalog を書き換えるだけで、グラフを解決しない | catalog の 1 行。結果は PR の CI に出る |

Dependabot が `gradle.lockfile` を更新する処理を手元で再現した。[実装の init script](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle/file_updater/lockfile_updater.rb#L308-L335) を取り出して同じ引数で実行した結果である。実際の Dependabot の実行ではない。

| 操作 | 結果 |
| --- | --- |
| `activity` を 1.12.4 から 1.13.0 に更新して実行、`ANDROID_HOME` なし | 成功。`app/gradle.lockfile` が更新され、続く `build` も成功 |
| AGP を 9.3.2 から 9.3.3 に更新して実行 | 成功。`app/gradle.lockfile` と `buildscript-gradle.lockfile` が変わる |
| 上の結果のうち `gradle.lockfile` だけを残す（PR に載る内容） | `configuring root project` で解決が失敗。AGP と Kotlin の更新 PR は必ず CI が失敗する |

Dependabot の lockfile の更新が失敗しても、[エラーを log に出して元のファイルを返す](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle/file_updater/lockfile_updater.rb#L74-L76)ので、PR は古い lockfile のまま作られる可能性がある。

採用した方式への影響:

- 依存の更新で別の catalog の項目を超えて上がると、Dependabot の PR の CI が検査で失敗する。これは意図した動作で、人が判断する合図になる。同じ PR で catalog を合わせる
- 同じ `version.ref` を共有する項目は 1 行の変更で揃うが、別の `[versions]` の項目が連動して上がる場合は PR が分かれて、片方の CI が失敗する。`dependabot.yml` の `groups` で束ねられる可能性があるが、テーマ 2 の範囲なので確認していない
- Dependabot の wrapper の更新は build file を[空にして実行する](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle/file_updater/wrapper_updater.rb#L224-L232)ので、`apply(from = ...)` は影響しない

未確認の点:

- 実際の Dependabot の PR で上の動作になるか
- `groups` で連動する更新を束ねられるか
- `rootProject.file(...)` を使う `apply` が Dependabot の解析で問題にならないか。[取り込みの正規表現](https://github.com/dependabot/dependabot-core/blob/fdbe8b342118a62ac97da47e824666df11046fec/gradle/lib/dependabot/gradle/file_parser.rb#L75)は文字列リテラルだけを対象にするので、無視されると読んでいる

Dependabot の security updates は Gradle では dependency submission API の利用が前提で、[推移的依存の alert では PR を作らない](https://docs.github.com/en/code-security/dependabot/ecosystems-supported-by-dependabot/supported-ecosystems-and-repositories)。推移的依存の修正は `constraints` を人が足す前提になる。

## 論点 5: verification-metadata.xml との相互作用

`verification-metadata.xml` はこのブランチでは変更していない。テーマ 2 は作業中に main へ入った（b13cccb）。このブランチの d8b7f5f と main を一時的な clone で merge すると競合はなく、`./gradlew build` は 97 タスクで成功した。下の表の上 3 行は `sha256` で生成した clone の結果で、下 3 行は merge した clone の結果である。

| 確認 | 結果 |
| --- | --- |
| `--write-verification-metadata sha256 build` で生成し、`checkResolvedVersions build` を実行 | 成功。448 component |
| `checkResolvedVersions` を除いて生成し、検査を実行 | 成功。component 数も同じ 448。[生成は全 configuration を解決する](https://docs.gradle.org/9.7.1/userguide/dependency_verification.html)ので、検査の追加で記録すべきものは増えない |
| 生成後に `tracing` を `strictly("1.0.0")` に固定して `compileDebugKotlin` | 失敗。`tracing-1.0.0.aar` と `annotation-1.1.0.pom` が未記録 |
| catalog の `activity` を未記録の 1.12.4 に変更して `checkResolvedVersions` | 成功。検査はグラフだけを解決するので、検証が実行されない。続く `build` は `Dependency verification failed` で失敗 |
| 同じ変更で `--write-verification-metadata pgp,sha256 --export-keys build`（main の再生成ジョブのコマンド） | 成功。`verification-metadata.xml` が 11 行増え、続く `build` も成功 |
| ズレのある変更（S1）で同じコマンド | 失敗（exit 1）。検査がズレを報告する。手元では `verification-metadata.xml` は更新されていた |

統合で注意する点:

- 検査の成功は検証の成功を意味しない。検査の後に成果物を解決する `build` で検証が実行される。CI で `checkResolvedVersions` だけを実行する構成にしない
- 解決結果を変える方式（`strictly`、`enforcedPlatform`、`constraints`、locking の lock 更新）を使うと `verification-metadata.xml` の更新が必要になる。catalog を解決結果に合わせるだけの修正では解決結果が変わらないので、更新は不要
- 低い版への固定は、`verification-metadata.xml` に古い版が記録されていると検証を通る。`tracing` の 1.0.0 と 1.2.0 を記録した状態で 1.0.0 に固定すると成功した。検証は版の妥当性を判断しない
- 依存の更新 PR で推移的依存の変化が見えるのは `verification-metadata.xml` の diff である。この前提で locking を不採用にしている。`verification-metadata.xml` をやめるときは locking を再検討する
- main の `regenerate-verification-metadata.yml` は `build` を実行するので、merge すると検査も含まれる。ズレのある Dependabot PR では再生成のコマンドが失敗し、後続の `upload-artifact` の step は実行されないはずである。人が catalog を直すと PR の更新で再度動く想定だが、Actions の上では確認していない
- 再生成とズレの検知を分けたい場合は、再生成のコマンドに `-x checkResolvedVersions` を足せばよい。検査を除いても記録される component は同じ（448）で、ズレは通常の CI で検知される。ファイルはテーマ 2 の範囲なので、このブランチでは変更していない
- Dependabot 自身が `verification-metadata.xml` を更新する実装は dependabot-core の Gradle の `lib` に見当たらなかった（`verification-metadata` の検索で 0 件）。main の再生成ジョブはこれを補うものである

## 運用手順

ズレで CI が失敗したとき:

1. 失敗のメッセージで module と configuration を確認する
2. 原因を調べる

   ```sh
   ./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency <group:name>
   ./gradlew resolvedPluginInsight --dependency <group:name>
   ```

3. 問題がなければ、`gradle/libs.versions.toml` を解決結果の版に上げる。plugin の場合は共有している `version.ref` の値を確認する
4. 問題があれば、上げた依存の更新を見送るか、[論点 3](#論点-3-検知と固定の使い分け) に従って固定する

推移的依存を上げるときは、catalog に項目を足して `constraints` で宣言する。`constraints` を外すときは catalog の項目も消す。

新しい module を追加したときは追加の作業はない。`allprojects` で全 project に適用される。

## rss-reader に適用するときの差分

コピーで確認した。元のリポジトリは変更していない。

1. `gradle/resolved-versions.gradle.kts` をコピーする
2. `build.gradle.kts` に追加する

   ```diff
   +buildscript {
   +    configurations.classpath { resolutionStrategy.failOnNonReproducibleResolution() }
   +}
   +
    plugins {
        alias(libs.plugins.android.application) apply false
        ...
    }
   +
   +allprojects { apply(from = rootProject.file("gradle/resolved-versions.gradle.kts")) }
   ```

3. 検査で見つかったズレを直す

   ```diff
   -junitVintage = "5.10.2"
   +junitVintage = "5.12.2"
   ```

   `mockk` 1.14.3 が `junit-jupiter` 5.12.2 を経由して `junit-bom` 5.12.2 を要求し、`junit-vintage-engine` を上げている。7 module の `debugUnitTestRuntimeClasspath` で確認した。テストはすでに 5.12.2 で動いているので、解決結果は変わらない

4. CI に step を足す。rss-reader の CI は `build` ではなく個別のタスクを実行しているので、`check` に組み込むだけでは実行されない

   ```diff
   +      - name: Resolved versions
   +        if: ${{ !cancelled() }}
   +        run: ./gradlew checkResolvedVersions
   ```

確認の結果:

- `./gradlew checkResolvedVersions` は差分 3 の前に上記の 1 件で失敗し、差分 3 の後に成功した
- `./gradlew build --continue -x test` が成功した（743 タスク）。`failOnNonReproducibleResolution` による失敗はない
- plugin の classpath は root の `plugins` に `apply false` で集まっているので検査できる。`rssreader.*` の convention plugin の classpath である `build-logic` と、settings の `foojay-resolver-convention` は検査の対象外
- CI の `setup-gradle` は rss-reader の判断であり、この差分では変更しない

## 再現手順

ベースラインと検査の確認:

```sh
./gradlew checkResolvedVersions
./gradlew buildEnvironment
./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency androidx.core:core-ktx
./gradlew resolvedPluginInsight --dependency org.jetbrains.kotlin:kotlin-gradle-plugin
./gradlew resolvedPluginInsight --dependency org.jetbrains.kotlin:kotlin-stdlib
```

ライブラリのズレ（S1）:

```diff
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
 androidxActivity = "1.13.0"
+androidxCore = "1.13.1"
 androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "androidxActivity" }
+androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidxCore" }
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
     implementation(libs.androidx.activity.compose)
+    implementation(libs.androidx.core.ktx)
```

plugin の classpath のズレ（S3）:

```diff
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
-kotlin = "2.4.21"
+kotlin = "2.2.0"
```

どちらも `./gradlew checkResolvedVersions` が失敗する。

GitHub Actions では、`ci.yml` の trigger に一時ブランチを足すコミットを d8b7f5f の上に載せて確認した。ブランチは確認後に削除した。`ci.yml` は変更していない。`build` が検査を含むからである。

| 条件 | 結果 | 実行 |
| --- | --- | --- |
| ズレなし | 成功。`:checkResolvedVersions` と `:app:checkResolvedVersions` が実行された | [run 41075091703](https://github.com/appachan/android-supply-chain-lab/actions/runs/41075091703) |
| S1 | 失敗。`:app:checkResolvedVersions FAILED`、`androidx.core:core-ktx: declared 1.13.1, resolved 1.18.0` | [run 41075099381](https://github.com/appachan/android-supply-chain-lab/actions/runs/41075099381) |
| S3 | 失敗。`:checkResolvedVersions FAILED`、`compose-compiler-gradle-plugin: requested 2.2.0 ..., resolved 2.2.10` | [run 41075106953](https://github.com/appachan/android-supply-chain-lab/actions/runs/41075106953) |

## Consequences

- catalog の値と実際に使う版の不一致が、ライブラリ、BOM、plugin のどれでも CI で止まる
- 動的な版と changing な版は使えない。SNAPSHOT を使う検証は一時的に設定を外す必要がある
- 検査は catalog に宣言した範囲だけを見る。推移的依存の変化は `verification-metadata.xml` に依存する
- `apply(from = ...)` のスクリプトなので convention plugin より型の支援が弱いが、`build-logic` を持たないこのリポジトリと、持っている rss-reader の両方に同じファイルで適用できる
- Gradle の embedded Kotlin の更新で plugin の classpath の `kotlin-stdlib` が変わることは検査しない
