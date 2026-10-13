# Campaign objective targets

The campaign target registry now supports keeper defeat targets and creature Kill, Imprison and Convert targets. It is shared by the level, has six stable slots, and participates in entity replication and local save/load. The objective panel lists occupied slots and can locate a target only when its current tile is visible to that client. Held creatures have no locatable position. Target labels are derived from the current registry and installed definitions; they are rebuilt after loading.

## Semantics and evidence

Inspection of the locally owned executable corrects an earlier interpretation of the map argument. In a **player** trigger, `MAKE_OBJECTIVE` ignores the creature-specific argument and adds the owner of that trigger as a defeat target. In a **creature** trigger, zero removes the first matching creature target; 1, 2 and 3 add Kill, Imprison and Convert respectively. Keeper and creature additions use the first empty slot. A full list ignores additions, and repeated additions of the same target occupy separate slots.

The inspected executable has SHA-256 `b5e4e2f6b76335e2a2e7f43bb6903bfc6f4170eabf04598955b00f512a47b449`. The relevant player dispatch is at `0x4c89e4`, creature dispatch at `0x49affa`, and six-slot getter/setter at `0x5096a0`/`0x5096c0`. These are observations from the owned binary, not a claim to have the original source. Executable bytes and disassembly stay local.

The registry also contains an extra integer per slot in the original engine. Its complete display/discovery semantics remain unresolved. MacKeeper's target list is a usability addition, not a pixel-exact recreation of an established original panel. Creature flowers display the first remaining target kind; duplicate removal therefore leaves a marker while another target remains. Stale references are removed when the creature object disappears or a keeper is defeated. This removes all stale duplicates, whereas the inspected original removal paths clear a first matching slot. Imprisonment or conversion does not automatically remove an entry: authored Off actions and object lifetime control removal.

## Implementation

- `LevelObjective` stores one occupied slot as an entity component. `LevelObjectives` owns authoritative additions, removal, validation and stale-target cleanup. Creature `PlayerObjective` components remain the derived marker consumed by the existing unit flower.
- Player and creature trigger handlers dispatch to `IGameController`; the creature handler cannot fall through into keeper handling on Off.
- `ObjectiveTargetsView` holds asynchronous entity-set subscriptions for entries and marked creature definitions, positions and held membership. Rendering and clicks read its local observations. Zay-ES synchronous entity lookup and initial watched-entity requests are excluded from this render path. Cleanup releases all subscriptions.
- The additive entity-component archive schema saves stable slots, target references and kinds. Restore rejects invalid or duplicate slots and inconsistent target kinds/player IDs. Network protocol **9** accounts for the new replicated component; this does not establish live multiplayer acceptance or reconnect support.

## Verification

Asset-free regressions cover shared capacity, repeated targets, first-match removal, hole reuse, marker retention, gone-target cleanup, invalid registries, parsed trigger dispatch, asynchronous view queries and archive continuation.

The owned-map audit inventories all **66 maps** and executes **14 keeper** and **45 creature** commands in **21 real map worlds**. Keeper commands occur in `Level11A/B/C`, `Level12` and `Level18`. The creature inventory is 28 Kill, six Convert and eleven Off commands; synthetic tests also cover Imprison. Each world passes a complete server archive write/read/restore comparison. Creature dispatch uses an explicit bound fixture entity, so this audit does not prove authored trigger timing, party spawning, mission progression or victory.

```sh
scripts/verify-objective-targets.sh "/path/to/Dungeon Keeper II"
scripts/verify-native-objective-targets.sh "/path/to/Dungeon Keeper II" "/path/to/assets/Converted"
```

On 2026-10-13, the muted native Level12 probe passed pause-menu objective routing, all six rendered rows, shared capacity, fog-aware locate, actual camera movement, fresh local archive loading and first-match removal. The 1280×800 panel was inspected: the briefing and targets occupy separate columns above a fixed Close control. Closing returns to the paused options menu. This run emitted no SEVERE messages; the wrapper reports any cold-load loop overruns separately from functional failures. Smaller-window usability, full performance acceptance, complete campaign playthroughs, original marker/discovery fidelity and real multiplayer transport remain open.

The panel's scroll offset is transient and is rebuilt at the top with a fresh HUD after loading. Slot contents and logical screen/pause state persist; the scroll offset is not part of the save contract.

The larger map also exposed an existing icon-list defect: changing child layout order changed Nifty's sorted render-set comparator keys. `CustomScroll` now assigns stable nonzero render order values before moving icons between rows. A real-Nifty regression exercises expansion and repopulation, compares layout and render order, fails on the old code and passes with the fix. The full asset-free suite passes **592 tests**.
