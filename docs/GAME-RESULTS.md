# Terminal results and campaign progress

The simulation retains the first result separately for each player. Local and hosted callbacks now send that outcome and its simulation elapsed time to the intended client. The client retains the first result through the end screen, optional continued play, teardown and debriefing. A repeated or conflicting terminal callback cannot replace it or count another campaign attempt. Elapsed time must be finite and nonnegative. The callback signature requires network protocol **8**.

The debriefing now displays the actual win/loss and elapsed time, floored to whole seconds. Labels are populated when Nifty starts the bound screen; populating the unbound controls before navigation silently left the real screen showing placeholders. Narration and campaign cutscenes use the exact played campaign level rather than a stale menu selection. Losses cannot select a winning cutscene. A quit without a terminal outcome returns to the menu without inventing a result.

## Campaign persistence

The local client owns campaign progress. The dormant duplicate server persistence path was removed. An attempt means a terminal win or loss; abandoning a level currently does not count. A loss does not clear an earlier completion. A normal campaign win unlocks the next playable level without reducing existing progress on replay. MPD and secret completion do not advance the main campaign.

Attempts are stored under `CampaignAttempts.<type>.<number><variation>`, separating branches, MPD and secret levels. Old main-campaign counters remain readable and are incorporated on the next update. Old untyped counters are not assigned to bonus modes because their origin is ambiguous. Counters saturate at the integer limit. MPD completion keys include the variation, and secrets have an explicit completed status that remains visible in the campaign map. Campaign reset clears actual normal/secret completion and attempt keys while preserving the separate MPD progress.

## End-screen lifetime

Space opens the debriefing. Escape or left Control preserves the original continue-playing action. The end state is detached once and consumes the key release so a restored Escape binding does not immediately pause the game.

The player camera owns its cinematic transitions. Cleanup cancels them without moving the menu camera or restoring removed HUD controls. Cancellation acknowledges completion once while the same client/player session remains live, allowing campaign scripts to continue; callbacks from detached or replaced sessions are ignored. Scripted camera requests arriving during the end screen are acknowledged without accessing a missing camera. Camera requests wait for a newly attached camera to initialize instead of discarding opening sweeps. Camera and HUD commands retain their callback order in one render-thread queue, and speech callbacks are also queued, and delayed speech text checks the original session and active HUD before use.

## Verification

- The full asset-free suite has **208 tests**. Fourteen result/progress/cinematic tests cover per-player callback routing, fractional time, invalid results, duplicate outcomes, profile reloads, branches, bonus modes, legacy counters, resets and camera cancellation. Two asset-path tests cover ordinary case correction and a symlinked converted-data root; canonical paths are relativized consistently instead of truncating resolved names by an unrelated root-string length.
- `scripts/verify-player-defeat.sh` checks all 72 shipped defeat predicates across 18 maps and isolated Level13/16/17 actions with the owned game data. Callback checks include the exact retained times for separate players.
- On ARM64 macOS, `scripts/verify-native-results.sh <original-data> <converted-assets>` runs the actual application three times: loss, win, and win followed by Escape continuation and later debriefing. It injects a terminal server result into Level1, checks the retained model and bound displayed labels, rejects severe runtime errors and verifies end-state cleanup. The continuation scenario starts the 500-frame, 500-position EnginePath201 camera transition before the result and verifies its cancellation is acknowledged. Each process has a three-minute watchdog. Screenshots and logs stay in ignored `work/`; a temporary profile keeps the user's campaign progress isolated.
- A real loading run exposed room metadata with no visual node during fog updates. Removal now clears that metadata safely without passing null to scene detachment; a regression checks missing and visible nodes and repeated removal.
- Independent production review covered result delivery, progress persistence, transition cancellation and canonical asset paths. Test bodies were excluded from that review and were inspected/run separately.

The single-frame/stationary spline crash discovered during this work is now fixed by authored-time sample playback. The native continuation probe also completes EnginePath250 and the stationary EnginePath67 before the moving cancellation check. See [CAMERA-PATHS.md](CAMERA-PATHS.md).

These checks do not complete a mission. Full campaign branches, bonus-map playthroughs, original ranking/statistics, live network transport, skirmish victory rules and complete save/load remain open. Other debriefing statistics still show inherited placeholders. Continued play after defeat also needs the broader defeated-army/command policy. The new settings fields persist campaign progress, not a resumable simulation.
