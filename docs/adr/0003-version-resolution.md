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

検査の目的は、意図しない版が使われないことと、ビルドした時期で版が変わらないことである。自然なアプローチを優先し、それで解決できないときだけ追加の仕組みを許容する。

1. 時期による変化は `failOnNonReproducibleResolution()` で防ぐ
2. 決定的だが意図しない版は `gradle.lockfile` で止めて、PR の差分で確認する。対象は subproject の `*CompileClasspath` と `*RuntimeClasspath` である。lock と `failOn` は同じ configuration で併用できないので、それ以外の configuration と root の plugin の classpath に `failOnNonReproducibleResolution()` を設定する
3. 特定の module を上げたくないときは、`constraints` の `reject` を使う。`strictly`、`enforcedPlatform` は常用しない。推移的依存を上げるときは catalog の項目と `constraints` を使う
4. 自作の `checkResolvedVersions` は廃止する。dependency-guard は採用しない
5. `buildscript-gradle.lockfile` は使わない。plugin の classpath の推移的依存の変化は PR で見えないが、許容する

最初の決定は、自作の検査を基本にし、locking を採用しないというものだった。その前提は誤りだったので見直した。経緯と比較は[論点 6](#論点-6-既存の手段との比較と見直し)に書く。以下の論点 1 から 5 の実験は記録として残す。

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
| `failOnChangingVersions()` と `failOnDynamicVersions()` | 効果なし（M4） | 効果なし | なし（lab、rss-reader とも `build` 成功）。ただし結果が変わらない組み合わせでも失敗する場合がある（M7） | `configurations.configureEach` で全体に適用できる | 同左 |
| dependency locking | 変化を検知。lock 作成時に既にあるズレは記録されるだけ | catalog を lock より下げても黙って lock の版になる | なし | `lockAllConfigurations()` で全体に適用。lint、UTP、Kotlin compiler の configuration も lock され、lab で 303 行、rss-reader で計 4,739 行 | 別に宣言が必要。`buildscript-gradle.lockfile` が増える |
| BOM / `platform` | 効果なし。BOM を超えて上がる（M6） | 効果なし | なし | 宣言した configuration の範囲 | 効果なし |
| `enforcedPlatform` | 固定。ただしビルドが壊れた（M6） | 引き起こす側 | なし | 宣言した configuration の範囲 | 未確認 |
| 自前の検査（廃止） | 検知 | 検知 | なし。rss-reader の 1 件は実際のズレ | `*CompileClasspath`、`*RuntimeClasspath` を列挙 | marker の実装を比較 |

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
- `failOnDynamicVersions` と同時に使うと `Both dependency locking and fail on dynamic versions are enabled. You must choose between the two modes.` で失敗する。`failOnChangingVersions` も同様に `Both dependency locking and fail on changing versions are enabled.` で失敗する（[論点 6](#論点-6-既存の手段との比較と見直し)）

M6（BOM / `platform`）: Compose BOM を 2025.06.00 にする。`platform` では BOM の `ui` 1.8.2 を超えて 1.9.2 に解決された。BOM は `activity-compose` 1.13.0 の要求を止めない。`enforcedPlatform` では 1.8.2 に固定されるが、`runtime-annotation` が 1.9.0 のまま残り `assembleDebug` が重複クラスで失敗した。

```text
Duplicate class androidx.compose.runtime.StableMarker found in modules runtime-annotation.aar (androidx.compose.runtime:runtime-annotation-android:1.9.0) and runtime-release.aar (androidx.compose.runtime:runtime-android:1.8.2)
```

[`enforcedPlatform`](https://docs.gradle.org/9.7.1/userguide/platforms.html) は依存グラフの版を上書きするので注意が必要とされている。[Compose BOM](https://developer.android.com/develop/ui/compose/bom) は版の対応表で、個別のライブラリに版を指定して上書きできる。

### failOnNonReproducibleResolution() と推移的依存

M7: 結論として、推移的依存にも効く。POM と Gradle Module Metadata（GMM）のどちらでも、ライブラリと root の plugin の classpath のどちらでも同じ結果だった。設定した configuration のグラフの中では、結果が変わり得るのに通過した組み合わせはなかった。すり抜けるのは、設定が届かない configuration である。

[`failOnNonReproducibleResolution()` の Javadoc](https://docs.gradle.org/9.7.1/javadoc/org/gradle/api/artifacts/ResolutionStrategy.html#failOnNonReproducibleResolution()) は「resolution result」としか書いていないが、[`failOnDynamicVersions()` の Javadoc](https://docs.gradle.org/9.7.1/javadoc/org/gradle/api/artifacts/ResolutionStrategy.html#failOnDynamicVersions()) には判定の条件がある。

> if the resolved dependency graph contains a module and that the versions participating in the selection of that module contain at least one dynamic version, then resolution will fail if the resolution result can change because of this version selector.

判定の対象は宣言ではなくグラフの module である。これは推移的依存が含まれることと、後述の項目 4、5 が通ることの説明になる。ただし推移的依存とは明記されていないので、下の実験で確かめた。[`failOnChangingVersions()` の Javadoc](https://docs.gradle.org/9.7.1/javadoc/org/gradle/api/artifacts/ResolutionStrategy.html#failOnChangingVersions()) は「no changing version participates in resolution」とだけ書いている。

実装は [`DependencyGraphBuilder` の `validateDynamicSelectors`](https://github.com/gradle/gradle/blob/92f0512e7f06d84621afba191f75e265363890cf/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/ivyservice/resolveengine/graph/builder/DependencyGraphBuilder.java#L429-L464) である。selector がすべて動的なら失敗し、一部が動的なら、固定の selector が選択結果を受け入れ、かつ動的な selector がすべて `canBeStable` のときだけ通す。ソースのコメントでは、`canBeStable` になるのは現在は範囲だけである。Gradle の統合テストにも[推移的依存の場合](https://github.com/gradle/gradle/blob/92f0512e7f06d84621afba191f75e265363890cf/platforms/software/dependency-management/src/integTest/groovy/org/gradle/integtests/resolve/reproducibility/FailOnDynamicVersionsResolveIntegrationTest.groovy#L101-L131)がある。

実験の条件:

- scratchpad に lab（763eebb）のコピーを作り、`python -m http.server` で偽の Maven リポジトリを配信した。`maven-metadata.xml` と、SNAPSHOT は unique な版を置いた。file リポジトリではなく HTTP にしたのは、動的な版のキャッシュを実際の動作に合わせるためである
- 直接依存 `d:1.0` が推移的依存 `t` を指定する。`t` の版は 1.0、1.2、1.5、1.9、2.5。GMM の場合は POM に依存を書かず、GMM が読まれたことを結果で確かめた
- plugin の classpath は、marker の POM から `d` を経由して `t` に至る fake plugin を root の `plugins` に `apply false` で宣言した
- 1 項目につき POM と GMM、ライブラリと plugin の 4 通り（GMM だけの項目は 2 通り）、計 80 通りを実行した
- 確認したのは、検査なし（`failOnNonReproducibleResolution()` を外す）の解決結果、`checkResolvedVersions`、`incoming.files` による解決である。項目内の結果は同じだったので、下の表は 1 行にまとめた
- 実行後に `t` の 1.9.5 と 3.0（SNAPSHOT は build 2）を公開し、同じ入力で再度解決した。「キャッシュあり」はそのまま、「キャッシュなし」は `caches/modules-2` を消してデーモンを止めた状態で、CI のコールドスタートに相当する

| 項目 | 指定 | 検査なしの版（初回、キャッシュあり、キャッシュなし） | 判定 | 失敗のメッセージ |
| --- | --- | --- | --- | --- |
| 1 | `[1.0,2.0)` | 1.9、1.9、1.9.5 | 失敗 | `Could not resolve t:[1.0,2.0): Resolution strategy disallows usage of dynamic versions` |
| 1 | `[1.0,2.0)` と prefer 1.5（GMM のみ） | 1.5、1.5、1.5 | 失敗（結果は変わらない） | `Could not resolve t:{require [1.0,2.0); prefer 1.5}: ...dynamic versions` |
| 2 | `1.+` | 1.9、1.9、1.9.5 | 失敗 | `Could not resolve t:1.+: ...dynamic versions` |
| 2 | POM の `LATEST`、GMM の `latest.integration` | 2.5、2.5、3.0 | 失敗 | `Could not resolve t:latest.integration: ...dynamic versions`（POM の `LATEST` もこの表記になる） |
| 2 | POM の `RELEASE`、GMM の `latest.release` | 2.5、2.5、3.0 | 失敗 | `Could not resolve t:latest.release: ...dynamic versions` |
| 2 | GMM に `LATEST` をそのまま書く | 解決できない | 失敗（設定に関係なく） | `Could not find t:LATEST.`。GMM では動的な版として扱われない |
| 3 | `1.0-SNAPSHOT` | build 1、build 1、build 2 | 失敗 | `Could not resolve t:1.0-SNAPSHOT: Resolution strategy disallows usage of changing versions` |
| 3 | `1.0-SNAPSHOT`、直接 2.5 | 2.5、2.5、2.5 | 通過 | なし |
| 4 | `[1.0,2.0)`、直接 2.5（範囲より上） | 2.5、2.5、2.5 | 通過 | なし |
| 4 | `[1.0,2.0)`、直接 1.9（範囲内の最高） | 1.9、1.9、1.9 | 通過 | なし |
| 4 | `[1.0,2.0)`、直接 1.5（範囲内） | 1.5、1.5、1.5 | 通過 | なし |
| 4 | `[1.2,2.0)`、直接 1.0（範囲より下） | 1.9、1.9、1.9.5 | 失敗 | `Could not resolve t:1.0: ...dynamic versions`。固定の版が範囲に負けて、結果が時期で変わる |
| 4 | `1.+`、直接 2.5 | 2.5、2.5、2.5 | 失敗（結果は変わらない） | `t:2.5` と `t:1.+` の両方が `...dynamic versions` |
| 5 | `[1.0,2.0)`、`constraints` 1.5 | 1.5、1.5、1.5 | 通過 | なし |
| 5 | `[1.0,2.0)`、`constraints` 2.5 | 2.5、2.5、2.5 | 通過 | なし |
| 5 | `[1.0,2.0)`、`constraints` の `strictly` 1.5 | 1.5、1.5、1.5 | 通過 | なし |
| 5 | `[1.0,2.0)`、直接の `strictly` 1.5 | 1.5、1.5、1.5 | 通過 | なし |
| 5 | `1.+`、`constraints` の `strictly` 1.5 | 1.5、1.5、1.5 | 通過 | なし |
| 5 | `[1.0,2.0)`、`force` 1.5 | 1.5、1.5、1.5 | 通過 | なし |
| 5 | `1.+`、`force` 1.5 | 1.5、1.5、1.5 | 通過 | なし |
| 5 | `1.+`、`eachDependency` の `useVersion` 1.5 | 1.5、1.5、1.5 | 通過 | なし |

分かったこと:

- 判定は時期で変わらなかった。全 80 通りで、新しい版の公開の前後、キャッシュの有無にかかわらず同じだった。通過した組み合わせは版も変わらなかった
- 通過するのは、固定の selector が結果を決めている場合だけである。固定の版が範囲内にあれば、範囲の最高の版より低くてもその版が選ばれる
- 結果が変わらないのに失敗するものが 2 種類ある。高い固定の版がある `1.+` と、prefer 付きの範囲である。安全側の失敗である
- `1.+` が `strictly` で通るのは、祖先に strict があると [`EdgeState`](https://github.com/gradle/gradle/blob/92f0512e7f06d84621afba191f75e265363890cf/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/ivyservice/resolveengine/graph/builder/EdgeState.java#L104) がその edge の版を無視するからである
- 失敗は実際のビルドでも起きる。項目 1 の POM とライブラリの組み合わせで `:app:assembleDebug` は同じメッセージで失敗し、`checkResolvedVersions` は `not resolved` と報告した。root の plugin の classpath の場合は configuration の段階で失敗するので、`help` を含むすべてのタスクが動かない
- `dependencies` タスクは `t:[1.0,2.0) FAILED` と表示するが exit 0 で終わるので、検知には使えない
- キャッシュがあると新しい版は反映されない。[既定で 24 時間キャッシュされる](https://docs.gradle.org/9.7.1/userguide/dependency_caching.html)ためで、キャッシュのない CI と手元で結果が分かれる原因になる

設定が届かない経路では、推移的依存の動的な版はすり抜ける。範囲 `[1.0,2.0)` を指定した同じ構成を次の経路で使い、POM と GMM で確認した。

| 経路 | 採用した設定のまま | 版（初回、キャッシュあり、キャッシュなし） | 対策 | 対策後 |
| --- | --- | --- | --- | --- |
| subproject の `plugins` に版付きで書いた plugin | 通過 | 1.9、1.9、1.9.5 | subproject ごとに `project.buildscript.configurations.configureEach { resolutionStrategy.failOnNonReproducibleResolution() }` | 失敗 |
| `settings.gradle.kts` の `plugins` | 通過 | 1.9、1.9、1.9.5 | `settings.gradle.kts` に `buildscript { configurations.all { resolutionStrategy.failOnNonReproducibleResolution() } }` | 失敗 |
| included build（`build-logic`）の依存 | 通過 | 1.9、1.9、1.9.5 | included build の project に `configurations.configureEach { resolutionStrategy.failOnNonReproducibleResolution() }` | 失敗 |

この対策は当時の `apply(from = ...)` のスクリプトで確認した。スクリプトの中の `buildscript` はスクリプト自身の handler を指すので、`project.buildscript` が必要である。`buildscript` のままでは通過した。

対策と運用への影響:

- lab には 3 経路のどれもないので、コードは変更しない。使い始めるときに上の設定を足す
- rss-reader には settings の plugin と `build-logic` があるので、[適用の差分](#rss-reader-に適用するときの差分)に含める
- ライブラリが動的な版の依存を公開すると、その更新の PR の CI が失敗する。範囲なら、catalog の項目と `constraints` で範囲内の版を宣言すると通る。既存の手順と同じである。宣言する版は、更新前に解決されていた版を基準に選ぶ
- `1.+`、`latest.release` などは `constraints` の通常の宣言では通らないはずである（`1.+` と直接の 2.5 の結果と実装からの判断で、この組み合わせは実行していない）。`constraints` の `strictly` を使う。これは決定 3 の例外で、低い固定の危険があるので、解決結果の版に合わせ、理由をコメントに残す
- 固定した版が古くなることは、この設定では検知されない。範囲の中に新しい版が出ても、固定の版のまま通過する

確認していないこと:

- 24 時間の経過は待っていない。キャッシュの削除で代用した
- configuration cache は外して実行した
- 別の版に負けてグラフから外れた component の依存が動的な場合は実行していない。Gradle の統合テストでは通過する。結果に関わらないためである
- BOM の制約に書かれた動的な版と、Ivy のリポジトリ
- Gradle のバージョンは 9.7.1 だけである。rss-reader のコピーは Gradle 9.1.0 で、対策が誤検知を起こさないことだけを確認した

### 自前の検査（廃止）

この節は廃止した `checkResolvedVersions` の記録である。実装は 0605bc0 からの変更として a8d068e まで残っている。廃止の理由は[論点 6](#論点-6-既存の手段との比較と見直し)に書く。

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
- catalog にない推移的依存の動的な版そのもの。ただし `failOnNonReproducibleResolution()` がグラフの失敗にするので、`not resolved` として報告される（M7）

## 論点 3: 検知と固定の使い分け

検知して人が判断する場面:

- 依存の更新で別の依存が上がったとき。`gradle.lockfile` の差分で上がった版を確認する
- BOM を超えて上がったとき。意図した組み合わせかを確認する
- plugin の classpath のズレ。Kotlin と Compose Compiler のように組み合わせに制約があるものは、`./gradlew buildEnvironment` で確認する

固定する場面:

- 脆弱性や不具合のために推移的依存を上げるとき。下げる方向の固定ではないので `strictly` は要らない。catalog に項目を足して `constraints` で宣言する（S7）。lock に現れ、Dependabot も更新できる

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

`gradle.lockfile` だけを使う構成での確認は[論点 6](#論点-6-既存の手段との比較と見直し)に書く。

`groups` は main に入り、Gradle の更新は 1 つの PR に束ねられている（[`dependabot.yml`](https://github.com/appachan/android-supply-chain-lab/blob/80c3f5d/.github/dependabot.yml)、[ADR-0002](https://github.com/appachan/android-supply-chain-lab/blob/80c3f5d/docs/adr/0002-dependency-verification.md)）。

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

- グラフだけの解決では検証は動かない。CI では成果物を解決する `build` を実行する
- 解決結果を変える方式（`strictly`、`enforcedPlatform`、`constraints`、locking の lock 更新）を使うと `verification-metadata.xml` の更新が必要になる。catalog を解決結果に合わせるだけの修正では解決結果が変わらないので、更新は不要
- 低い版への固定は、`verification-metadata.xml` に古い版が記録されていると検証を通る。`tracing` の 1.0.0 と 1.2.0 を記録した状態で 1.0.0 に固定すると成功した。検証は版の妥当性を判断しない
- 最初の決定は、依存の更新 PR で推移的依存の変化が `verification-metadata.xml` の diff に見えることを前提にしていた。この前提は成り立たなかった（[論点 6](#論点-6-既存の手段との比較と見直し)）
- Dependabot 自身が `verification-metadata.xml` を更新する実装は dependabot-core の Gradle の `lib` に見当たらなかった（`verification-metadata` の検索で 0 件）。main の再生成ジョブはこれを補うものである

## 論点 6: 既存の手段との比較と見直し

最初の決定は既存のツールと比較していなかった。自作を前提にせず、検査の目的から比較し直した。Dependabot を運用している環境を前提にする。実験は main の 80c3f5d のコピーで行った。Gradle 9.7.1、AGP 9.3.3、strict な `verification-metadata.xml` の状態である。

### 前提の検証: metadata の diff に版の変化は出ない

ADR-0002 の `pgp,sha256` では、信頼する鍵で署名された成果物は metadata にハッシュを持たないので、版が上がっても差分が出ない。実際の PR で確かめた。

| PR | 実際の版の変化 | metadata の差分 | 差分に出た版 |
| --- | --- | --- | --- |
| [#1](https://github.com/appachan/android-supply-chain-lab/pull/1) AGP 9.3.3 から 9.5.0 | plugin の classpath の 114 module のうち 36。`bcprov-jdk18on` などの 1.79 から 1.80.2 を含む。`debugRuntimeClasspath` などは 0 | +6/-1 | 0 件。`trusted-key` の範囲の変更だけである |
| [#3](https://github.com/appachan/android-supply-chain-lab/pull/3) 同じ更新と wrapper | 同上 | +11/-1 | `bc-jdk18on-bom-1.80.2.pom` の 1 件だけ |

ライブラリの変更も同じである。Dependabot の PR ではないが、`activity` を 1.13.0 から metadata に未記録の 1.12.4 に変えて再生成すると、`debugRuntimeClasspath` の 102 module のうち 9 の版が変わったのに対し、metadata の差分は 11 行で、`component` として現れたのは `core` 1.16.0 と `core-ktx` 1.13.0 の 2 件だけだった。

前提は成り立たない。metadata の diff は、新しい鍵と無署名の成果物を確認するものであり、推移的依存の変化を見るものではない。locking を不採用にした主な根拠が崩れたので、結論を見直した。

### 時期による変化は failOnNonReproducibleResolution() で足りる

足りる。[M7](#failonnonreproducibleresolution-と推移的依存) のとおり、80 通りで結果が変わり得るものはすべて失敗した。動的な版と changing な版がなければ、グラフは catalog と公開済みのメタデータから決まる。公開済みの成果物の差し替えは `verification-metadata.xml` が検知する。

条件が 2 つある。

- 設定が届く configuration に限る。subproject、settings、included build の classpath は個別に設定する（M7）
- lock した configuration には設定できない。ただし同じ依存を見る `*LintChecksClasspath` に設定しておけば `build` で止まる。lock した `debugRuntimeClasspath` は、範囲を指定した依存と SNAPSHOT の依存を解決できてしまうが、`:app:lintVitalAnalyzeRelease` が `Could not resolve t:[1.0,2.0): ...dynamic versions` と `Could not resolve t:1.0-SNAPSHOT: ...changing versions` で失敗した。AGP の configuration の名前に依存している

lock だけでは足りない。lock した範囲は新しい版を公開しても 1.9 のままだったが、SNAPSHOT は版が同じまま中身が変わり、strict でも通過した。[公式](https://docs.gradle.org/9.7.1/userguide/dependency_verification.html)のとおり、検証は SNAPSHOT を対象にしない。なお lab のリポジトリには SNAPSHOT がないので、`core-ktx:1.99.0-SNAPSHOT` は `Could not resolve` で失敗する。SNAPSHOT のリポジトリを足すまで、この経路は実際には開かない。

### 決定的だが意図しない版は標準機能で止められる

Gradle は最も高い版を選び、BOM は上限にならない（M6）。したがって、対象を指定せずに事前に防ぐ標準機能はない。止める手段は 2 つあり、どちらも標準機能で足りる。

`feature` と `other` の 2 module を足し、Compose BOM を 2025.06.00 にして、両方の `ui` が 1.8.2 の状態から `feature` だけに `activity-compose` を足して確かめた。

| 手段 | 結果 |
| --- | --- |
| 何もしない | `feature` だけ 1.9.2 になり、`build` は成功する |
| `gradle.lockfile` | `Resolved 'androidx.activity:activity-compose:1.13.0' which is not part of the dependency lock state` で失敗する。`--write-locks` の後、差分は `feature/gradle.lockfile` だけに出て、`ui` の 1.9.2 が見える。`other` は 1.8.2 のまま |
| `constraints` の `version { reject("[1.9,)") }` | `Component is the target of multiple version constraints with conflicting requirements` で失敗する |
| `failOnVersionConflict()` | ズレがなくても失敗する（M3） |
| `strictly`、`enforcedPlatform` | 固定できるが低い固定の危険がある（論点 3、M6） |

lock は変化を止めるが、Dependabot の PR では lock も更新されるので、CI は失敗しない。止めるのは PR の差分を読む人である。ADR-0002 が auto-merge を使わず人が確認すると決めているので、この運用に乗る。上げたくない module が分かっているときは `reject` で機械的に止める。

残る限界:

- 一度受け入れたモジュール間の差は、エラーにならない。`feature` の 1.9.2 と `other` の 1.8.2 は lock に並ぶだけである
- plugin の classpath は lock しない。AGP の更新の 36 module の変化は PR に出ない。plugin の版は catalog の 1 行で決まり、その依存は plugin の公開者が決めたものなので許容する。内容は `verification-metadata.xml` が検証する
- catalog と解決結果の不一致そのものは見ない

### 候補の洗い出し

保守の状況は 2026-10-31 に GitHub で確認した。

| 候補 | 最新のリリース | open issue | 内容 | 判断 |
| --- | --- | --- | --- | --- |
| `gradle.lockfile`（標準） | Gradle 9.7.1 | なし | 解決結果を lock し、変化で失敗する。Dependabot が更新する | 採用 |
| `failOnVersionConflict()`（標準） | 同上 | なし | 競合で失敗する | 不採用。ズレがなくても失敗する（M3） |
| [dependency-guard](https://github.com/dropbox/dependency-guard) | 0.5.0（2024-02-05） | 26 | configuration ごとの baseline と比べる | 不採用。下記 |
| [gradle-consistent-versions](https://github.com/palantir/gradle-consistent-versions) | 3.22.0（2026-10-27） | 54 | `versions.props` と `versions.lock` で版を一元管理する | 不採用。catalog を置き換える独自の形式で、Dependabot が取得するのは `gradle.lockfile` だけである（論点 4）。実行していない |
| [gradle-dependency-lock-plugin](https://github.com/nebula-plugins/gradle-dependency-lock-plugin) | v17.2.1（2026-10-05） | 30 | 独自の lock | 不採用。標準の lock で足りる。実行していない |
| [dependency-tree-diff](https://github.com/JakeWharton/dependency-tree-diff) と [action](https://github.com/usefulness/dependency-tree-diff-action) | 1.2.1（2024-01-02）、v2.4.0（2026-08-10） | 2、1 | base と head の依存ツリーの diff を出す。baseline を持たない | 不採用。失敗にならず、CI のジョブが増える。実行していない |
| [dependency-submission](https://github.com/gradle/actions/blob/main/docs/dependency-submission.md) と [dependency-review-action](https://github.com/actions/dependency-review-action) | v6.4.1（2026-10-28）、v5.0.0（2026-05-08） | 44、40 | 依存のグラフを GitHub に送り、PR で確認する | 不採用。`contents: write` のジョブが増える。lab の `dependency-graph/sbom` は 404 で、リポジトリ設定の確認が必要である。実行していない |
| [gradle-versions-plugin](https://github.com/ben-manes/gradle-versions-plugin)、[dependency-analysis](https://github.com/autonomousapps/dependency-analysis-gradle-plugin)、[version-catalog-update](https://github.com/littlerobots/version-catalog-update-plugin) | v0.66.0、v3.20.0、v1.1.2 | 57、164、5 | 新しい版の提案、未使用の依存の検出、catalog の更新 | 対象外。解決結果のズレを見ない |

### dependency-guard の実験

Gradle 9.7.1、AGP 9.3.3 で動いた。root に `classpath`、app に `debugRuntimeClasspath` と `releaseRuntimeClasspath` を設定した。configuration cache も再利用された。[#113](https://github.com/dropbox/dependency-guard/issues/113) の root の classpath の失敗は再現しなかった。

| シナリオ | 結果 |
| --- | --- |
| S1 catalog より推移的依存が高い | 検知しない（exit 0） |
| `activity` の変更と推移的依存の変化 | 検知 |
| Compose BOM の変更 | 検知 |
| plugin の classpath（`kotlin = "2.2.0"`） | 検知。`classpath.txt` に 2.2.10 が出る |
| AGP 9.5.0 | 検知。36 module の変化が出る |
| 解決できない依存 | `- androidx.core:core-ktx:1.18.0` と削除として報告される（[#112](https://github.com/dropbox/dependency-guard/issues/112)） |

検知の範囲は lock より広く、`failOn` と併用できる。plugin の classpath の変化を見られるのは lock にない利点である。不採用の理由は次のとおり。

- 自然なアプローチで解決できる問題に、追加の plugin を足すことになる。plugin の classpath の変化を見たいという理由だけでは足りない
- Dependabot は baseline を更新しない。再生成のジョブに `dependencyGuardBaseline` を足して、artifact とコミットの対象にする必要がある。[Renovate との連携](https://github.com/dropbox/dependency-guard/issues/60)や[自動の更新](https://github.com/dropbox/dependency-guard/issues/14)は issue のままである
- 最新のリリースは 2024 年で、リポジトリの Gradle は 7.6.1 である。2024 年以降のコミットはリリース設定と README の修正だけである。Gradle 9 と AGP 9 の対応は宣言されておらず、今回動いただけである
- `verification-metadata.xml` の差分が 62 行増えた。署名の鍵 `902E15693BB8846B` を鍵サーバーから取得できず `ignored-key` になり、plugin の jar はハッシュで受け入れることになった。鍵の信頼で確認できない成果物を、ビルド時に実行するコードとして足すことになる
- module ごとに plugin と設定が必要である

### gradle.lockfile だけの構成の実験

`buildscript-gradle.lockfile` を作らない。Dependabot の処理は[現在の実装](https://github.com/dependabot/dependabot-core/blob/85379652e4ef784fdd38bf4598b0b347832f805b/gradle/lib/dependabot/gradle/file_updater/lockfile_updater.rb#L58-L77)の init script と同じ引数で手元で実行した。実際の Dependabot の実行ではない。

全 configuration を lock すると 299 行で、AGP の更新で lint などの lock がずれて `build` が失敗する。`*CompileClasspath` と `*RuntimeClasspath` に絞ると 109 行で、AGP だけの更新では lock が変わらず、それ以外の configuration に `failOn` を残せる。この形を採用した。

| 確認 | 結果 |
| --- | --- |
| project の configuration に `failOnDynamicVersions` または `failOnChangingVersions` | `You must choose between the two modes` で失敗 |
| root の `classpath` に `failOnNonReproducibleResolution()`、lock は project だけ | 成功。configuration ごとの制約なので問題ない |
| catalog を lock より下げる（`activity` 1.12.4、lock は 1.13.0） | 黙って 1.13.0 になり `build` 成功。[公式の動作](https://docs.gradle.org/9.7.1/userguide/dependency_locking.html)である。使う版は変わらない |
| catalog を lock より上げる | `Cannot find a version ... that satisfies the version constraints` で失敗 |
| lock にない依存を足す | `not part of the dependency lock state` で失敗 |
| ライブラリの更新で Dependabot のコマンド | strict でも成功。init script はグラフだけを解決するので検証が動かない。lock が更新される。metadata が必要なら `build` は再生成の後に成功する |
| 新しい鍵やハッシュが必要な AGP の更新で同じコマンド | 失敗。root の classpath の解決が `Dependency verification failed` で止まる。Dependabot は[エラーを log に出して元の lockfile を返す](https://github.com/dependabot/dependabot-core/blob/85379652e4ef784fdd38bf4598b0b347832f805b/gradle/lib/dependabot/gradle/file_updater/lockfile_updater.rb#L74-L77) |
| 上の結果で AGP だけの更新 | lock は変わらないので問題ない |
| AGP とライブラリを同時に更新（`groups` で起きる） | lock が古いまま残る。main の再生成のコマンドは `Cannot find a version` で失敗 |
| 再生成のコマンドに `--write-locks` を足す | 2 回とも成功。lock の差分は +11/-11 で、strict の `build` も成功 |

両立は条件付きである。ライブラリだけの PR はそのまま動く。plugin と同じ PR では Dependabot の lock の更新が失敗するので、再生成のジョブが lock も更新する必要がある。main の 2 つのワークフローに次の変更が要る。テーマ 2 のファイルなので、このブランチでは変更していない。Actions の上では実行していない。

```diff
--- a/.github/workflows/regenerate-verification-metadata.yml
+++ b/.github/workflows/regenerate-verification-metadata.yml
-          ./gradlew --write-verification-metadata pgp,sha256 --export-keys build
-          ./gradlew --write-verification-metadata pgp,sha256 --export-keys build
+          ./gradlew --write-verification-metadata pgp,sha256 --export-keys --write-locks build
+          ./gradlew --write-verification-metadata pgp,sha256 --export-keys --write-locks build
@@
             gradle/verification-metadata.xml
             gradle/verification-keyring.keys
+            app/gradle.lockfile
--- a/.github/workflows/commit-verification-metadata.yml
+++ b/.github/workflows/commit-verification-metadata.yml
```

`commit-verification-metadata.yml` は、比較と `fileChanges` に lockfile を加える。`upload-artifact` は共通の親を基準に配置するので、ダウンロード先のパスも合わせて変更する。lockfile は検証なしで実行したジョブの出力なので、metadata と同じく信頼しないデータとして扱う。版の変更は PR の差分で人が確認する。

### 比較

| 観点 | 検査をやめる（`failOn` だけ） | `gradle.lockfile` と `failOn`（採用） | dependency-guard と `failOn` | 自作を続ける |
| --- | --- | --- | --- | --- |
| 時期による変化 | 防ぐ | 防ぐ（`build` の lint の configuration で） | 防ぐ | 防ぐ（`failOn` による） |
| catalog との不一致 | 検知しない | 検知しない | 検知しない | 検知 |
| catalog にない推移的依存の変化 | 検知しない | 検知、差分に出る | 検知、差分に出る | 検知しない |
| BOM を超えた版 | 検知しない | 変化として検知 | 変化として検知 | 検知 |
| plugin の classpath | 検知しない | 検知しない（S3 は stdlib の lock で間接的に失敗） | 検知 | marker の実装だけ検知 |
| 特定の module だけのズレ | 検知しない | 検知。module ごとの差分 | 検知。設定した configuration | catalog の項目だけ |
| Dependabot の PR | 変更なし | ライブラリはそのまま。plugin と同時なら再生成に `--write-locks` | baseline を再生成の流れに足す | 別の catalog の項目を超えると CI が失敗し、人が直す |
| マルチモジュールの手間 | なし | なし。`subprojects` で適用し、lockfile は自動で増える | module ごとに設定 | なし |
| 差分のレビュー | なし | 行に configuration 名が並ぶが読める | 最も読みやすい | 差分は出ず、失敗のメッセージだけ |
| plugin を足すリスク | なし | なし | 鍵を取得できない plugin を足す | なし |
| 保守 | なし | root の 9 行 | plugin の追従 | 100 行のスクリプト |

### 判断

検査をやめる案は採用しない。`failOn` だけでは compose ui の例が黙って通り、意図しない版が使われないという目的を満たせない。

自作は置き換える。目的に照らすと、自作の検査が見ていたのは使う版そのものではなく、catalog の値との不一致だった。S1 では catalog を 1.13.1 と書いても使う版は 1.18.0 のまま変わらない。一方で、本当に見るべき推移的依存の変化は見えていなかった。lock はこれを標準機能で見せる。失うのは catalog との不一致の検知だけで、使う版は lock に残るので許容する。

スクリプトの縮小も採用しない。`failOnNonReproducibleResolution()` の 2 行は標準機能で、残りの 100 行は目的に対して必要ない。

以前のシナリオの結果は次のとおり。実装のコピーで `build` を実行した。verification-metadata はこのブランチにないので含まない。

| シナリオ | 以前の検査 | 今の `build` | `--write-locks` の差分 |
| --- | --- | --- | --- |
| S0 変更なし | 成功 | 成功 | なし |
| S1 catalog が推移的依存より低い | 失敗 | 成功。使う版は変わらない | なし |
| S2 catalog に足すだけ | 成功 | 成功 | なし |
| S3 `kotlin = "2.2.0"` | 失敗 | 失敗。lock の stdlib 2.4.21 でコンパイルが失敗する | +1/-1。更新後は成功 |
| S4 `strictly("1.13.1")` | 失敗 | 失敗。`Cannot find a version` | +4/-3。更新後もコンパイルが失敗する（論点 3 の危険） |
| S5 Compose BOM を下げる | 失敗 | 成功。lock の版のままで使う版は変わらない | +73/-47 |
| S6 debug だけ `tracing` 1.3.0 | 失敗 | 失敗。`not part of the dependency lock state` | +3/-1。debug の行だけ変わる |
| S7 `constraints` で上げる | 成功 | 失敗。意図した変更なので lock を更新する | +2/-1 |
| S8 build script に直接低い版 | 成功 | 成功。使う版は変わらない | なし |
| S9 `1.+` | 失敗 | 失敗。lint の configuration の `disallows usage of dynamic versions` | 更新しても失敗 |

S1、S5、S8 が通るのは、使う版が変わらないからである。使う版が変わる S3、S4、S6、S7、S9 はすべて失敗する。

main の 80c3f5d と merge した状態の CI は成功した（[run 41166135144](https://github.com/appachan/android-supply-chain-lab/actions/runs/41166135144)）。

## 運用手順

`gradle.lockfile` と異なる版が解決されて CI が失敗したとき:

1. メッセージで module を確認し、原因を調べる

   ```sh
   ./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency <group:name>
   ```

2. 問題がなければ lock を更新して、差分をコミットする

   ```sh
   ./gradlew :app:dependencies --write-locks
   ```

3. 問題があれば、更新を見送るか、`reject` で止める

Dependabot の PR では、次を確認する。

- `gradle.lockfile` の差分。catalog の変更で説明できない module の変化がないか。特に Compose のように一式で揃う module が、一部の module でだけ変わっていないか
- plugin の更新の PR では `gradle.lockfile` が更新されたか。古いままなら再生成のジョブを確認する

推移的依存を上げるときは、catalog に項目を足して `constraints` で宣言する。範囲を指定した依存が入って失敗したときも同じである。`constraints` を外すときは catalog の項目も消す。

上げたくない module は `constraints` の `reject` で止める。理由を `because` に書き、解除の条件を決める。

catalog の版を下げるときは lock も更新する。更新しないと使う版は変わらない。

## rss-reader に適用するときの差分

コピー（227fb9c）で確認した。元のリポジトリは変更していない。CI の step の追加は要らない。lock はすべてのビルドで効く。

1. `build.gradle.kts` に追加する

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
   +subprojects {
   +    configurations.configureEach {
   +        if (name.endsWith("CompileClasspath", ignoreCase = true) || name.endsWith("RuntimeClasspath", ignoreCase = true)) {
   +            resolutionStrategy.activateDependencyLocking()
   +        } else {
   +            resolutionStrategy.failOnNonReproducibleResolution()
   +        }
   +    }
   +}
   ```

2. settings と `build-logic` に設定する。root の `buildscript` と `subprojects` は届かない（[M7](#failonnonreproducibleresolution-と推移的依存)）

   ```diff
   --- a/settings.gradle.kts
   +++ b/settings.gradle.kts
   +buildscript {
   +    configurations.all { resolutionStrategy.failOnNonReproducibleResolution() }
   +}
    dependencyResolutionManagement {
   --- a/build-logic/convention/build.gradle.kts
   +++ b/build-logic/convention/build.gradle.kts
   +buildscript {
   +    configurations.classpath { resolutionStrategy.failOnNonReproducibleResolution() }
   +}
   +
    plugins {
        `kotlin-dsl`
    }
   +
   +configurations.configureEach { resolutionStrategy.failOnNonReproducibleResolution() }
   ```

3. lockfile を生成してコミットする。root の `dependencies` は subproject を辿らないので、module ごとの `dependencies` を並べるか、[Dependabot の init script](#再現手順) を使う。確認では init script を使った

   ```sh
   ./gradlew :app:dependencies :shared:domain:dependencies --write-locks
   ```

確認の結果:

- 10 module で計 2,729 行の `gradle.lockfile` ができた。全 configuration を lock すると 4,739 行である
- `./gradlew build --continue -x test` が成功した（730 タスク）。誤検知はない
- `junit-vintage-engine` の catalog の 5.10.2 は変更しない。使う版は lock の 5.12.2 である。直すなら catalog を上げるだけでよい
- plugin の classpath の lock、`build-logic` の lock は行わない

## 再現手順

依存の変化が止まること:

```diff
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
     implementation(libs.androidx.activity.compose)
+    debugImplementation("androidx.tracing:tracing:1.3.0")
```

`./gradlew build` が `not part of the dependency lock state` で失敗し、`./gradlew :app:dependencies --write-locks` の差分に debug の行だけが出る。

Dependabot の lock の更新の再現は、[`write_init_script`](https://github.com/dependabot/dependabot-core/blob/85379652e4ef784fdd38bf4598b0b347832f805b/gradle/lib/dependabot/gradle/file_updater/lockfile_updater.rb#L308-L335) の内容を取り出して次を実行する。

```sh
./gradlew --init-script dependabot-locking.init.gradle dependabotResolveAll --write-locks --no-daemon
```

このコマンドの実行では `settings-gradle.lockfile` もできるが、中身はないのでコミットしない。`gradle.lockfile` の `empty=` の行は実行するコマンドで有無が変わるが、解決には影響しない。

## main に統合するときの注意

- `README.md` の 2 か所が競合する。テーマ 2 の行とテーマ 3 の行の両方を残す
- 上記の再生成のワークフローの変更を同時に入れる。入れないと、plugin とライブラリを同時に更新した PR の再生成が失敗する
- 統合後に実際の Dependabot の PR で、ライブラリだけの更新と、plugin を含む更新の両方を確認する

## Consequences

- 使う版が変わると、ライブラリの compile と runtime の classpath で CI が止まるか、PR の `gradle.lockfile` の差分に出る
- 依存の更新で `gradle.lockfile` の差分のレビューが増える。自作のスクリプトはなくなる
- 動的な版と changing な版は、推移的依存を含めて使えない。lock した configuration の分は lint の configuration で止まるので、AGP の configuration の名前に依存する
- catalog の値と使う版の不一致は検知しない。正しい版は `gradle.lockfile` にある
- plugin の classpath の推移的依存の変化は PR に出ない
- `verification-metadata.xml` は版の変化を見るものではない。ADR-0002 が確認するのは新しい鍵と無署名の成果物である
- plugin と同じ PR でライブラリを更新すると、再生成のジョブが lock を更新する必要がある。main のワークフローの変更は統合のときに行う
- subproject、settings、included build の classpath は `failOnNonReproducibleResolution()` の対象外なので、使い始めるときに設定を足す
