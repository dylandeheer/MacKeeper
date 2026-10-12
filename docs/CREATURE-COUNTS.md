# Campaign creature-count conditions

`PLAYER_CREATURES_AT_LEVEL`, `PLAYER_HAPPY_CREATURES` and `PLAYER_ANGRY_CREATURES` no longer return false unconditionally. They query the authoritative entity data, so level, mood, ownership and creature removal changes are visible without waiting for a separate roster update.

## Original predicate evidence

The locally owned Steam DKII 1.70 executable was inspected for these predicates. Its SHA-256 is `b5e4e2f6b76335e2a2e7f43bb6903bfc6f4170eabf04598955b00f512a47b449`. The dispatch table at `0x4c7b20` resolves type 20 to `0x4c7c23`, type 21 to `0x4c7c98`, and type 52 to `0x4c7e4e`.

- The level condition counts owned non-worker creatures whose experience level is **at least** byte 3 of the condition data. Byte 0 is the comparison operator and the following 32-bit word is the required count. Bytes 1 and 2 are unused. The inherited parser incorrectly exposed byte 3 as `playerId`; it now exposes `level`.
- Happy and angry conditions count native mood states **0** and **2** respectively. State 1 (unhappy) satisfies neither. Byte 1 optionally filters the creature species; zero matches every non-worker species. Workers are excluded even when the species filter names a worker. Bytes 2 and 3 are unused.
- All three predicates compare the count to a literal value. They do not use the player-versus-player interpretation of the ordinary `PLAYER_CREATURES` condition.

MacKeeper derives mood states from its existing independent anger counters and authored unhappy threshold. Counting uses the existing `Health`-component lifetime convention: dead creatures lose that component, while unconscious creatures retain it. Temporary entity queries are released after each count. No original code, binary bytes or game assets are included here.

## Verification

Five new asset-free regression tests cover level boundaries, above-threshold levels, all comparison operators, workers, species filters, other owners, missing components, mood thresholds, ownership transfer, removal and one-time child action traversal. The full suite passes **195 tests**.

The owned-data audit parses all **66 maps**, resolves each count condition to its player root, and exercises all **five authored conditions** with production creature spawning, level-up and count logic:

| Map | Condition | Verified isolated child action |
| --- | --- | --- |
| Level 2 | At least one creature at level 2 | Speech 10 after the camera transition ends |
| Level 15a | At least one happy creature of species 22 | Speech 13 |
| My Pet Dungeon 2 | At least 15 happy creatures | Add 6,000 to level score |
| My Pet Dungeon 3 | At least one creature at level 8 | Add 8,000 to level score |
| My Pet Dungeon 4 | At least 20 creatures at level 4 | Add 11,000 to level score |

The probe verifies that actions stay blocked below the threshold, run once after it is reached, and that ownership loss or death removes a creature from the count. The shipped maps contain no angry-count condition; synthetic tests cover that handler.

With JDK 25 configured:

```sh
scripts/verify-creature-counts.sh "/path/to/Dungeon Keeper II"
```

The audit isolates these conditions from sibling mission prerequisites. It does not establish completed missions, audible speech, complete mood behavior, live multiplayer transport, or saved-state restoration. Kill statistics, breach events and other unfinished campaign dependencies remain separate work.
