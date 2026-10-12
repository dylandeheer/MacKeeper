#!/bin/bash
set -euo pipefail
if [[ "$#" -ne 1 ]]; then
  echo 'Usage: scripts/verify-camera-paths.sh "/path/to/assets/Converted/Interface/Paths"' >&2
  exit 2
fi
paths_dir=$(cd "$1" && pwd)
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 25 installation}"
./gradlew classes installDist --console=plain >&2
"$JAVA_HOME/bin/java" -Djava.awt.headless=true \
  -cp 'build/classes/java/main:build/install/MacKeeper/lib/*' scripts/CameraPathSmoke.java "$paths_dir"
