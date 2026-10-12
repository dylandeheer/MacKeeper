# Save/load implementation and state contract

Save/load is incomplete. A consistent **in-memory simulation capture boundary**, explicit component codecs and staged entity-store restoration are implemented. There is no complete game-save container, restored simulation or functional Save/Load menu yet. Existing `Savable` implementations and network component serializers do not cover a running game.

## Implemented boundary

`GameLoopManager.captureSnapshot` waits for the current logic, animation and steering updates and player commands to finish, then excludes all of them while a callback copies detached state. All three loops share `SimulationGate`; normal updates retain their existing concurrency. `GameServerState` routes selection, construction, sale, interaction, hand actions, spells, doors, traps, gold and cheats through the same gate. Pause/resume and lifecycle admission are also guarded.

Captures are rejected before all systems initialize and after shutdown starts. Nested captures, capture from an update, and commands issued inside capture fail immediately rather than deadlock. Capture duration is subtracted from each loop's clock, including failed captures, so saving does not advance wages, hunger or combat. Waiting for an already-running update is not subtracted. A callback must copy values; returning mutable controllers/components would escape the boundary. Encoding and file I/O must happen afterwards.

Shutdown closes admission, signals every loop, then joins outside the gate so workers can finish cleanup. Interrupted callers still wait and retain their interrupt status. Concurrent stop callers wait for the same completed shutdown. This prevents a save/quit race from leaving live workers behind.

This gate does **not** yet capture session-owned camera-transition acknowledgements or client presentation state. Local/hosted services change transition state outside the server command listener, and trigger conditions can observe it. That state needs an authoritative restore contract before complete saves can use this boundary.

## Implemented entity-state section

`ComponentSnapshots` explicitly registers all 65 concrete component types and every field, including inherited attack fields and private AI state. A captured snapshot privately owns its copied values; neither the live component nor a restored component can mutate it. Each restore returns fresh vectors, collections and components. Strings, nulls, empty collections, false map entries, wide integer balances and entity references remain distinct. Creature/chicken AI states and the integer-backed melee attack type use enum names in the wire format.

`GameEntityData` is now the authoritative store in local and hosted sessions. It retains zero-based IDs, empty entities, removed-ID gaps and the next allocation value. Staged restore builds a separate store and cannot replace the current session. References to removed but previously allocated targets survive, because ordinary systems can retain those until their next update; references beyond the allocation counter fail validation. Per-field semantic validation, such as whether a required room must exist, belongs to the complete world reconstruction contract and is still pending. The allocator cannot wrap at exhaustion. The inherited entity string index is initialized explicitly for the subclass; current component schemas contain actual strings, not string-index IDs.

`EntitySnapshots` encodes/decodes this ECS section after detached capture. It rejects negative/out-of-range IDs, duplicate entities or components, unknown types/versions, trailing/truncated data, malformed UTF-8, invalid booleans/enums, non-finite numbers and excessive lengths. The format uses explicit stable type identifiers and explicit field order; it does not use reflective class loading, Java serialization, network registration order or enum ordinals.

The current prerelease section format is big-endian: `MKES`, section version 1, next entity ID, entity count, then entity IDs and length-prefixed component records. Each component has `MKCS`, container version 1, a type identifier, an explicit schema version and its declared fields. Format changes require version changes and explicit migrations when supported. Bounds are 64 MiB per ECS section, one million entities, 1 MiB per component, 65,536 bytes per UTF-8 string, 65,536 list entries and 256 player-flag entries. These structural checks are not a checksum or proof that arbitrary edited values form a valid game. A complete save still needs data identity, whole-container integrity, per-owner semantic validation and atomic file replacement.

## Authoritative state inventory

The source inventory includes these owners. Entity-store capture/codecs/staged restore are implemented; other owners still need explicit capture, validation and restore code. Derived indexes may be rebuilt only if doing so preserves the resumed behavior and cannot repeat initialization side effects.

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

The component directory currently has 65 concrete component types, an abstract `Attack` base and the `ViewType` enum. The component codec covers all concrete types, including inherited and private fields; a source-inventory regression fails if a new component or field lacks coverage. Network registration is not a complete schema.

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

Five asset-free concurrency regressions cover parallel updates and exclusion, failed callbacks and reentry, three real loop clocks, capture while paused, and interrupted/concurrent shutdown. Nineteen entity/component persistence tests cover schema completeness, every field, detached mutation, named enum fixtures, references, allocator preservation/exhaustion/concurrency, repeated round trips and malformed input. The full suite has 238 tests.

`scripts/verify-snapshot-boundary.sh "/path/to/Dungeon Keeper II"` runs the production logic, animation and steering threads on the owned Level 1 data. Ten 100 ms captures hold a detached digest of the game clock, flags, timers, players, tiles and live entity state unchanged. A one-second capture is excluded from the next game-clock delta; the check waits for the actual authored 4 Hz logic tick. Paused capture and a blocked player command also pass. An appended delayed worker verifies both interrupted and concurrent manager shutdown callers wait for cleanup and preserve interrupt status.

These are capture-boundary checks, not a complete-state hash, save round trip, original-game parity, rendered acceptance or live network acceptance.

`scripts/verify-entity-snapshots.sh "/path/to/Dungeon Keeper II"` captures three live snapshots each on Levels 1, 17, TrapTime and 10, encodes outside the simulation gate, decodes and restores into independent stores. All 12 ECS round trips pass byte comparison after recapture; allocation resumes at the saved next ID, and continued original play plus staged-store mutation cannot change the captured snapshot. The measured run covered 352,150 component records and 46 live component types; synthetic fixtures cover all 65 types. Capture calls took 5–65 ms in this short run on the test Mac, including boundary wait, without establishing broad save-performance acceptance.

These are entity-state round trips. Restored stores have no running controllers, tasks, triggers, RNG or session. Resumed gameplay equivalence is still unproven.
