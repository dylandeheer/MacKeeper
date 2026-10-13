#!/bin/bash
set -euo pipefail
if [[ "$#" -ne 1 ]]; then
  echo 'Usage: scripts/verify-defense-conditions.sh "/path/to/Dungeon Keeper II"' >&2
  exit 2
fi
game_data_dir=$(cd "$1" && pwd)
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 25 installation}"
./gradlew classes installDist --console=plain >&2
mkdir -p work/defense-condition-classes
"$JAVA_HOME/bin/javac" -cp 'build/classes/java/main:build/install/MacKeeper/lib/*' -d work/defense-condition-classes \
  scripts/ObjectiveAudit.java scripts/WorldContinuationSmoke.java scripts/DefenseConditionAudit.java
"$JAVA_HOME/bin/java" -cp 'work/defense-condition-classes:build/classes/java/main:build/install/MacKeeper/lib/*' DefenseConditionAudit "$game_data_dir"
