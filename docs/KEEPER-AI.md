# Computer keeper implementation

Computer-keeper strategy now has an authoritative economy controller. This is an integrated first part of the strategy system; defense, attacks and several other decisions remain unfinished.

## Identity and execution

Campaign players now retain their authored AI flag and type when converted into runtime keepers. Previously `Keeper(Player)` discarded both, leaving campaign opponents identified as human. Explicit lobby choices remain authoritative when map data is attached.

`GameController` runs `KeeperEconomySystem` after scripted player actions, on the simulation thread. The controller excludes human, hero, neutral, destroyed and heartless players. Commands share the world's construction/gold lock. Decisions are ordered by player ID and candidate ties by coordinates. Delayed frames issue one decision per eligible keeper rather than replaying a burst of commands.

## Economy behavior

- Read the map's build order, default room size, build rectangle, decision speed and mining threshold. Only discovered, buildable land rooms enter construction planning.
- Find a rectangular site adjoining territory connected to the heart. Reject enemy land, existing rooms, water, lava, impenetrable terrain and the same object/door/trap blockers used by player construction. Revalidate a pending plan when terrain, blockers or availability changes.
- Tag the exposed edge of the footprint. Ordinary imps excavate and claim it; the controller never grants terrain, ownership, rooms, gold or workers.
- Call the normal world construction transaction only when the entire footprint is claimed and affordable. It rechecks blockers, ownership and funds before building and charging.
- When funds are at or below the authored threshold or below the pending room price, tag up to four exposed gold deposits adjacent to connected owned territory. Remote deposits are not selected through solid terrain.

The controller currently constructs the first missing instance of each eligible room type. A pending plan with no newly buildable tiles for 60 decisions is reconsidered. Terrain connectivity is conservative and does not yet model doors or worker-specific reachability; disconnected or constrained dungeons need further strategy work.

## Evidence

Nine synthetic tests cover campaign/lobby identity, human/heartless exclusion, discovered build order and prices, decision timing, excavation/claim sequencing, enemy/impenetrable restrictions, exposed income targets, alternate sites around blockers, and pending-plan revalidation. The full suite contains 130 tests. Mocked decision tests are separate from production-world integration.

Run `scripts/verify-keeper-profiles.sh` with JDK 25 and a locally owned game-data path. All 66 shipped maps pass: 142 keeper slots include 23 authored AI keepers across 10 maps. This validates identity and profile references, not original AI equivalence.

Run `scripts/verify-keeper-economy.sh` for six simulated minutes on the shipped TrapTime map. It invokes the exact production logic, animation-state and steering systems sequentially without background threads, failing on system exceptions. The verified run produced construction callbacks for AI player 4: a 12-tile treasury, lair, hatchery and library (48 tiles total), with ordinary mining, excavation, claiming and paid construction. Human player 3 received no construction commands. The run took about 3.3 seconds on the local M1 Max; this is a headless execution observation, not a gameplay performance benchmark. Creature behavior includes existing randomness, so exact construction times/sites can vary.

AI player 5 did not construct during that verified run. Wider profile, layout and worker-strategy coverage remains required. The smoke does not prove victory, full strategy, rendering, live network behavior or concurrent execution correctness.

## Remaining strategy and fidelity work

- Reach and claim portals/neutral rooms, explore for further income, replace workers, and expand rooms according to demand and authored expansion policies.
- Reserve enough money for wages and recovery; avoid economic dead ends when no exposed income source exists.
- Handle blocked worker paths, passages, doors, bridges and constrained construction sites. Follow authored corridor/spacing preferences and rebuild delays.
- Implement creature management, defense, attack selection, strength thresholds, rallying, spells, retreat and prisoner policies.
- Honor lobby-selected archetypes beyond identity; the economy currently uses the attached map profile. Mapping speed to seconds, minimum 3-by-3 rooms, rectangular rounding and all-zero unrestricted build bounds are explicit implementation choices pending original-engine comparison.
- Add authoritative AI knowledge/visibility rather than querying full terrain for footprint feasibility. Income targeting is limited to exposed deposits, but footprint planning is not a complete fog-of-war knowledge model.
- Persist decision clocks, pending construction and future strategic state in the complete versioned save contract.
- Validate every campaign opponent and representative skirmish layouts, including the currently non-building second TrapTime keeper. Recheck gameplay with native rendering and multiplayer.
