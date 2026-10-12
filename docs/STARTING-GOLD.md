# Starting gold stockpile

The owned Dungeon Keeper II manual, section 8.1.2, describes starting gold as a separate stockpile spent before treasury gold. MacKeeper now keeps that reserve outside physical room storage and includes it in the displayed total.

Purchases, hand withdrawals and wage collection consume the reserve first. Physical pile pickup, room destruction and capture affect stored treasure independently. Creatures still travel to an accessible owned gold room to collect wages, even when that room is empty and the reserve supplies the payment. Starting reserves do not count as mined gold.

Total keeper balances use a 64-bit value so a reserve can coexist with captured or deposited treasure above the old integer limit. Individual transfers, personal purses and room capacities retain their integer bounds. The shared building validator and server payment path reject room selections whose total cost exceeds that transfer limit. Local and hosted gold callbacks carry both total and reserve; network protocol 5 rejects older clients. Keeper serialization writes `goldTotal` and `goldReserve`, with a fallback for the older integer `gold` field. This is player-state compatibility, not a complete save/load implementation.

## Evidence

The 121-test suite includes reserve-first purchases and wages, empty-treasury collection, room capture above the old integer limit, physical pickup, loose-pile overflow conservation, oversized room purchase rejection, Keeper serialization and local/hosted callback forwarding. Network callback tests substitute transport.

Run `JAVA_HOME=/path/to/jdk25 scripts/verify-starting-gold.sh /path/to/owned/game-data` to audit all shipped maps. All 66 maps and 234 funded player entries preserve authored starting balances, leave physical gold storage empty and start with zero mined gold. Before this change, 89 hero/neutral player entries lost a combined 267,000 starting gold; keeper entries in this dataset retained their totals but occupied physical room capacity.

The payday movement check passes Levels 1, 2 and 17 with empty gold rooms and reserve-backed collection; it seeds one wage of reserve when the map has insufficient starting funds. The Level 17 capture check still passes all three authored room transfers. These are headless integration checks, not completed missions or original-engine comparison.

## Remaining work

Exact original-engine behavior, rendered large balances, live multiplayer and full campaign acceptance remain unverified. The inherited mined-gold counter still counts ordinary storage deposits, including some returned funds; this separate accounting problem remains open. The future save format must include the reserve together with the full authoritative simulation state.
