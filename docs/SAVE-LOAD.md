# Save/load implementation and state contract

Save/load is incomplete. The implemented first step is a consistent **in-memory simulation capture boundary**. There is no save-file format, restored world or functional Save/Load menu yet. Existing `Savable` implementations and network component serializers do not cover a running game.

## Implemented boundary

`GameLoopManager.captureSnapshot` waits for the current logic, animation and steering updates and player commands to finish, then excludes all of them while a callback copies detached state. All three loops share `SimulationGate`; normal updates retain their existing concurrency. `GameServerState` routes selection, construction, sale, interaction, hand actions, spells, doors, traps, gold and cheats through the same gate. Pause/resume and lifecycle admission are also guarded.

Captures are rejected before all systems initialize and after shutdown starts. Nested captures, capture from an update, and commands issued inside capture fail immediately rather than deadlock. Capture duration is subtracted from each loop's clock, including failed captures, so saving does not advance wages, hunger or combat. Waiting for an already-running update is not subtracted. A callback must copy values; returning mutable controllers/components would escape the boundary. Encoding and file I/O must happen afterwards.

Shutdown closes admission, signals every loop, then joins outside the gate so workers can finish cleanup. Interrupted callers still wait and retain their interrupt status. Concurrent stop callers wait for the same completed shutdown. This prevents a save/quit race from leaving live workers behind.

This gate does **not** yet capture session-owned camera-transition acknowledgements or client presentation state. Local/hosted services change transition state outside the server command listener, and trigger conditions can observe it. That state needs an authoritative restore contract before complete saves can use this boundary.

## Authoritative state inventory

The source inventory includes these owners; each row needs explicit capture, validation and restore code. Derived indexes may be rebuilt only if doing so preserves the resumed behavior and cannot repeat initialization side effects.

| Owner | State to preserve or reconstruct |
| --- | --- |
| Level identity and settings | Map identity and content hashes, data/version identity, mode, campaign branch, player assignments, level variables and any overrides. Refer to installed data; do not embed original assets. |
| Entity store | Stable entity IDs, the allocator's next unused ID, every present component and all entity references, including references to rooms, lairs, spell entities and hand contents. Component absence is meaningful. |
| `GameController.LevelInfo` | 128 flags, 16 timers and their active state, score, nullable time limit, payday cycle, participating keepers and action points. |
| `GameTimeController`, `GameController` | Elapsed time, tick count and each player's terminal result; do not rerun victory/loss or campaign accounting on restore. |
| `Keeper`, player controls | AI profile, gold/reserve/mined totals, mana, defeat/killer and kill/loss counters, alliances, availability/research progress, possession, ordered hand stack and capacity. `Keeper.write` currently omits possession and the bound map `Player` definition; player control state is separate. |
| Map and room controllers | Altered tiles, ownership, selected/flashing tiles, room topology/IDs, destruction, furniture IDs, occupants and storage/reservations. Restore room bindings without constructing duplicate furniture or resetting initial money. |
| `TaskManager` and tasks | Queues, stable task IDs and next ID, entity assignments, assignees, execution progress, room-capacity reservations, job-specific targets and pending unemployment callbacks expressed as data. `TaskComponent` alone cannot recreate these. |
| Creature/chicken controllers | Current and previous FSM states, state time, motionless duration, next payday check, failed-path retry state, party leadership/membership/objective and spawned-party identity. Entering a state during restore must not repeat its effects. |
| `KeeperEconomySystem` | Plans, discovered gold/frontiers, current construction decision and decision deadlines, in addition to authored AI settings. |
| Logic and animation systems | Payday cycle, periodic update accumulators, creature/chicken/entrance cooldowns, hunger/mood/mana cadence, decay/fall progress and dungeon-heart construction pieces/stage. |
| `MovementSystem` | Steering velocity/angular state and behavior/target/path progress currently held outside components. Rebuild lookup maps; do not silently reset motion unless equivalent resumed behavior is demonstrated. |
| Trigger controllers and trees | Level, player, party, creature, object, door and action-point roots; consumed actions, repeat counts, traversal cursor/last-child state, payday observations and initial cycle. Recreating trees from the map replays one-shot actions. |
| Random generators | Simulation-owned reproducible state. Current `Utils.RANDOM` and `FastMath.rand` are global and also used for visual choices; isolate simulation randomness before claiming equivalent resumed outcomes. |
| Session and client state | Player ownership, pause, objective/HUD state, fog/exploration, camera/transition semantics and campaign result delivery. Recreate listeners, transports, views and entity sets instead of serializing live callbacks or connections. |

### Component schema coverage

The component directory currently has 65 concrete component types, an abstract `Attack` base and the `ViewType` enum. A save codec must explicitly cover all concrete types, including inherited and private fields; network registration is not a complete schema.

| Group | Concrete types |
| --- | --- |
| Creature identity, behavior and needs | `CreatureAi`, `CreatureComponent`, `CreatureEfficiency`, `CreatureExperience`, `CreatureFall`, `CreatureHunger`, `CreatureImprisoned`, `CreatureMood`, `CreatureRecuperating`, `CreatureSalary`, `CreatureSleep`, `CreatureTortured`, `ChickenAi` |
| Combat and lifecycle | `AttackTarget`, `CreatureMeleeAttack`, `CreatureSpell`, `CreatureSpells`, `Damage`, `Death`, `Decay`, `Fearless`, `Health`, `LastDamageSource`, `Regeneration`, `Slapped`, `Threat`, `Unconscious`, `WoodenBridgeDecay` |
| Navigation and control | `FollowTarget`, `HauledBy`, `InHand`, `Interaction`, `Mobile`, `Navigation`, `Objective`, `Owner`, `Party`, `PlayerObjective`, `Position`, `Possessed`, `Senses`, `TaskComponent`, `Trigger` |
| World, resources and spawning | `ChickenGenerator`, `CreatureGenerator`, `DoorComponent`, `DungeonHeart`, `Food`, `Gold`, `ImpGenerator`, `Mana`, `MapTile`, `ObjectComponent`, `Placeable`, `Portal`, `PortalGem`, `RoomComponent`, `Spellbook`, `Storage`, `Stored`, `TrapComponent` |
| Replicated view state | `CreatureViewState`, `DoorViewState`, `ObjectViewState`, `TrapViewState` |

Important encoding cases are nullable attack times inherited from `Attack`, private ordinal-backed creature/chicken AI states, `MapTile` player-keyed maps, entity-ID lists in `CreatureSpells`, paths of `Vector2`, positions of `Vector3f`, `Point`, enum values and nullable references. The on-disk format should use stable type/field identifiers and enum names, with explicit versions and bounded counts; Java field order, enum ordinals and reflective class loading must not define compatibility.

## Implementation sequence and acceptance

1. Implement explicit detached state records and codecs, with complete component coverage and reference validation. Add per-owner state capture/restore methods rather than serializing the controller object graph.
2. Separate new-game creation from restored-world construction. Restore IDs and state into a staged world; bind controllers, reservations and indexes without initial spawns, initial gold, state-entry side effects or replayed triggers. Isolate and restore simulation RNG state.
3. Add a versioned, bounded container with map/data identity and corruption detection. Validate the entire payload before replacing the live session. Write a temporary file, flush it and atomically replace the selected save; preserve the prior file on failure.
4. Compare resumed outcomes with uninterrupted play after repeated save/load cycles: mining/deposits, room edits/capture, hand contents, combat/incapacitation, paydays, AI planning, party spawns, fog, objectives and one-shot/repeating triggers. Reject missing assets, unknown versions/types, invalid references/counts, non-finite numeric values and truncated/corrupt files without damaging the current game.
5. Wire pause-menu save/load and useful error messages only after the world contract passes. Define host ownership and reconnect semantics before exposing multiplayer persistence.

## Verification, 2026-10-13

Five asset-free concurrency regressions cover parallel updates and exclusion, failed callbacks and reentry, three real loop clocks, capture while paused, and interrupted/concurrent shutdown. The full suite has 219 tests.

`scripts/verify-snapshot-boundary.sh "/path/to/Dungeon Keeper II"` runs the production logic, animation and steering threads on the owned Level 1 data. Ten 100 ms captures hold a detached digest of the game clock, flags, timers, players, tiles and live entity state unchanged. A one-second capture is excluded from the next game-clock delta; the check waits for the actual authored 4 Hz logic tick. Paused capture and a blocked player command also pass. An appended delayed worker verifies both interrupted and concurrent manager shutdown callers wait for cleanup and preserve interrupt status.

These are capture-boundary checks, not a complete-state hash, save round trip, original-game parity, rendered acceptance or live network acceptance.
