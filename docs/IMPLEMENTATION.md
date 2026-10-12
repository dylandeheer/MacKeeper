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
- All 144 automated tests pass, including setup/game launch isolation, property persistence and synthetic data validation. Mac packaging and bundled command-line help/data checks pass locally; remote CI status is separate.
- Development builds use an ad hoc local signature. Developer ID signing/notarization and Intel hardware testing remain pending.

Log4j runtime constraints were updated from the upstream 2.15.0 dependency to 2.26.1, documented in [Apache's release notes](https://logging.apache.org/log4j/2.x/release-notes.html). Other inherited dependencies still need a full release audit.

## Simulation follow-up

- Implemented imp gold drops with bounded piles and retry coverage.
- Implemented scripted and normal room capture through one ownership/accounting path, with serialized construction and hand actions. Fixed gold duplication when a deposit spans several rooms and guarded deposits into destroyed/inactive-owner rooms.
- Added dungeon-heart replacement and null-safe camera/hero-objective handling when the last heart is lost.
- Parsed all 66 shipped maps and verified trigger links; all three real Level 17 room transfers pass a headless check. See [CAMPAIGN-AUDIT.md](CAMPAIGN-AUDIT.md) for evidence limits and remaining script dependencies and [COMPLETION-PLAN.md](COMPLETION-PLAN.md) for the full scope. No mission completion is claimed by these checks.
- Implemented hero-gate collapse, destroyed-room replication/redraw, ruins model selection and suppression of later party spawns at collapsed gates. All 67 shipped collapse commands and both destroyed model sets pass a local headless check; seven new synthetic tests pass. The Level 7 adjacent-wall target uses a documented inferred compatibility rule. Original animation/audio and campaign progression remain unverified.

- Scripted objective display now reaches the intended local/network player and remains available in the objective panel alongside the briefing. Nine new tests pass, the owned Level 19 action/text dispatch check passes, and a native panel preview with original assets was inspected. Protocol version is now 2; live multiplayer transport and persisted/reconnected objective restoration remain unverified. The root-aware audit corrects the earlier claim about 59 unimplemented marker actions: 45 already use creature handlers; 14 player-off commands remain unresolved.

- Corrected creature level percentages so fractional gains affect wages, health, combat, movement and work rates. Level changes preserve carried gold and map-authored starting balances; generated creatures receive scaled defaults. Spell unlocks now use minimum-level thresholds, and movement receives updated speed. Over-capacity pickups no longer duplicate gold. Seven new synthetic regressions pass; a real-data audit exercises 30,360 level transitions across 3,036 definitions in all 66 maps. This establishes data/ECS consistency, not original-engine rounding parity, combat balance or full gameplay acceptance.

- Implemented the payday clock, per-creature debt/advance ledger, physical room collection, gold gifts, unpaid anger and trigger cycle observation. The suite now has 121 tests; owned Levels 1, 2 and 17 pass real navigation/arrival/collection checks. Protocol version is now 4. See [PAYDAY.md](PAYDAY.md) for evidence and explicit parity gaps, including gift gratitude, presentation, persistence and full mission acceptance.

- Starting funds now use a separate reserve, spent before room treasure. Wide keeper balances preserve funds through capture; individual room purchases remain bounded. All 66 maps/234 funded player entries pass the initial-reserve audit. See [STARTING-GOLD.md](STARTING-GOLD.md) for transaction, serialization and callback evidence and limitations.

- Computer keepers now retain authored AI identity and execute economy decisions on the authoritative simulation thread. All 66 maps pass profile checks; two seeded twelve-minute production-system headless TrapTime runs verify normal mining/worker excavation/claiming and paid rooms for both opponents. The full suite has 144 tests. This is the economy foundation, with remaining opponent strategy and coverage recorded in [KEEPER-AI.md](KEEPER-AI.md).

- Failed assigned-task paths now retry once per game second, with immediate retries for changed tasks/locations. A seeded headless Level 10 comparison shows lower simulation cost; the benchmark and limitations are in [PERFORMANCE.md](PERFORMANCE.md).
