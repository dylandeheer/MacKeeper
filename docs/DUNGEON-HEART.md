# Dungeon-heart combat and defeat

Creature combat now accepts a live enemy dungeon-heart object as a target. Reachable enemy creatures take priority, including defenders arriving while a heart is under attack. Selection and attack execution reject captured, removed, destroyed or unrelated heart-shaped objects. Ordinary movement targets a reachable outer-ring tile; the impassable central 3×3 is never a navigation destination.

Attack distance uses the central 3×3 footprint in the ground plane rather than the below-floor object origin. A supercover tile ray rejects intervening solid terrain, blocking doors and blocked diagonal corners. Short-range creatures choose a side tile they can hit from instead of stalling at a corner. Existing valid approaches are reused. Failed searches are retried after one game second, or immediately after movement/clock rewind, and an unreachable cached heart target is cleared. These footprint and attribution rules are provisional compatibility choices; they have not been compared frame-for-frame against the original engine.

Lethal heart-object damage now reaches one world transaction that:

- Marks the room and its footprint destroyed with zero health, retaining replicated ruins state.
- Removes furniture, regeneration/mana sources and the free-imp generator; delayed construction callbacks tolerate removed furniture.
- Releases physical treasure as loose gold and debits the same amount and capacity from its current owner. Initial reserves and mined-gold totals are preserved.
- Chooses another surviving heart, if any. Losing the last heart marks the keeper destroyed and stops mana income, computer-economy decisions and portal recruitment.
- Clears the defeated keeper's stored mana and awards it, up to the normal cap, to an eligible enemy keeper credited with the lethal damage. Heroes have no mana controller and receive no mana. Human defeat is dispatched once; an AI defeat does not invoke the human campaign result path.

Creature melee and the existing immediate spell-damage path now record the attacking player in `Damage`. The latest positive hit in an accumulated damage component supplies attribution; an unattributed positive hit clears previous attribution. Healing does not claim a kill. Aggregation saturates at integer bounds and health arithmetic uses a wide intermediate to prevent overflow reversing damage or healing. The added replicated field requires network protocol **7**; this does not establish live multiplayer compatibility.

## Campaign defeat conditions

Final-heart destruction records the first killer's player ID on the defeated keeper. The killer must exist in the current player roster; unknown attribution remains zero. Hero kills are retained even though heroes cannot receive mana. Losing an earlier heart while another survives does not record defeat. The keeper's binary serialization preserves attribution and now distinguishes an absent heart from a heart at coordinate (0,0). Older serialized keepers retain their prior coordinate interpretation and receive no invented killer. This is player-state serialization, not complete save/load.

`PLAYER_KILLED` now requires recorded defeat and a nonzero killer. Its `playerId` parameter is an attacker filter: zero accepts any recorded killer; another ID must match. The condition's second data word (`value`) is unused, including the `value=1` occurrence in Level16. Existing trigger-tree repeat counts govern action repetition.

This predicate was independently checked against the user's owned Steam `DKII.EXE` (SHA-256 `b5e4e2f6b76335e2a2e7f43bb6903bfc6f4170eabf04598955b00f512a47b449`). The player trigger dispatcher at virtual address `0x004C7B20` maps type 47 through its tables at `0x004C85E8` and `0x004C8580` to `0x004C7B4B`. That branch checks the player's recorded killer reference, accepts a zero filter, otherwise compares the referenced killer's map player ID. It does not read the second data word. The [Flame address/field map](https://github.com/DiaLight/Flame/blob/f6e2a3d385bf2e6e95dc70cab88aa387bb7659d9/mapping/DKII_EXE_v170.sgmap) helped locate the branch. No Flame implementation, executable bytes, disassembly or game assets are included in this repository. This confirms the condition, not the original engine's full lethal-hit attribution or defeat aftermath.

Game results are recorded once per player. A direct heart loss followed by scripted `LOSE_GAME`, or a later conflicting win, retains the first outcome and time and sends one terminal callback. Other players have independent results; the existing no-argument accessor remains the campaign keeper (player 3), and a player-ID accessor supports other participants. The local client now persists terminal campaign outcomes and displays the retained win/loss and elapsed time; full campaign progression acceptance remains open. See [GAME-RESULTS.md](GAME-RESULTS.md).

## Evidence

The full asset-free suite has **190 passing tests**, including sixteen new heart tests covering lethal/nonlethal damage, multiple hearts, capture, human/AI callbacks, mana-less heroes, treasure conservation, overflow, delayed construction, defender priority, melee cooldown, reachable approaches, wall/door/corner exclusion, stale targets, route reuse/recovery and an intact portal before/after defeat. Independent production review identified and helped resolve continued portal recruitment, corner stalls and repeated failed path searches. Review test bodies were summary-only; implementation tests were run locally.

Six new asset-free defeat tests cover wildcard/specific/unknown killers, both extra-word values, one-shot actions, immutable first attribution, binary round trips and legacy reads. The owned-data defeat audit checks all **72 conditions in 18 maps** against living, unknown, matching and nonmatching states. Isolated authored branches in **Levels 13 and 16** verify that heart loss followed by scripted loss produces one callback; a conflicting win cannot overwrite it. **Level17** verifies its flag-5 death counter advances once for each of the two computer keepers. These checks use production heart destruction and trigger actions, but isolate prerequisite conditions rather than play the complete missions.

Run the owned-data checks with JDK 25:

```sh
JAVA_HOME=/path/to/jdk-25 scripts/verify-dungeon-heart.sh "/path/to/Dungeon Keeper II"
JAVA_HOME=/path/to/jdk-25 scripts/verify-player-defeat.sh "/path/to/Dungeon Keeper II"
```

On the owned Steam data, **Level1, TrapTime and Level17** each pass destruction of an authored **100,000-HP** heart through a level-10 Unique Dark Angel's production creature state machine, authored melee damage/recharge, health processing and regeneration. Each takes 28.85 simulated combat seconds in this isolated scenario. Levels 1 and 17 also use real navigation and steering from outside the heart. TrapTime starts inside its initially sealed heart chamber; no path through its walls is fabricated. The smoke verifies physical treasure accounting, mana transfer/clearing, defeat callbacks and removal of the imp generator.

The two twelve-minute TrapTime economy seeds retain their prior 84/75 and 84/25 built-tile totals. The Level 17 ownership check and Level 1/2/17 payday movement checks also pass. A new local ARM64 package passes strict ad hoc signature verification and bundled `--help`; the updated combat has not been visually replayed in it.

This test deliberately removes the existing armies and drives selected systems sequentially. It does not demonstrate a played mission, strategic assault, balanced combat, original animation/audio, rendered ruins, concurrent simulation or network transport.

## Remaining work

- `PLAYER_DESTROYS` remains unimplemented. Heart death and isolated `PLAYER_KILLED` checks do not establish complete campaign victory or progression acceptance.
- The renderer already chooses destroyed floor tiles; center ruins, destruction effects/audio and an actual rendered combat replay remain unverified.
- Skirmish last-survivor victory and multiplayer reward choices beyond stored mana are not implemented by this checkpoint.
- Defeated armies, remaining territory, player commands and mode-specific end-of-game behavior need broader lifecycle work. Save/load and reconnect restoration remain open.
- Ranged-shot semantics are inherited, including immediate damage alongside created shots. This change does not establish spell/projectile parity or implement keeper strategic attacks.
