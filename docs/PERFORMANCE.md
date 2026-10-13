# Simulation performance evidence

## Blocked task navigation

A twelve-minute headless Level 10 probe at checkpoint `7c7c0268` took approximately 353 seconds on the local M1 Max with ARM64 JDK 25. A 30-second Java Flight Recorder profile captured 2,510 execution samples. Frequent leaf frames were ECS map lookups (`ConcurrentHashMap.get`, 1,549 samples) and `MapIndexedGraph.getCost` (262 samples). A main-thread stack showed WORK repeatedly calling `navigateToAssignedTask` and A* for an unreachable destination.

The WORK state retries navigation whenever a creature is stopped. Previously, a failed path left the creature stopped and caused the same search on the next simulation tick. Failed assigned-task routes now wait one game second before retrying. A changed task, start tile or destination retries immediately; successful routes are not throttled. A rewound clock also permits an immediate retry. Doors opening or paths being dug can therefore recover within one game second without permanently caching a failure. Null task targets are handled safely.

Five controller tests exercise retry frequency, changed tasks/locations, route recovery, successful-path behavior, missing targets and clock rewind. At that checkpoint, the complete asset-free suite contained 144 tests. Both twelve-minute TrapTime economy seeds also pass after this change, preserving their construction totals (84/75 and 84/25 tiles for keepers 4/5).

## Reproduction

Compile the current checkout and run the bounded, seeded workload with locally owned data:

```sh
export JAVA_HOME=/absolute/path/to/jdk25
./gradlew classes installDist
"$JAVA_HOME/bin/java" -Xmx2g \
  -cp 'build/classes/java/main:build/install/MacKeeper/lib/*' \
  scripts/NavigationBenchmark.java '/absolute/path/to/Dungeon Keeper II'
```

The benchmark loads Level 10, seeds the world-owned simulation generator with zero and invokes the production logic, animation and steering systems sequentially for 120 simulated seconds. It fails on system exceptions and reports simulation time separately from source compilation and map loading. Capture stderr to a file consistently for comparisons; it includes the existing navigation warnings.

An isolated sequential comparison measured **56.1 seconds before and 32.0 seconds after** the retry change, a 43% reduction in elapsed simulation time for this workload. An earlier exploratory comparison measured 59.4 and 32.9 seconds. The baseline used the `CreatureController` source from `7c7c0268`, compiled against the same dependencies and placed first on the classpath; the remaining source, data, seed and runtime matched. Both versions ended with keeper 4 holding 10,000 gold, seven creatures and four imps. These summaries check broad workload consistency, not identical game state or original-game fidelity. Whole-engine determinism is not established.

This evidence concerns one headless simulation workload. It does not establish rendered frame rates, load-time or memory improvements, multiplayer performance, campaign completion or performance across all maps. The broader campaign probe, including a slow Level 13 run stopped before completion, still needs investigation. Full-map navigation and repeated ECS reads remain profiling candidates.

The simulation generator now uses explicitly saved SplitMix64 state, isolated from visual randomness. Historical timings above used the previous global generators and are not directly comparable with the new seed-zero sequence. Re-measure both versions on an equivalent workload before claiming an additional speedup.

A fresh run using the world-owned generator completed the same 120 simulated seconds in 29.6 seconds, ending with 10,000 keeper gold, seven creatures and four imps. This is a new workload baseline, not a measured improvement over the historical random sequence.

After spatial persistence introduced stable entity ordering, the same seeded workload completed in 30.6 seconds with the same reported gold/creature/imp totals. The prior run was 29.6 seconds. These single runs are a sanity check, not evidence of a statistically established slowdown or speedup; full state equivalence and rendered performance remain separate checks.
