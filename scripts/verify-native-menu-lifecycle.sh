#!/bin/bash
set -euo pipefail
if [[ "$#" -ne 2 || "$(uname -s)" != Darwin ]]; then
  echo 'Usage on macOS: scripts/verify-native-menu-lifecycle.sh "/path/to/Dungeon Keeper II" "/path/to/assets/Converted"' >&2
  exit 2
fi
game_data_dir=$(cd "$1" && pwd)
converted_dir=$(cd "$2" && pwd)
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to a macOS JDK 25 installation}"
./gradlew classes installDist --console=plain >&2
mkdir -p work
profile=$(mktemp -d "$PWD/work/menu-lifecycle-profile.XXXXXX")
trap 'rm -rf "$profile"' EXIT
mkdir -p "$profile/assets"
ln -s "$converted_dir" "$profile/assets/Converted"
for scenario in disable retire; do
  log="work/native-menu-$scenario.log"
  "$JAVA_HOME/bin/java" -XstartOnFirstThread -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED \
    "-Dmackeeper.home=$profile" "-Dmackeeper.dk2.dir=$game_data_dir" \
    -cp 'build/classes/java/main:build/install/MacKeeper/lib/*' scripts/NativeMenuLifecycleSmoke.java "$scenario" > "$log" 2>&1
  if grep -Eq 'SEVERE:|AssertionError|Uncaught exception|Failed to load the main menu scene|Failed to load!' "$log"; then
    echo "Native menu lifecycle failed; see $log" >&2
    exit 1
  fi
  grep '^MENU' "$log"
done
