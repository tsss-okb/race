#!/usr/bin/env bash
set -euo pipefail
VERSION=9.5.0
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/racelab-bootstrap"
DIST="$BASE/gradle-$VERSION"
if [ ! -x "$DIST/bin/gradle" ]; then
  mkdir -p "$BASE"
  ZIP="$BASE/gradle-$VERSION-bin.zip"
  echo "Downloading Gradle $VERSION..."
  curl -fL "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ZIP"
  unzip -q -o "$ZIP" -d "$BASE"
fi
exec "$DIST/bin/gradle" "$@"
