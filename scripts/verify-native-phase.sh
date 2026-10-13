#!/bin/bash
set -euo pipefail
if [[ "$#" -ne 1 ]]; then
  echo 'Usage: scripts/verify-native-phase.sh "/path/to/Dungeon Keeper II"' >&2
  exit 2
fi
game_data_dir=$(cd "$1" && pwd)
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 25 installation}"
./gradlew classes installDist --console=plain >&2
probe_classes=$(mktemp -d "${TMPDIR:-/tmp}/mackeeper-phase.XXXXXX")
trap 'rm -rf "$probe_classes"' EXIT
"$JAVA_HOME/bin/javac" -cp 'build/classes/java/main:build/install/MacKeeper/lib/*' -d "$probe_classes" scripts/WorldContinuationSmoke.java scripts/NativePhaseSmoke.java
"$JAVA_HOME/bin/java" -cp "$probe_classes:build/classes/java/main:build/install/MacKeeper/lib/*" NativePhaseSmoke "$game_data_dir"
