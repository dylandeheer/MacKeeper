# Doors, traps and workshop implementation

The defense system is unfinished. Map-authored doors/traps load, door views respond to nearby creatures, and the campaign can now query owned defense state. Player placement, workshop production, crate delivery, trap firing/rearming and their end-to-end campaign behavior still require implementation.

## Campaign predicates

`PLAYER_DOORS` and `PLAYER_TRAPS` previously returned false unconditionally. The owned Steam executable (SHA-256 `b5e4e2f6b76335e2a2e7f43bb6903bfc6f4170eabf04598955b00f512a47b449`) dispatches them at `0x4c80ce` and `0x4c8240`. Their rules differ from the inherited parser comment describing an optional comparison against another player:

- With comparison `NONE`, the predicate checks available/discovered status (native status 3). Type zero means any such definition; a nonzero type checks that definition. The numeric value and count flags are ignored.
- With a numeric comparison, the right-hand side is the literal value. The stored `playerId` byte is unused; the trigger's owning player supplies the left-hand state.
- Flag bit 8 counts blueprints of all types. Otherwise, bit 16 counts owned defenses minus blueprints, also across all types. Bit 8 wins when both are set. These branches deliberately ignore the requested type, matching the inspected engine.
- Without either count flag, type zero counts all owned defenses; a nonzero type counts that type.
- A type-specific trap count additionally includes owned object type 74 when the requested trap's shot type is 26 (rolling boulders). This exception does not apply to the all-types, blueprint or built-only branches. A door's embedded trap weapon is not a standalone owned trap.

`DefenseCounts` reads current authoritative entities, so construction-state changes, ownership transfers and removal affect the next evaluation without a second cached ledger. Door blueprint state lives in `DoorComponent`; the trap factory currently persists its only blueprint value in `TrapViewState`, which the count reads. A complete trap lifecycle should move authoritative arming state into a dedicated persisted model with a defined migration, rather than adding a second unsynchronized boolean. These counts describe owned placed entities, not workshop stock.

Existing entity and keeper archive sections already preserve the state consumed by the predicates. This change adds no save section or network message.

## Evidence

The full asset-free suite passes **596 tests**. Four new regressions cover owner/type isolation, embedded door weapons, blueprint/built flag precedence, rolling boulders, availability, literal comparison, immediate lifecycle updates and entity archive continuation.

On 2026-10-13, `scripts/verify-defense-conditions.sh` inventoried all **66 maps** and exercised all **10 authored conditions** in **four real map worlds**: Level3, Secret1, Secret4 and MPD5. Each world passes server archive write/read/restore with unchanged predicate results before fixtures are introduced. Isolated defense fixtures then exercise empty, threshold and removal outcomes through the real parsed player trigger controller. This is predicate coverage; it does not establish workshop production, player placement, child-action timing or mission completion.

## Remaining implementation sequence

1. Trace original placement, manufacture queue, production work, stock and delivery semantics, including blueprint cancellation and refunds. Complete the currently empty scripted door/trap availability actions with verified parameters.
2. Give manufacturing orders, accumulated work, crate identity/location, reservations and blueprint/arming state authoritative owners and versioned persistence. Avoid deriving stock from HUD counts.
3. Implement workshop task assignment and capacity through ordinary creature work and room ownership. Handle interruption, sale/capture/destruction, carried crates and failed routes without duplicating stock or work.
4. Connect player input to validated authoritative placement transactions, with terrain/orientation/blocker/ownership/availability checks and discoverable feedback. Implement cancellation, sale and delivery through the same world boundary.
5. Implement trap detection, firing, projectiles, ammo/mana, cooldown and rearming from installed definitions; preserve timers and targets across loads.
6. Verify Level3's complete tutorial flow, Secret1/4 and MPD5, representative skirmishes, save continuation, rendered feedback and multiplayer authority. Keep all rendered test audio muted.

No original executable bytes, disassembly or proprietary assets are distributed with this documentation.
