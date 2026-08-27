#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
TMP="${TMPDIR:-/tmp}/racelab-core-test"
rm -rf "$TMP" && mkdir -p "$TMP"
kotlinc \
  "$ROOT/app/src/main/java/ru/racelab/phone/core/RaceCore.kt" \
  "$ROOT/app/src/main/java/ru/racelab/phone/core/Parsers.kt" \
  "$ROOT/tools/CoreSelfTest.kt" \
  -include-runtime -d "$TMP/core-test.jar"
java -jar "$TMP/core-test.jar"
