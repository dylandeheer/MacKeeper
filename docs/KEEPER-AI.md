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
- When funds are at or below the authored threshold or pending room price, prioritize up to four exposed gold deposits adjacent to connected owned territory. Mining may extend outside the room construction rectangle.
- Remember gold deposits within an eligible owned creature's authored perception range when their terrain has the reveal-through-fog flag. The strict Euclidean range and closed-door exclusion match the existing fog controller. Dead, captive, held and foreign-controlled creatures do not contribute.
- Search for a route to known gold through diggable land, excluding foreign land, water, lava and non-target impenetrable terrain. Tag the first excavation step; ordinary workers must open and claim the route. If no known deposit is reachable, prospect an exposed ordinary-earth tile, with at most four pending frontier selections.

The controller currently constructs the first missing instance of each eligible room type. A pending plan with no newly buildable tiles for 60 decisions is reconsidered. Terrain connectivity is conservative and does not yet model doors or worker-specific reachability; disconnected or constrained dungeons need further strategy work.

## Evidence

Seventeen synthetic economy tests cover campaign/lobby identity, human/heartless exclusion, discovered build order and prices, decision timing, excavation/claim sequencing, enemy/impenetrable restrictions, alternate sites around blockers, pending-plan revalidation, bounded prospecting, income outside construction bounds, perception eligibility/range, closed-door perception and routes to gold. The full suite contains 144 tests. Mocked decision tests are separate from production-world integration.

Run `scripts/verify-keeper-profiles.sh` with JDK 25 and a locally owned game-data path. All 66 shipped maps pass: 142 keeper slots include 23 authored AI keepers across 10 maps. This validates identity and profile references, not original AI equivalence.

Run `scripts/verify-keeper-economy.sh` for twelve simulated minutes on the shipped TrapTime map with random seeds 0 and 1. It invokes the exact production logic, animation-state and steering systems sequentially without background threads, failing on system exceptions. Both opponents must mine income and construct rooms, while human player 3 must receive no construction commands. In the verified runs, AI player 4 constructed seven 12-tile rooms in each seed; AI player 5 constructed three 25-tile rooms with seed 0 and one with seed 1. All use ordinary mining, excavation, claiming and paid construction. These longer runs also exposed and now cover a null-creature payday task cleanup failure.

Each run took approximately nine seconds on the local M1 Max; this is a headless execution observation, not a gameplay performance benchmark. Seeds control the primary random generators but do not establish whole-engine determinism. The smoke does not prove victory, full strategy, rendering, live network behavior or concurrent execution correctness. Wider map and profile coverage remains required.

## Remaining strategy and fidelity work

- Reach and claim portals/neutral rooms, explore and evaluate further income sources, replace workers, and expand rooms according to demand and authored expansion policies.
- Reserve enough money for wages and recovery; recover from absent/unreachable income and constrained worker access.
- Handle blocked worker paths, passages, doors, bridges and constrained construction sites. Follow authored corridor/spacing preferences and rebuild delays.
- Implement creature management, defense, attack selection, strength thresholds, rallying, spells, retreat and prisoner policies.
- Honor lobby-selected archetypes beyond identity; the economy currently uses the attached map profile. Mapping speed to seconds, minimum 3-by-3 rooms, rectangular rounding and all-zero unrestricted build bounds are explicit implementation choices pending original-engine comparison.
- Add authoritative AI knowledge/visibility rather than querying full terrain for footprint feasibility. Gold targets use creature perception, but route/footprint feasibility and remembered-deposit depletion read live terrain; this is not a complete fog-of-war knowledge model.
- Persist decision clocks, pending construction and future strategic state in the complete versioned save contract.
- Validate every campaign opponent and representative skirmish layouts, beyond the two seeded TrapTime runs. Recheck gameplay with native rendering and multiplayer.
