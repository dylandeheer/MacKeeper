# MacKeeper completion plan

The active objective is the full game, with original-data compatibility and a usable Mac experience. A passing build or a first-level launch is a checkpoint, not completion. The initial port is recorded in IMPLEMENTATION.md; this document orders the remaining work and its acceptance evidence.

## Work order and evidence

1. **Simulation foundations and campaign inventory.** Audit the real shipped maps and trigger types; fix missing economy and creature-state paths. Verify gold conservation, task reassignment, room destruction/ownership, hunger, sleep, combat and incapacitation with simulation tests. Record which original behaviors still need comparison.
2. **Campaign scripting and progression.** Implement used trigger actions/conditions, objectives, messages, party lifecycles, win/loss and campaign unlocks. Run every campaign branch and bonus map, recording completion evidence and differences. Parsing or loading a map alone is insufficient.
3. **Computer keeper strategy.** Use the existing map, task, room and spell controllers for excavation, income, essentials, expansion, recruitment, defense and attack. Honor map availability and AI parameters. Test decisions with synthetic maps, then play representative skirmishes and campaign opponents.
4. **Save/load.** Inventory authoritative state before choosing a format. Persist entity references, map and room edits, players, task state, triggers, timers, random state and progression at a consistent simulation boundary. Version and validate the format; use atomic writes. Demonstrate equivalent resumed outcomes, successful repeated round trips, and safe rejection of truncated/incompatible saves. Wire actual UI actions after the complete state contract exists.
5. **Mac usability and media.** Verify original audio, movies, graphics and minimap; add discoverable keyboard/trackpad controls, scalable UI, actionable setup/runtime errors, and reliable pause/quit/window transitions. Preserve a classic control option where interactions change.
6. **Performance and multiplayer.** Measure loading time, frame time, simulation cost and memory on representative large dungeons. Fix measured bottlenecks with repeatable comparisons. Validate supported network sessions, ownership, disconnect/rejoin and version compatibility.
7. **Release.** Run the complete regression suite, campaign and skirmish acceptance, architecture-specific packaging, license/dependency audit, independent review and distribution checks. Developer ID signing/notarization and Intel hardware evidence require the corresponding external resources; keep them explicitly open until actually verified. Never include proprietary game data in Git or app distributions.

## Current source findings

- `CreatureController.dropGold()` was empty despite being called by working imps when full and no treasury task can be assigned. The first correction now drops bounded piles and debits each completed pile; five regression tests pass.
- Room capture now transfers metadata, storage ownership and gold/capacity consistently with build/sale and hand actions. Thirteen regression tests and a real Level 17 transfer smoke test pass; room-specific occupant behavior and full mission progression remain unverified.
- Hero-gate collapse now handles both shipped gate types and their destroyed-model redraws. Seven regression tests and a 67-command/17-map headless check pass. The Level 7 adjacent-wall rule is inferred; original audiovisual timing and mission progression still need validation.
- Scripted objective text is now displayed and retained within the current client level, with local/hosted callback tests and a real Level 19 text/dispatch check. Root tracing corrected the marker inventory: 45 commands use existing creature handlers, while 14 player-off commands still need implementation after their semantics are established. Payday remains absent.
- `GameController` constructs creature/task/trigger systems, but no strategic computer-keeper system is present. Lobby AI selection alone does not implement an opponent.
- `TriggerControl` and specialized controls contain unimplemented actions and conditions. The [shipped-map inventory](CAMPAIGN-AUDIT.md) is used to prioritize actual campaign dependencies; source TODO counts are not a completeness metric.
- Save/load must cover server simulation state, not merely serialize view state or menus. No complete implementation is present yet.

## Verification discipline

Keep synthetic tests asset-free. Real-game smoke tests and campaign validation read the user's local Steam data. Record evidence separately for unit tests, CI, parser audits, actual gameplay and release packaging; do not substitute one for another.
