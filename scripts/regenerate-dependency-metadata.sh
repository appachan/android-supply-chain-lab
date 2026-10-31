#!/bin/sh
# gradle/verification-metadata.xml、gradle/verification-keyring.keys、gradle.lockfile を再生成する。
# 空の GRADLE_USER_HOME を使うのは、キャッシュの状態で結果が変わるためである。部分的なキャッシュでは
# 不要な行が入り、redirect された子の親と BOM の行が書かれないこともある（gradle/gradle#19228）。
# 2 回実行するのは、親や BOM の POM の行が 1 回目に書かれないことがあるためである。
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
./gradlew --write-verification-metadata pgp,sha256 --export-keys --write-locks build
