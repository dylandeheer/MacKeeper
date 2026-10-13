# Creature deaths and campaign kill conditions

Creature deaths now update authoritative player loss and kill statistics. `PLAYER_CREATURES_KILLED` and `PLAYER_KILLS_CREATURES` use these statistics instead of returning false unconditionally. Knockout alone does not count as death; attacker attribution remains on the creature while it is unconscious.

## Predicate and counter semantics

The owned Steam DKII 1.70 executable (SHA-256 `b5e4e2f6b76335e2a2e7f43bb6903bfc6f4170eabf04598955b00f512a47b449`) was inspected locally. The type-22 predicate at `0x4c7d0f` reads the killer filter from condition byte 3. Zero means the subject player's total losses; a specific killer means that killer's count against the subject player. Type 23 at `0x4c7df0` reads the victim filter from byte 2, which the inherited parser incorrectly called `flag`. Zero queries non-hero enemy kills; a specific player queries all recorded kills against that player. Both compare to the literal second data word.

The death path at `0x488226` checks depleted health before updating statistics. At `0x4882ca` it separates creatures with the `IS_GOOD` definition flag into hero kills. Other creatures increase the general enemy-kill total only when killer and owner differ. The per-victim matrix still includes self-kills and heroes. The victim's total losses also include unattributed deaths. Workers are not excluded. The source-player reference used during death is retained on the original entity. The [Flame field map](https://github.com/DiaLight/Flame/blob/f6e2a3d385bf2e6e95dc70cab88aa387bb7659d9/mapping/DKII_EXE_v170.sgmap) helped identify the structures; no Flame implementation, executable bytes, disassembly or original assets are committed.

MacKeeper now retains the most recent applied positive-damage source in `LastDamageSource`. An unattributed positive hit clears earlier credit; healing does not claim a kill. Death with a body, death without a body, prison/torture death and death that produces a skeleton all record the original owner once. Rescue and ordinary removal do not record deaths. Slapping supplies the owner's identity. The existing melee and direct spell paths already supply their attacker.

Keeper serialization includes losses, separate non-hero/hero totals and per-victim counts. Legacy records default to zero; negative counts and malformed arrays are rejected. Counts use wide integers instead of reproducing the original filtered-counter 16-bit overflow. Network protocol **8** accounts for the new keeper fields and attribution component. This is not a complete save system or verified live multiplayer support.

## Verification

The full asset-free suite passes **208 tests**. Thirteen new tests cover attribution, workers, hero/self-kill distinctions, knockout versus death, rescue, replacement attackers, unattributed damage, corpse-less creatures, objects, ordinary removal, prison/torture death, skeleton replacement, serialization, and all comparison operators for both predicates.

The owned-data audit parses **66 maps**, traces player roots, and exercises every **27 kill/loss condition in eight maps** through production creature spawning and health processing. Each test checks a wrong killer, the count below the threshold, the threshold itself and the next count. Equality conditions correctly stop matching after their exact count.

Representative isolated child chains additionally verify:

- Level 13 speech 8 after four hero kills.
- All twelve Level 17 kill-dependent flag increments.
- Level 6a speech branches 12 and 13.
- Secret Level 2's first reinforcement-spawn branch, including no repeated spawning after completion.

```sh
scripts/verify-creature-kills.sh "/path/to/Dungeon Keeper II"
```

These probes force their spawned victims to die instantly, apply damage through the production damage component, and isolate authored conditions from sibling prerequisites. Synthetic tests separately exercise the unconscious interval and other death routes. This does not demonstrate completed missions or rendered combat.

## Remaining acceptance

The original executable has additional attribution fallback lookups and a runtime exclusion flag whose full lifecycle is not established here. Exact parity for mixed damage/healing within a tick, environmental damage after an earlier attack, summoned/temporary creatures and end-of-level adjustments remains open. The debriefing does not yet receive these new statistics. Complete versioned saves, original-engine comparisons, live multiplayer transport and full campaign playthroughs remain required.
