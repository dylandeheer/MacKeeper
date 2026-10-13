#!/bin/bash
set -euo pipefail
if [[ "$#" -ne 2 || "$(uname -s)" != Darwin ]]; then
  echo 'Usage on macOS: scripts/verify-native-objective-targets.sh "/path/to/Dungeon Keeper II" "/path/to/assets/Converted"' >&2
  exit 2
fi
game_data_dir=$(cd "$1" && pwd)
converted_dir=$(cd "$2" && pwd)
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a macOS JDK 25 installation}"
./gradlew classes installDist --console=plain >&2
mkdir -p work
profile=$(mktemp -d "$PWD/work/objective-profile.XXXXXX")
trap 'rm -rf "$profile"' EXIT
mkdir -p "$profile/assets"
ln -s "$converted_dir" "$profile/assets/Converted"
log=work/native-objective-targets.log
"$JAVA_HOME/bin/java" -XstartOnFirstThread -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED \
  "-Dmackeeper.home=$profile" "-Dmackeeper.dk2.dir=$game_data_dir" \
  -cp 'build/classes/java/main:build/install/MacKeeper/lib/*' scripts/NativeObjectiveTargetsSmoke.java > "$log" 2>&1
if grep -Eq 'AssertionError|Uncaught exception' "$log" || grep '^SEVERE:' "$log" | grep -Ev '^SEVERE: Loop ".+" took [0-9]+ ms!$'; then
  echo "Native objectives failed; see $log" >&2
  exit 1
fi
grep '^TARGETS' "$log"
# Cold-load tick overruns are reported separately from functional assertions.
grep -E '^SEVERE: Loop ".+" took [0-9]+ ms!$' "$log" || true
