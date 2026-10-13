#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")/.."
if [[ "$(uname -s)" != Darwin ]]; then
  echo 'Build the macOS application on macOS.' >&2
  exit 1
fi
: "${JAVA_HOME:?Set JAVA_HOME to a macOS JDK 25 installation}"
java_version=$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1)
if ! [[ "$java_version" =~ java.specification.version\ =\ 25 ]]; then
  echo 'MacKeeper requires JDK 25.' >&2
  exit 1
fi

./gradlew test installDist --console=plain
# Unique output directories make repeat builds safe without deleting an existing app.
mkdir -p build/macos
stage=$(mktemp -d "$PWD/build/macos/package.XXXXXX")
"$JAVA_HOME/bin/jlink" \
  --add-modules java.se,jdk.unsupported,jdk.charsets,jdk.zipfs,jdk.localedata,jdk.crypto.ec \
  --strip-debug --no-header-files --no-man-pages \
  --output "$stage/runtime"
# Keep bin/java: the launcher separates Swing setup from GLFW's first-thread JVM.
test -x "$stage/runtime/bin/java"
cat > "$stage/game.properties" <<'EOF'
main-jar=MacKeeper-0.1.0-dev.jar
main-class=toniarts.openkeeper.GameLauncher
java-options=-XstartOnFirstThread -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED
EOF
"$JAVA_HOME/bin/jpackage" --type app-image --name MacKeeper \
  --app-version 1 --vendor 'MacKeeper contributors' \
  --description 'Experimental OpenKeeper-derived Dungeon Keeper II engine' \
  --input build/install/MacKeeper/lib --main-jar MacKeeper-0.1.0-dev.jar \
  --main-class toniarts.openkeeper.Launcher \
  --add-launcher "MacKeeperGame=$stage/game.properties" \
  --runtime-image "$stage/runtime" --dest "$stage/app" \
  --mac-package-identifier io.github.dylandeheer.mackeeper
cp LICENSE license.txt "$stage/app/MacKeeper.app/Contents/Resources/"
cp README.md "$stage/app/MacKeeper.app/Contents/Resources/"
cp -R docs "$stage/app/MacKeeper.app/Contents/Resources/"
# Apple's bundle build number must start above zero; display the pre-release version.
/usr/libexec/PlistBuddy -c 'Set :CFBundleShortVersionString 0.1.0' "$stage/app/MacKeeper.app/Contents/Info.plist"
codesign --force --deep --sign - "$stage/app/MacKeeper.app"
echo "Development app (not Developer ID signed or notarized): $stage/app/MacKeeper.app"
