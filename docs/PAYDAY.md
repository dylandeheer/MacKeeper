# Payday implementation and verification

The owned game manual, sections 7.6.2.2–3 and 8.1.2, establishes physical wage collection, unpaid anger and gold gifts as advance payment. The shipped map variables supply timing: Level 1 uses 1,200 seconds before and between paydays; the other 65 maps use 600 seconds. All 66 use a 120-second cutoff and four-second pay reevaluation setting.

## Implemented

- The authoritative game-time cycle accrues wages from each creature's current scaled pay. Repeated ticks at the same cycle do not charge twice; advance credit covers wages before debt accrues. Catch-up preserves the first uncovered cycle's time.
- Salary debt and advance credit are separate from personal gold. Loot and authored starting purses do not count as advance wages.
- Eligible creatures interrupt ordinary work to seek an accessible owned gold room. Combat retains priority. Equal-distance rooms remain candidates, full treasuries permit withdrawal, and unsuccessful attempts are throttled by the map setting. Sleeping and other special states finish before the ordinary work/idle path requests collection.
- Unassigned collection tasks support the task manager's null-creature cleanup probe and can be removed safely.
- Collection requires actual arrival and revalidates the creature, room, owner and funds. Partial payments retain unpaid debt. Treasury storage, keeper balance and personal purse move under the same lock as capture and hand actions.
- Dropping gold on a living, conscious, owned wage-earning creature on the drop tile settles debt and credits excess as advance pay. Invalid targets fall back to the ordinary drop behavior. Payment clears only unpaid anger when the debt is fully covered.
- Purse changes, level changes, loot extraction and relevant mood changes share the transaction boundary. Stored gold updates replace ECS components so clients can observe pile changes.
- `LEVEL_PAY_DAY` conditions observe new cycles once per condition. A newly created trigger controller starts at the current cycle, avoiding replay of an event that preceded it.
- The next-payday tooltip subtracts advance credit; collection has the original text entry and payday icon. Salary state is registered for replication, introduced with network protocol version 4 (current version: 10).

## Evidence

Synthetic tests cover clock boundaries, repeated ticks, catch-up, advance and partial payment, unpaid anger, conversion, wage estimates, trigger baselines, actual treasury transactions, full storage, capture interleavings, duplicate attempts, invalid/foreign gift targets, combat priority and death-loot conservation. They contain no original assets.

Run `JAVA_HOME=/path/to/jdk25 scripts/verify-payday.sh /path/to/owned/game-data` for the real-data integration check. It loads Levels 1, 2 and 17, advances each authored clock, assigns a production collection task, uses the real movement system to travel from outside the gold room, and checks arrival, debt settlement, keeper debit and duplicate-task conservation. The check uses empty gold rooms and seeds one wage of reserve when authored starting funds are insufficient. It does not run the complete game loop or establish mission completion.

## Remaining parity and acceptance work

The following choices are explicit provisional behavior pending original-engine comparison:

- Unpaid debt carries forward; captives do not accrue new wages; conversion resets the employer ledger while retaining physical gold.
- The cutoff is treated as a grace period before unpaid anger, using the existing native-scale anger conversion at each logic tick. Exact original timing still needs verification.
- Dungeon hearts and treasuries both satisfy gold-room collection. The separate initial stockpile now supplies wages before physical room gold; see [STARTING-GOLD.md](STARTING-GOLD.md).
- General gratitude from gifts beyond clearing unpaid anger, gifts to zero-wage creatures, collection animation/audio, mentor announcements and the visible countdown remain unfinished.
- Rendered UI, pause/reconnect, live multiplayer, save/load persistence and full campaign behavior require acceptance testing. The clock and salary ledger must be included in the future versioned save contract.

These checks establish a working collection path, not complete original-game fidelity.
