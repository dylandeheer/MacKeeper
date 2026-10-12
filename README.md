# MacKeeper

MacKeeper is an experimental macOS fork of [OpenKeeper](https://github.com/tonihele/OpenKeeper), the open-source Dungeon Keeper II engine reimplementation. It uses Java 25 and jMonkeyEngine 3.9. Original Bullfrog source code is not included.

**This is development work, not a complete or campaign-verified game.** Keeper AI, save/load, creature behavior and campaign compatibility still have substantial upstream gaps. See [implementation status](docs/IMPLEMENTATION.md).

You need your own Dungeon Keeper II game data. Original game assets are not distributed with MacKeeper. The source retains OpenKeeper's GPL-3.0-or-later license and attribution; the upstream introduction is preserved in [UPSTREAM.md](docs/UPSTREAM.md).

## Build and run

Install a JDK 25 for your Mac's architecture, set `JAVA_HOME`, then run:

```sh
./gradlew test installDist
build/install/MacKeeper/bin/MacKeeper --check-assets --data-dir '/path/to/Dungeon Keeper 2'
build/install/MacKeeper/bin/MacKeeper --data-dir '/path/to/Dungeon Keeper 2'
```

The launcher first prepares the assets in a separate setup process, then starts the game with macOS's required first-thread configuration. `--help` prints the available options. `-nomovies` skips intro movies. New installations use a resizable 1280×800 window with VSync.

To produce a self-contained development app with a bundled Java runtime:

```sh
scripts/package-macos.sh
```

The script prints the generated `MacKeeper.app` path under `build/macos/`. It builds for the current JDK architecture. It does not create a Developer ID signed or notarized release.

## Your data

Settings, converted assets, screenshots and logs are stored in `~/Library/Application Support/MacKeeper`. Source game files are read without modification. `-Dmackeeper.home=/another/path` overrides the writable data directory when passed to Java (or through the generated script's `JAVA_OPTS`).

The launcher can detect an installed Steam game or a console depot in the default Steam library. Console downloads split common files and language files into different folders; these must be combined into one complete data directory before playing. A file-presence check alone does not establish that the download is complete or the game works.

## Controls

Escape opens the pause menu during a game and leaves possession when possessing a creature. The original OpenKeeper controls and UI remain otherwise largely unchanged. A full Mac interaction redesign is still pending.

## Development

Run `./gradlew test` for synthetic, asset-free tests. Nifty GUI is pinned to an immutable commit and Gradle dependency versions are locked. When intentionally updating dependencies, review changes from `./gradlew test installDist --write-locks`.

Keep proprietary assets, local game settings and generated bundles out of Git. The bundled engine is GPL-3.0-or-later; dependencies retain their own licenses. Dungeon Keeper II is owned by its respective rights holders. This project is not affiliated with them or the unrelated MacKeeper utility product.

Dungeon-heart combat and defeat behavior, owned-data verification and remaining limits are recorded in [docs/DUNGEON-HEART.md](docs/DUNGEON-HEART.md).
