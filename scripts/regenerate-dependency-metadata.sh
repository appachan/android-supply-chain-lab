#!/bin/sh
# gradle/verification-metadata.xml、gradle/verification-keyring.keys、gradle.lockfile を再生成する。
# 空の GRADLE_USER_HOME を使うのは、キャッシュがあると不要な行が入るためである。実測で、build の後の
# キャッシュでは 24 行、普段の GRADLE_USER_HOME では 44 行増えた。
# gradle/gradle#19228 には、キャッシュがあると親や BOM の行が書かれないという報告もある（lab では未再現）。
# 親や BOM の POM の行は 1 回の実行で足りないことがある。そのときは verify が対象のファイルを示して
# 失敗するので、README の「検証が POM で失敗したとき」の手順で行を足す。
# 理由は docs/adr/0002-dependency-verification.md の「2 回の再生成が必要な原因」を参照
set -eu

cd "$(dirname "$0")/.."

GRADLE_USER_HOME="$(mktemp -d)"
export GRADLE_USER_HOME

cleanup() {
  ./gradlew --stop > /dev/null 2>&1 || true
  rm -rf "$GRADLE_USER_HOME"
}
trap cleanup EXIT

./gradlew --write-verification-metadata pgp,sha256 --export-keys --write-locks build
