# Camera sample playback

Camera sweeps now advance through their authored samples at 30 Hz. Position, rotation and field of view are interpolated between adjacent samples using elapsed time, with the final sample held until the path's `sampleCount / 30` duration ends. The existing KCS reader documents this timing from the original path data and accompanying text. Linear interpolation between position/FOV samples and quaternion interpolation preserve the authored sample poses; exact original-engine interpolation between samples remains unverified.

The previous distance-based spline removed repeated positions. This changed the duration of holds, disconnected rotation/FOV indices from their original samples and crashed for a one-point spline. The owned data contains seven one-frame paths (`250`, `255`, `258`, `261`, `264`, `273`, `274`) and the 170-frame stationary `EnginePath67`. Stationary paths can still animate rotation and field of view.

Playback writes camera and audio-listener pose together and copies the caller's origin and cached rotation values before use. It retains the existing coordinate conversion, world offset, 0.1 near plane and 100 far plane. Original near-plane metadata is still not mapped. An empty path is rejected before playback. No proprietary samples are embedded in source or tests.

These cinematics are single use, matching their existing callers. Finishing or stopping applies the final sample; cancellation leaves the current pose intact. Recursive stop calls during state detachment and later stop/cancel requests notify completion listeners once. Pause/resume remains supported before completion. The player camera's existing cleanup still restores the pre-transition view and acknowledges the live session.

## Evidence

- **190 asset-free tests pass.** Seven new playback regressions cover one-frame completion, stationary rotation/FOV, repeated-position holds, unequal sample distances, boundary completion, pause/resume, cancellation, immutable samples/origin, empty paths and one completion notification. The prior pending-cancellation tests remain green.
- `scripts/verify-camera-paths.sh <converted Interface/Paths>` runs the actual cinematic over all **80 owned camera files and 34,346 samples**, including all **eight stationary paths**. At every authored sample time it compares camera position, rotation, field of view and listener pose with the converted sample, then checks one completion callback and detachment. This is a playback audit of converted data, not independent verification of the conversion or original renderer.
- `scripts/verify-native-results.sh <original-data> <converted-assets>` checks native loss/win/continue result flows. The continuation case pauses the Level1 simulation to isolate camera playback, completes one-frame `EnginePath250`, verifies stationary `EnginePath67` holds its position and completes within the six-second observation window, resumes simulation, and cancels the moving `EnginePath201` through the end screen before continuing to debriefing. Logs must contain no severe runtime errors; profiles and screenshots stay local in ignored `work/`.
- Independent production review covered sampling and lifecycle behavior. Test bodies were excluded from review and inspected/run separately.

This closes the reproduced stationary-path crash. Full mission audiovisual acceptance and side-by-side original-engine timing/interpolation comparison remain open.
