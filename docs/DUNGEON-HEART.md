# Dungeon-heart combat and defeat

Creature combat now accepts a live enemy dungeon-heart object as a target. Reachable enemy creatures take priority, including defenders arriving while a heart is under attack. Selection and attack execution reject captured, removed, destroyed or unrelated heart-shaped objects. Ordinary movement targets a reachable outer-ring tile; the impassable central 3×3 is never a navigation destination.

Attack distance uses the central 3×3 footprint in the ground plane rather than the below-floor object origin. A supercover tile ray rejects intervening solid terrain, blocking doors and blocked diagonal corners. Short-range creatures choose a side tile they can hit from instead of stalling at a corner. Existing valid approaches are reused. Failed searches are retried after one game second, or immediately after movement/clock rewind, and an unreachable cached heart target is cleared. These footprint and attribution rules are provisional compatibility choices; they have not been compared frame-for-frame against the original engine.

Lethal heart-object damage now reaches one world transaction that:

- Marks the room and its footprint destroyed with zero health, retaining replicated ruins state.
- Removes furniture, regeneration/mana sources and the free-imp generator; delayed construction callbacks tolerate removed furniture.
- Releases physical treasure as loose gold and debits the same amount and capacity from its current owner. Initial reserves and mined-gold totals are preserved.
- Chooses another surviving heart, if any. Losing the last heart marks the keeper destroyed and stops mana income, computer-economy decisions and portal recruitment.
- Clears the defeated keeper's stored mana and awards it, up to the normal cap, to an eligible enemy keeper credited with the lethal damage. Heroes have no mana controller and receive no mana. Human defeat is dispatched once; an AI defeat does not invoke the human campaign result path.

Creature melee and the existing immediate spell-damage path now record the attacking player in `Damage`. The latest positive hit in an accumulated damage component supplies attribution; an unattributed positive hit clears previous attribution. Healing does not claim a kill. Aggregation saturates at integer bounds and health arithmetic uses a wide intermediate to prevent overflow reversing damage or healing. The added replicated field requires network protocol **5**; this does not establish live multiplayer compatibility.

## Evidence

The full asset-free suite has **160 passing tests**, including sixteen new heart tests covering lethal/nonlethal damage, multiple hearts, capture, human/AI callbacks, mana-less heroes, treasure conservation, overflow, delayed construction, defender priority, melee cooldown, reachable approaches, wall/door/corner exclusion, stale targets, route reuse/recovery and an intact portal before/after defeat. Independent production review identified and helped resolve continued portal recruitment, corner stalls and repeated failed path searches. Review test bodies were summary-only; implementation tests were run locally.

Run the owned-data smoke with JDK 25:

```sh
JAVA_HOME=/path/to/jdk-25 scripts/verify-dungeon-heart.sh "/path/to/Dungeon Keeper II"
```

On the owned Steam data, **Level1, TrapTime and Level17** each pass destruction of an authored **100,000-HP** heart through a level-10 Unique Dark Angel's production creature state machine, authored melee damage/recharge, health processing and regeneration. Each takes 28.85 simulated combat seconds in this isolated scenario. Levels 1 and 17 also use real navigation and steering from outside the heart. TrapTime starts inside its initially sealed heart chamber; no path through its walls is fabricated. The smoke verifies physical treasure accounting, mana transfer/clearing, defeat callbacks and removal of the imp generator.

The two twelve-minute TrapTime economy seeds retain their prior 84/75 and 84/25 built-tile totals. The Level 17 ownership check and Level 1/2/17 payday movement checks also pass. A new local ARM64 package passes strict ad hoc signature verification and bundled `--help`; the updated combat has not been visually replayed in it.

This test deliberately removes the existing armies and drives selected systems sequentially. It does not demonstrate a played mission, strategic assault, balanced combat, original animation/audio, rendered ruins, concurrent simulation or network transport.

## Remaining work

- Campaign `PLAYER_KILLED` conditions still require an attribution/state contract and validation against their authored parameters. A local all-map probe found both `value=0` and `value=1`; treating every case as a plain destroyed flag would be an unverified shortcut. `PLAYER_DESTROYS` also remains unimplemented. Heart death alone is not campaign victory acceptance.
- The renderer already chooses destroyed floor tiles; center ruins, destruction effects/audio and an actual rendered combat replay remain unverified.
- Skirmish last-survivor victory and multiplayer reward choices beyond stored mana are not implemented by this checkpoint.
- Defeated armies, remaining territory, player commands and mode-specific end-of-game behavior need broader lifecycle work. Save/load and reconnect restoration remain open.
- Ranged-shot semantics are inherited, including immediate damage alongside created shots. This change does not establish spell/projectile parity or implement keeper strategic attacks.
