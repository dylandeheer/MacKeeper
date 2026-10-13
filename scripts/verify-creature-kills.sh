#!/bin/bash
set -euo pipefail
if [[ "$#" -ne 1 ]]; then
  echo 'Usage: scripts/verify-creature-kills.sh "/path/to/Dungeon Keeper II"' >&2
  exit 2
fi
game_data_dir=$(cd "$1" && pwd)
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 25 installation}"
./gradlew classes installDist --console=plain >&2
probe_classes=$(mktemp -d "${TMPDIR:-/tmp}/mackeeper-kills.XXXXXX")
trap 'rm -rf "$probe_classes"' EXIT
"$JAVA_HOME/bin/javac" -cp 'build/classes/java/main:build/install/MacKeeper/lib/*' -d "$probe_classes" scripts/CreatureKillSmoke.java
"$JAVA_HOME/bin/java" -cp "$probe_classes:build/classes/java/main:build/install/MacKeeper/lib/*" toniarts.openkeeper.tools.convert.map.CreatureKillSmoke "$game_data_dir"
