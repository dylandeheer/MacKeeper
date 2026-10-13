#!/bin/bash
set -euo pipefail
if [[ "$#" -ne 1 ]]; then
  echo 'Usage: scripts/verify-objective-targets.sh "/path/to/Dungeon Keeper II"' >&2
  exit 2
fi
game_data_dir=$(cd "$1" && pwd)
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 25 installation}"
./gradlew classes installDist --console=plain >&2
mkdir -p work/objective-target-classes
"$JAVA_HOME/bin/javac" -cp 'build/classes/java/main:build/install/MacKeeper/lib/*' -d work/objective-target-classes \
  scripts/ObjectiveAudit.java scripts/WorldContinuationSmoke.java scripts/ObjectiveTargetAudit.java
"$JAVA_HOME/bin/java" -cp 'work/objective-target-classes:build/classes/java/main:build/install/MacKeeper/lib/*' ObjectiveTargetAudit "$game_data_dir"
