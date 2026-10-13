# Dungeon breach events

Destroying another player's fortified wall now emits the player breach events used by Level 1 and Pressure. The defender receives `PLAYER_DUNGEON_BREACHED`, then the attacker receives `PLAYER_ENEMY_BREACHED`. Partial damage, unowned rock, floor damage, self-owned walls and scripted terrain replacement do not emit this wall-destruction event.

## Original executable evidence

The locally owned Steam DKII 1.70 executable has SHA-256 `b5e4e2f6b76335e2a2e7f43bb6903bfc6f4170eabf04598955b00f512a47b449`. Its player predicate dispatcher at `0x4c7b20` returns false for types 56 and 57. These are dispatched separately through `0x4c7a50`, which scans matching root conditions and evaluates their descendants at event time. The stored player byte is ignored for these two types. Consequently, polling an old breach must not unlock a later condition, and a nested breach condition is not a root event subscription.

The terrain damage path at `0x4530bb–0x453175` requires zero tile health, a different destruction terrain, `SOLID` and `OWNABLE` flags on the original terrain, and an attacker different from the tile owner. It dispatches defender event 56 before attacker event 57, before converting the tile's terrain. No alliance test occurs in this path. MacKeeper preserves this sequence: a defender action can change a flag read by an attacker's descendant condition during the same event.

The event dispatcher executes root descendants synchronously. Nested conditions retain their own repeat behavior, and a false descendant remains eligible for a later event. The ordinary polling traversal still treats the two breach conditions as false. Listener registration follows the server trigger controller's start/stop lifecycle and does not use network transport.

The [Flame field map](https://github.com/DiaLight/Flame/blob/f6e2a3d385bf2e6e95dc70cab88aa387bb7659d9/mapping/DKII_EXE_v170.sgmap) supplied address leads; the control flow above was checked in the owned executable. Original bytes, disassembly and game assets remain local.

## Verification

Six asset-free regression tests cover partial versus final damage, the terrain state visible during the event, defender/attacker routing, one-shot and repeated actions, ignored encoded player filters, ignored non-breach damage, start/stop cleanup, events before registration, root versus nested event conditions, descendants becoming true after a past event, and defender-before-attacker ordering. The full suite passes **214 tests**.

The owned-data probe parses all **66 maps**, confirms that both authored breach conditions are player roots, and destroys an actual authored 900-health fortified tile using production map damage and player event dispatch:

| Map and condition | Event subject | Result |
| --- | --- | --- |
| Level 1, condition 80 | Defender 3 | Authored flag 80 becomes 1 |
| Pressure, condition 3 | Attacker 3 | Authored flag 0 becomes 1 |

Both require 113 ordinary wall-damage calls with their map settings. The probe checks that the flag remains untouched until the final hit and that a second breach does not repeat the one-shot action.

```sh
scripts/verify-dungeon-breaches.sh "/path/to/Dungeon Keeper II"
```

These are isolated map-damage and event-action checks. They do not constitute played missions, creature excavation/navigation acceptance, rendered effects, or live multiplayer verification.

## Remaining acceptance

Another original creature-task path at `0x498bb0` also emits these events and appears to reverse the two recipients. Its complete task/terrain prerequisites and counterpart in MacKeeper remain unestablished. This checkpoint implements the verified terrain-health path; it does not establish every original cause of a breach. Full Level 1 and Pressure playthroughs, other event types, saved trigger state and original-engine comparison remain open.
