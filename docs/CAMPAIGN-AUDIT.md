# Shipped-map audit

Date: 2026-10-12. Input: locally owned Steam base and English data. The report contains counts and map identifiers only; original game assets remain outside this repository.

The parser loaded all **66 maps**, including **24 campaign variants**, **5 secret maps** and **7 My Pet Dungeon maps**. All parsed child/sibling trigger links refer to existing nodes and are acyclic. This validates parsing and link integrity only: it does not prove gameplay compatibility, trigger semantics, victory or progression.

## Confirmed campaign dependencies on unfinished code

| Trigger | Occurrences | Campaign maps | Source finding |
|---|---:|---:|---|
| `MAKE_OBJECTIVE` (player-scoped off) | 14 | 5 | PlayerTriggerControl.makeObjectiveOff remains empty; player-target semantics need verification. |
| `LEVEL_PAY_DAY` | 1 | 1 | Implemented cycle observation and physical collection; Level 1 authored timing passes a headless movement/transaction check. Mission progression and original parity remain unverified. |

These are confirmed examples, not an exhaustive support matrix. Additional media, creature, party and player actions require semantic review. The room ownership actions occur in `Level17.kwd`; the payday condition occurs in `level1.kwd`.

## Implemented dependency: room ownership

`CHANGE_ROOM_OWNER` (three occurrences in `Level17.kwd`) now calls the same capture path as normal room claiming. It transfers tiles, room metadata, furniture, stored objects, treasury balances and capacity. Capture is serialized with gold pickup/drop and room construction/sale/destruction. Captured treasure does not increase mined-gold statistics. Losing the selected dungeon heart selects another owned heart when available; camera and hero-objective consumers handle a missing heart.

Thirteen asset-free regression tests cover scripted and normal capture, repeated requests, inactive targets, adjacent different-owner rooms, room counts, queued pickup/drop/sale, stale deposits, multi-room gold conservation, inactive-slot gold drops and heart/objective handling. A headless simulation using the real Level 17 data applied all three transfers and verified ownership, furniture, gold conservation and idempotence. It did **not** validate trigger timing, mission progression or victory.

With JDK 25 and the same local data, reproduce the real-map check with:

```sh
scripts/verify-room-ownership.sh "/path/to/Dungeon Keeper II"
```

## Implemented dependency: hero-gate collapse

`COLLAPSE_HERO_GATE` now marks the entire 2×2 or 3×1 gate destroyed while retaining its footprint, room identity and ruins. Later party spawns at a collapsed gate are suppressed, and destroyed rooms are excluded from claim tasks. Runtime room state reaches the renderer through the room entity update path, including changes that arrive separately from tile updates. Both gate constructors use the original destroyed models; the 3×1 path also fixes the missing underscore in the inherited destroyed-wall filenames.

Seven synthetic tests cover repeated/invalid requests, complete-footprint state, party lifecycle, live redraw/model selection, and the adjacent-wall rule below. A headless check applied all **67 commands across 17 shipped maps** (59 commands in 14 campaign maps), retained each gate's footprint and verified zero health/idempotence. It also successfully constructed both destroyed gate types using the user's converted models. No retail assets are included in the tests or repository.

**Compatibility inference:** `level7.kwd` trigger 65441 targets reinforced wall `(35,41)`, beside a 3×1 gate at `(32..34,41)`. The resolver therefore accepts a solid-wall target when exactly one 3×1 gate is cardinally adjacent. Floor targets and ambiguous matches do nothing. The data layout supports this rule, but its exact equivalence to the original engine remains unverified.

```sh
scripts/verify-gate-collapse.sh "/path/to/Dungeon Keeper II" "/path/to/Converted"
```

The smoke check proves controller state and model construction, not actual campaign progression, original animation/sound timing, network-session behavior or rendered visual fidelity. Those remain acceptance work.

## Objective display and corrected marker inventory

The initial type-only audit incorrectly attributed all 59 `MAKE_OBJECTIVE` commands to the player handler. Tracing each command from its actual level/player/thing/party-member root finds **45 creature-scoped commands** (28 kill, 6 convert, 11 off) and **14 player-scoped off commands** across five maps (`level11A/B/C`, `level12`, `level18`). CreatureTriggerControl already adds/removes PlayerObjective components and CreatureFlowerControl draws their icons. That existing path still needs mission-level validation; this inventory is not proof of original marker semantics. The player-scoped off handler remains incomplete.

`DISPLAY_OBJECTIVE` in `Level19.kwd` (trigger 65497, player 3, text ID 8, no camera action point) now sends an objective callback through local or hosted sessions. The client updates Nifty on the render thread, handles late callbacks after detach, and displays the instruction above the existing briefing. Closing/reopening the panel and returning from the resumable end-game screen retain the latest instruction. New level controllers start without a previous instruction. Missing/out-of-range text IDs preserve the last valid instruction. An optional nonzero action point still moves the camera. Network protocol version 2 rejects clients using the older session interface.

Nine synthetic tests cover trigger dispatch, recipient isolation, local and hosted/client callback paths, optional/missing action points, panel reopening/replacement, missing text, and resumable cleanup. The full suite passes **81 tests**. The network routing test substitutes the transport; it does not prove a live multiplayer session. A native 1280×800 rendering of the production objective panel with owned Level 19 fonts, text and textures was inspected for readable text and overlap. This isolated panel preview is not a complete mission playthrough or original-engine visual comparison.

The reproducible audit below traces roots across all 66 maps, invokes the real display action through TriggerLoader and PlayerTriggerControl, and checks the referenced converted dictionary entry. It reports identifiers/counts only, without copying game text or assets into the repository. Save/load and reconnect restoration of objective state remain part of the wider unfinished state-persistence work.

```sh
scripts/verify-objectives.sh "/path/to/Dungeon Keeper II" "/path/to/Converted"
```

## Level and mood count dependencies

The previously stubbed level/happy/angry counts now implement the original predicates. All five shipped conditions in Level2, Level15a and MPD2/3/4 pass threshold and child-action checks; see [CREATURE-COUNTS.md](CREATURE-COUNTS.md). The inventory remains broader than the implemented subset. The fortified-wall breach path now passes both authored event conditions; another original event cause remains unresolved. See [DUNGEON-BREACHES.md](DUNGEON-BREACHES.md). Creature kill/loss conditions now have a 27-condition/eight-map audit and representative speech, flag and reinforcement checks; see [CREATURE-KILLS.md](CREATURE-KILLS.md).

## Reproduce

With `JAVA_HOME` pointing at JDK 25:

```sh
scripts/audit-game-data.sh "/path/to/Dungeon Keeper II" > game-data-audit.tsv
```

Build/parser diagnostics go to stderr. Stdout contains tab-separated map, trigger kind and count. The audit exits unsuccessfully on unreadable/malformed maps, unknown trigger types, missing linked nodes, cyclic links or an empty maps directory. It never modifies the input data.

## Campaign map files

`Level10.kwd`, `Level13.kwd`, `Level14.kwd`, `Level15a.kwd`, `Level16.kwd`, `Level17.kwd`, `Level19.kwd`, `Level20.kwd`, `Level6a.kwd`, `Level6b.kwd`, `Level9.kwd`, `level1.kwd`, `level11A.kwd`, `level11B.kwd`, `level11C.kwd`, `level12.kwd`, `level15b.kwd`, `level18.kwd`, `level2.kwd`, `level3.kwd`, `level4.kwd`, `level5.kwd`, `level7.kwd`, `level8.kwd`.
