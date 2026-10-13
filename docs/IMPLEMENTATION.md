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
- All 262 automated tests pass, including setup/game launch isolation, property persistence and synthetic data validation. Mac packaging and bundled command-line help/data checks pass locally; remote CI status is separate.
- Development builds use an ad hoc local signature. Developer ID signing/notarization and Intel hardware testing remain pending.

Log4j runtime constraints were updated from the upstream 2.15.0 dependency to 2.26.1, documented in [Apache's release notes](https://logging.apache.org/log4j/2.x/release-notes.html). Other inherited dependencies still need a full release audit.

## Simulation follow-up

- Implemented imp gold drops with bounded piles and retry coverage.
- Implemented scripted and normal room capture through one ownership/accounting path, with serialized construction and hand actions. Fixed gold duplication when a deposit spans several rooms and guarded deposits into destroyed/inactive-owner rooms.
- Added dungeon-heart replacement and null-safe camera/hero-objective handling when the last heart is lost.
- Parsed all 66 shipped maps and verified trigger links; all three real Level 17 room transfers pass a headless check. See [CAMPAIGN-AUDIT.md](CAMPAIGN-AUDIT.md) for evidence limits and remaining script dependencies and [COMPLETION-PLAN.md](COMPLETION-PLAN.md) for the full scope. No mission completion is claimed by these checks.
- Implemented hero-gate collapse, destroyed-room replication/redraw, ruins model selection and suppression of later party spawns at collapsed gates. All 67 shipped collapse commands and both destroyed model sets pass a local headless check; seven new synthetic tests pass. The Level 7 adjacent-wall target uses a documented inferred compatibility rule. Original animation/audio and campaign progression remain unverified.

- Scripted objective display now reaches the intended local/network player and remains available in the objective panel alongside the briefing. Nine new tests pass, the owned Level 19 action/text dispatch check passes, and a native panel preview with original assets was inspected. Objective callbacks introduced protocol 2; the current protocol is 8; live multiplayer transport and persisted/reconnected objective restoration remain unverified. The root-aware audit corrects the earlier claim about 59 unimplemented marker actions: 45 already use creature handlers; 14 player-off commands remain unresolved.

- Corrected creature level percentages so fractional gains affect wages, health, combat, movement and work rates. Level changes preserve carried gold and map-authored starting balances; generated creatures receive scaled defaults. Spell unlocks now use minimum-level thresholds, and movement receives updated speed. Over-capacity pickups no longer duplicate gold. Seven new synthetic regressions pass; a real-data audit exercises 30,360 level transitions across 3,036 definitions in all 66 maps. This establishes data/ECS consistency, not original-engine rounding parity, combat balance or full gameplay acceptance.

- Implemented the payday clock, per-creature debt/advance ledger, physical room collection, gold gifts, unpaid anger and trigger cycle observation. The suite now has 121 tests; owned Levels 1, 2 and 17 pass real navigation/arrival/collection checks. Salary replication introduced protocol 4; the current protocol is 8. See [PAYDAY.md](PAYDAY.md) for evidence and explicit parity gaps, including gift gratitude, presentation, persistence and full mission acceptance.

- Starting funds now use a separate reserve, spent before room treasure. Wide keeper balances preserve funds through capture; individual room purchases remain bounded. All 66 maps/234 funded player entries pass the initial-reserve audit. See [STARTING-GOLD.md](STARTING-GOLD.md) for transaction, serialization and callback evidence and limitations.

- Computer keepers now retain authored AI identity and execute economy decisions on the authoritative simulation thread. All 66 maps pass profile checks; two seeded twelve-minute production-system headless TrapTime runs verify normal mining/worker excavation/claiming and paid rooms for both opponents. The full suite has 262 tests. This is the economy foundation, with remaining opponent strategy and coverage recorded in [KEEPER-AI.md](KEEPER-AI.md).

- Failed assigned-task paths now retry once per game second, with immediate retries for changed tasks/locations. A seeded headless Level 10 comparison shows lower simulation cost; the benchmark and limitations are in [PERFORMANCE.md](PERFORMANCE.md).

- Dungeon-heart combat now selects valid enemy hearts, approaches reachable attack positions, applies attributed damage and performs authoritative destruction/defeat with conserved treasure and mana. Sixteen new tests and isolated full-health combat checks on Levels 1, 17 and TrapTime pass. Defeat stops portal recruitment and income. Protocol is now 5. Campaign victory conditions, rendered destruction, mode-specific rewards and eliminated-army behavior remain open; see [DUNGEON-HEART.md](DUNGEON-HEART.md).

- Campaign player-defeat conditions now match recorded killer attribution, with per-player first-result retention and null-heart serialization. All 72 shipped conditions across 18 maps pass an owned-data predicate audit; isolated Level13/16 loss actions and Level17 death counters pass. See [DUNGEON-HEART.md](DUNGEON-HEART.md) for original-executable predicate evidence and limits.

- Terminal outcome/time now survive local/hosted callbacks, the end screen, teardown and debriefing. Client campaign attempts and completion are persisted once, separated by mode and branch, with legacy main-campaign counter fallback. Native loss, win and continue-to-debriefing checks use original assets and assert bound UI labels; they inject outcomes rather than play missions. Camera cleanup and delayed speech/transition callbacks respect session lifetime. Asset paths resolve correctly through a symlinked converted-data root. See [GAME-RESULTS.md](GAME-RESULTS.md).

- Camera sweeps now preserve authored 30 Hz sample timing, including single-frame paths, stationary rotation/FOV changes and repeated-position holds. Stop/cancel completion is idempotent. All 80 converted owned paths and 34,346 sample poses pass headless playback checks; native single-frame/stationary/moving checks are included in result-flow verification. See [CAMERA-PATHS.md](CAMERA-PATHS.md).

- Campaign level/happy/angry creature counts now use verified native predicate semantics, including minimum levels, species filters and worker exclusion. Five new tests pass; all five authored conditions unlock the expected isolated speech/score actions across five maps. See [CREATURE-COUNTS.md](CREATURE-COUNTS.md) for evidence and acceptance limits.

- Creature deaths now update kill/loss statistics and the two campaign kill predicates. Thirteen new tests pass; all 27 shipped conditions in eight maps pass production death-to-trigger checks, with representative child actions. Statistics serialize on Keeper and protocol 8 includes the attribution component; complete saves, debrief delivery and remaining original attribution edge cases are still open. See [CREATURE-KILLS.md](CREATURE-KILLS.md).

Fortified-wall breach events now execute the two shipped Level 1/Pressure flag actions. See [DUNGEON-BREACHES.md](DUNGEON-BREACHES.md) for the verified event sequence, six regression tests and remaining original-task-path acceptance.

Save/load now has a shared simulation capture boundary, five concurrency regressions and an owned Level 1 live-thread probe. Capture time is excluded from game clocks; interrupted and concurrent shutdown wait for all workers. The authoritative state inventory and remaining persistence/restore work are in [SAVE-LOAD.md](SAVE-LOAD.md).

Entity snapshots now encode all 65 component types with explicit schemas and reconstruct independent stores while retaining IDs and allocator state. Nineteen new tests and twelve live ECS round trips across four owned maps pass. This does not restore a playable simulation; controller/task/trigger/RNG/session state and complete save-file handling remain open in [SAVE-LOAD.md](SAVE-LOAD.md).

Keeper and hand persistence now preserve complete keeper values, research object sharing and carried-entity order with explicit versioned sections. Clock/timer helpers retain saved values through startup. Fourteen regressions and twelve live owned-map captures pass; full-world restore and saved-game UI remain pending. See [SAVE-LOAD.md](SAVE-LOAD.md).

Level/time and species interaction-history sections now round-trip with staged metadata binding; restored payday processing retains its cycle without charging previous wages. Ten regressions, twelve live level/statistics captures and three owned-map payroll/collection checks pass. Complete world/session restore remains open in [SAVE-LOAD.md](SAVE-LOAD.md).

Map/room persistence now binds saved tile and room IDs without creating furniture or resetting spawn/storage components. Nine regressions and all 66 owned maps (1,038 rooms, 15 controller classes, 66 matched withdrawals) pass. Storage validation also exposed and fixed spellbook removal accounting and library/hatchery cleanup ordering. Map controls and full simulation reconstruction remain open in [SAVE-LOAD.md](SAVE-LOAD.md).

Player-control snapshots now preserve mana/gold limits, primary hearts, portal state, ordered room/creature indexes and research object relationships. Timed tile flashes and pending creature-count updates restore without initialization callbacks. Ten new regressions and twelve live owned-map captures pass; the remaining world/task/system/trigger/session restore contracts are still open in [SAVE-LOAD.md](SAVE-LOAD.md).

Stored spellbook observations now restore pending discovery/removal/transfer updates without replaying research or spell upgrades. Object-controller binding skips the map's initial object loading and retains the restored allocator and clock for future creations. Seven regressions and twelve owned-map captures pass, including six stored-book round trips and 681 object wrappers. Chicken FSMs, remaining systems and full world/session assembly remain open in [SAVE-LOAD.md](SAVE-LOAD.md).

Chicken FSM snapshots now preserve current, previous and global states, motionless time, active controller identity and pending animation-query events. Restoration avoids hatching eggs or selecting routes during binding and resumes ordinary callbacks on the next tick. Eight regressions and twelve live chicken-controller captures pass. Creature task/FSM state and the remaining full-world restore owners are still open in [SAVE-LOAD.md](SAVE-LOAD.md).

Creature-controller snapshots preserve FSM history, motionless time, payday search deadlines and failed task/heart-route retry state. Staged binding validates saved species and distinct task-node identities, including objective children sharing runtime IDs, without callbacks or component writes. Seven regressions and 492 live controller-value round trips pass; task graph and party restoration remain open in [SAVE-LOAD.md](SAVE-LOAD.md).

Creature factories now restore all live cached controllers without spawning authored creatures again. The AI system binds its saved active membership and reconciles pending removals/additions on the next tick, preserving exit actions and worker-queue ordering. Five additional regressions pass (308 total tests); full task/party/world reconstruction remains unfinished.

Party snapshots now preserve cached leadership, ordered members, old spawned instances and shared current/runtime indexes. Party IDs use an independent per-world allocator; restore performs no spawning or leader selection. Six regressions, 108 live party-node round trips and 33 spawned-index entries pass (314 total tests). Executable task graphs, triggers, randomness and complete world continuation remain open.

Task allocation now belongs to each world and propagates to nested rescue/objective tasks. Six tile-work implementations restore their timestamps, targets and assignee progress without assignment callbacks; five regressions compare real future digging/claiming/repair effects. All 319 tests and 64 live tile-task round trips pass. Other task types, objective graphs, manager queues/reservations and complete world continuation remain open.

Room-work persistence now covers lair claims, wage collection, research, training and gold delivery, including completion flags and saved training parameters. Removed rooms remain shared task dependencies without recreating storage or entities. Staged task-manager construction skips new-game scans and callbacks and cannot start before complete restoration. Research tasks now reject sold/captured/destroyed/full libraries. Seven new regressions pass; the full suite has 326 passing tests, and twelve owned-map captures restore 18 room-task nodes alongside 64 tile nodes and 620 creature controllers. Full task graphs, manager roots/reservations/query state, other systems and complete saved-session continuation remain unfinished.

Entity-targeted task persistence now covers sleep, eating, rescue, enemy capture, creature transport, object fetching and object storage. Deferred target binding preserves creature/task cycles; object tasks retain cached definitions across changed or removed entities. Five new regressions compare future effects, generated assignments/IDs, hauling cleanup, retry timing and malformed saves. All 331 tests pass, and twelve captures across four owned maps restore 39 entity-task nodes after controlled loose-book/unconscious fixtures drive production task creation. Objective queues, complete manager/query/continuation state and whole-session restoration remain open.

Objective persistence now covers movement, waypoint and kill-player tasks. A unified executable graph preserves shared wrappers, ordered child queues, worker-party flags and detached creature retry references, and binds real creature/task cycles without planning or callbacks. Five new regressions pass; all 336 tests are green. Twelve owned-map captures restore 558 objective nodes plus 121 tile/room/entity nodes and 618 creature controllers. Decorators are covered by a synthetic planned-route test; the short owned captures contain none. Manager root roles, queues/reservations, query events, continuations and whole-session restoration remain unfinished.

Task managers now restore registered roots, ordered player queues, room reservations, pending work and last-observed entity-query state. Explicit idle continuations replace queued callbacks. Activation installs listeners once; the first resumed tick reconciles changes without replaying initialization, and shutdown/failure removes listeners. Eight new regressions compare subsequent assignments, cleanup and rescue/fetch creation after repeated loads, reject invalid ownership and cover listener lifecycle. All 344 tests and 12 owned-map manager binding/activation checks pass. Other systems, triggers, RNG, session state, complete save files and resumed-world acceptance remain unfinished.

Mana and creature-experience sections now retain partial update intervals, observed query membership, pending mana changes and accumulated working time. Loading does not credit resources or award experience early. Seven regressions compare subsequent credits, training resets and actual level-ups across repeated loads; all 351 tests and twelve live bindings on four owned maps pass. Other systems, triggers, RNG, session assembly, complete save files and resumed-world acceptance remain unfinished.

Keeper AI decision snapshots now retain fractional scheduling, remembered gold, pending room plans and progress/stall counters. Binding validates map/owner references without issuing commands; ordinary updates still prune stale deposits and invalidate plans. Seven regressions compare future decisions and paid construction through repeated loads. All 358 tests and twelve live bindings, including 15 AI state entries, pass. This preserves existing economy behavior; broader keeper strategy and complete saved-session restoration remain unfinished.

Creature recruitment now preserves its clock, cached limits/cooldowns and ordered shared entrance references. Restoration binds without initial scans or spawns; activation and shutdown manage room listeners once and roll back failed registration. Removed entrances are skipped before accessing deleted components. Seven regressions, all 365 tests and twelve live bindings of 66 entrance references pass. Random-choice continuity, chicken spawning and complete restored sessions remain unfinished.

Chicken spawning now retains cooldowns, cached coop-room associations, ordered coop choices and pending per-player populations. Loading avoids initialization callbacks, and the next tick reconciles saved observations once. Listener/query cleanup, destroyed-room guards, empty-coop selection and transfers to new population owners are covered. Seven new regressions, all 372 tests and twelve owned-map staged bindings pass. Matched tests supply equal external RNG inputs; full RNG and world/session restoration remain unfinished.

Decay, torture and death systems now retain their elapsed clocks and pending death-cleanup membership. Loading performs no decay, conversion or possession cleanup; subsequent ticks retain deadlines and process pending deaths once. Six regressions, all 378 tests and twelve owned-map staged lifetime bindings pass. Nonempty death continuation is tested synthetically; these are still subsystem bindings, not a complete saved-game session. See [SAVE-LOAD.md](SAVE-LOAD.md).

Slap persistence now retains expiry clocks, cached start times and separate creature/object query observations. Loading avoids replaying damage, anger, statistics and efficiency bonuses; pending events resume in the original phase order. Six regressions, all 384 tests and twelve owned-map bindings with ordinary active slaps pass. Health/recovery and full saved-session restoration remain unfinished. See [SAVE-LOAD.md](SAVE-LOAD.md).

Health persistence now retains elapsed time, cached health/death settings, observed memberships and pending regeneration-reset/recovery events. Loading performs no healing, awakening, death or kill accounting. Seven regressions, all 391 tests and twelve owned-map health bindings pass. Full saved-session assembly and the remaining system/trigger/RNG contracts are still open. See [SAVE-LOAD.md](SAVE-LOAD.md).

Falling and dungeon-heart construction now preserve clocks, completion and shared furniture references without replaying the startup hiding scan. Live object-role validation rejects malformed construction targets, while removed plugs remain valid references. Falling publishes independent position vectors. Seven regressions, all 398 tests and twelve owned-map bindings of 27 plug references pass; debris effects and full restored sessions remain unfinished. See [SAVE-LOAD.md](SAVE-LOAD.md).

Creature-view persistence now retains transition time, and bridge restoration preserves saved decay deadlines without initialization. Door, mood, loose-object and hauling queries rebuild without effects. Seven continuation regressions, strengthened nonzero slap-anger assertions, all 405 tests and twelve owned-map staged bindings pass. Movement, triggers, RNG and full saved-session reconstruction remain unfinished. See [SAVE-LOAD.md](SAVE-LOAD.md).

Movement persistence now preserves active route geometry, cached targets, all steering dynamics and pending query events. Nine regressions cover repeated walking/turning/arrival and changed navigation, speed and membership. Private route/position vectors prevent aliasing, and a reproduced angular clamp direction bug is fixed. All 414 tests and twelve owned-map binding checks (263 live bodies) pass. Complete saved-session restoration, triggers, simulation RNG and whole-game acceptance remain unfinished. See [SAVE-LOAD.md](SAVE-LOAD.md).
