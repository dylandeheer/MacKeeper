# Shipped-map audit

Date: 2026-10-12. Input: locally owned Steam base and English data. The report contains counts and map identifiers only; original game assets remain outside this repository.

The parser loaded all **66 maps**, including **24 campaign variants**, **5 secret maps** and **7 My Pet Dungeon maps**. All parsed child/sibling trigger links refer to existing nodes and are acyclic. This validates parsing and link integrity only: it does not prove gameplay compatibility, trigger semantics, victory or progression.

## Confirmed campaign dependencies on unfinished code

| Trigger | Occurrences | Campaign maps | Source finding |
|---|---:|---:|---|
| `COLLAPSE_HERO_GATE` | 59 | 14 | TriggerControl reads coordinates without collapsing the gate. |
| `MAKE_OBJECTIVE` | 59 | 21 | PlayerTriggerControl supports neither active markers nor clearing them. |
| `CHANGE_ROOM_OWNER` | 3 | 1 | TriggerControl reads the target without transferring the room. |
| `LEVEL_PAY_DAY` | 1 | 1 | TriggerControl always returns false; no payday system is present. |
| `DISPLAY_OBJECTIVE` | 1 | 1 | PlayerTriggerControl reads the objective and zooms without displaying it. |

These are confirmed examples, not an exhaustive support matrix. Additional media, creature, party and player actions require semantic review. The room ownership actions occur in `Level17.kwd`; the payday condition occurs in `level1.kwd`.

## Reproduce

With `JAVA_HOME` pointing at JDK 25:

```sh
scripts/audit-game-data.sh "/path/to/Dungeon Keeper II" > game-data-audit.tsv
```

Build/parser diagnostics go to stderr. Stdout contains tab-separated map, trigger kind and count. The audit exits unsuccessfully on unreadable/malformed maps, unknown trigger types, missing linked nodes, cyclic links or an empty maps directory. It never modifies the input data.

## Campaign map files

`Level10.kwd`, `Level13.kwd`, `Level14.kwd`, `Level15a.kwd`, `Level16.kwd`, `Level17.kwd`, `Level19.kwd`, `Level20.kwd`, `Level6a.kwd`, `Level6b.kwd`, `Level9.kwd`, `level1.kwd`, `level11A.kwd`, `level11B.kwd`, `level11C.kwd`, `level12.kwd`, `level15b.kwd`, `level18.kwd`, `level2.kwd`, `level3.kwd`, `level4.kwd`, `level5.kwd`, `level7.kwd`, `level8.kwd`.
