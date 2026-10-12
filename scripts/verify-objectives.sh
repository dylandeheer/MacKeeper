#!/bin/bash
set -euo pipefail
if [[ "$#" -ne 2 ]]; then
  echo 'Usage: scripts/verify-objectives.sh "/path/to/Dungeon Keeper II" "/path/to/Converted"' >&2
  exit 2
fi
game_data_dir=$(cd "$1" && pwd)
converted_dir=$(cd "$2" && pwd)
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 25 installation}"
./gradlew classes installDist --console=plain >&2
"$JAVA_HOME/bin/java" -cp 'build/classes/java/main:build/install/MacKeeper/lib/*' scripts/ObjectiveAudit.java "$game_data_dir" "$converted_dir"
