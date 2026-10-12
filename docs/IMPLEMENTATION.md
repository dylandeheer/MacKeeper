# Implementation status

Target: a complete macOS Dungeon Keeper II implementation based on OpenKeeper, with clearer controls and modern performance. Fork base: `1d9a1c713a033bb2850e25e977ddfdd13113f77a`. Completion means verified behavior, not removal of TODO comments or successful compilation.

## Implemented in the initial Mac port

- MacKeeper launcher and application identity; upstream Java package names and copyrights retained.
- Separate Swing setup and GLFW rendering processes, with macOS first-thread arguments for rendering.
- Per-user writable settings, asset cache, logs and screenshots independent of installation directory.
- Atomic settings replacement, preserving the previous file if serialization fails.
- Case-insensitive asset presence diagnostics and bounded Steam-library discovery.
- Escape routed to pause/possession rather than immediate application exit.
- Nifty commit pin, Gradle dependency locks, Java 25 CI, and macOS application packaging.
- Synthetic tests for data paths, file-presence validation, Steam discovery and launcher arguments.

## Remaining release gates

- Complete media fidelity checks beyond the initial Steam conversion and first-mission smoke test. Some UI sound lookups still warn; neither sound fidelity nor all movies have been verified.
- Verify renderer/input/audio on Apple silicon and Intel; test Retina scaling, windowed/fullscreen transitions, trackpad/right-click, pause/resume and quit.
- Implement and validate keeper AI: economy, excavation, construction, room expansion, creature management, defense and combat.
- Finish missing creature needs, moods and interactions against original behavior.
- Implement versioned save/load across entities, map edits, session state, triggers, timers and random state; test resumed outcomes and corrupt-save handling. Upstream menu placeholders are not a save system.
- Validate every campaign level, alternative branches and bonus maps, triggers, victory/defeat, unlocks and progression.
- Validate skirmish and multiplayer behavior and reconnection; define compatibility before exposing a supported network mode.
- Add accessible, discoverable controls and useful error recovery; preserve a classic control option where changes affect play.
- Profile representative large dungeons and improve measured bottlenecks; no performance uplift is claimed without comparable measurements.
- Resolve remaining dependency/release provenance, branding, signing and notarization before distributing to other users.

The original retail game data is a local test dependency and must never be committed or uploaded with artifacts. Automated unit tests use synthetic fixtures.

## Verification on 2026-10-12

- Downloaded the owned Steam base depot `2616461` (manifest `4905398069127563016`) and English depot `2616462` (manifest `5786048773683217752`) for app `2616460`; combined local data size 712,586,955 bytes. No game data is in this repository.
- All conversion tasks completed in about 15 seconds on the test Mac.
- On Apple M1 Max, the bundled ARM64 Java 25 app initialized OpenGL 4.1 and OpenAL, played the Bullfrog intro, displayed the main menu, campaign map and mission briefing, and loaded campaign mission 1.
- Visually verified Escape pause and resume and a running dungeon after the opening cinematic. This does not establish mission completion or campaign compatibility.
- Fixed two errors found by actual launch: Cocoa rejects setting a normal window icon; the video material required GLSL150 and the engine's GLSL compatibility header for the core OpenGL profile.
- All 47 automated tests pass, including setup/game launch isolation, property persistence and synthetic data validation. Mac packaging and bundled command-line help/data checks pass locally; remote CI status is separate.
- Development builds use an ad hoc local signature. Developer ID signing/notarization and Intel hardware testing remain pending.

Log4j runtime constraints were updated from the upstream 2.15.0 dependency to 2.26.1, documented in [Apache's release notes](https://logging.apache.org/log4j/2.x/release-notes.html). Other inherited dependencies still need a full release audit.
