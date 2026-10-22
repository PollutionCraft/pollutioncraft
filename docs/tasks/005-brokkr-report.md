# 005 report (Brokkr)

**Status: DONE.** `./gradlew build runGameTestServer --rerun-tasks`: BUILD SUCCESSFUL, 27 tests pass, "All 1 required tests passed". Nothing committed.

## Red before green
All tests were first written against non-existent classes: `:core:test` failed at `compileTestJava` (`cannot find symbol` for `CoreInfo`-style new types such as `ChunkKey`, `DiffusionStep`). That is a compile failure, not a per-test failure, so to get real per-test red I replaced `DiffusionStep.run` with a no-op stub (and restored the real code afterwards):

| Brief test | Test | Failure against the no-op stub |
| --- | --- | --- |
| 1 spread to 4 neighbours, total rises only by emission | `seededChunkSpreadsToFourNeighboursAndTotalRisesOnlyByEmission` | `expected: <101.2> but was: <100.0>` |
| 2 below threshold nothing spreads | `belowSpreadThresholdNothingSpreads` | **passes vacuously** on a no-op. Mutant instead: `spreads()` ignoring the threshold -> `belowSpreadThresholdNothingSpreads() FAILED` |
| 3 determinism, different insertion order | `resultIsBitIdenticalWhateverTheInsertionOrder` | **passes vacuously** on a no-op (identical untouched fields). It is a regression guard; I did not construct a mutant that makes it fail, so treat it as weaker evidence |
| 4 pruning; both pools spread; persistent decays, absorbable not | `chunksBelowEpsilonArePruned`, `bothPoolsSpreadPersistentDecaysAbsorbableDoesNot` | `expected: <null> but was: <ChunkPollution@…>`; second `FAILED` (spread/decay values) |
| 5 stats reconcile per chunk | `statsReconcileForEveryChunk` | `FAILED` against the stub; also failed when I broke the decay arithmetic (`p2 - decayed * 0.9`) |
| absorption cap (extra) | `absorptionIsCappedByCapacityAndByWhatIsPresent` | `expected: <3.0> but was: <5.0>` |
| emissions validated (extra) | `invalidEmissionsAreRejectedAndLeaveTheFieldUntouched` | `Expected IllegalArgumentException to be thrown, but nothing was thrown.` |

Green: all of the above pass with the real implementation.

## Review item 1: invalid capacity
New test `invalidCapacityIsRejectedAndLeavesTheFieldUntouched` (NaN, -1, +inf, -inf; field unchanged, `lastIntervalStats` untouched, message names the chunk). First run, before the fix: `capacity NaN ==> Expected java.lang.IllegalArgumentException to be thrown, but nothing was thrown.` Fix: `DiffusionStep` validates the capacity inside the compute phase and throws `IllegalArgumentException("absorption capacity must be finite and >= 0, was X for chunk x,z")` before the apply loop, so nothing is mutated; the old `Math.max(0, …)` clamp is gone. Note: capacity is only asked for chunks holding absorbable pollution (a chunk with none is never queried), documented in the Javadoc. The first chunk to be rejected may be a neighbour (lowest key), not the seeded one; the test asserts only the message shape.

## Review item 3: gradle.properties
I did **not** add `pollutioncraft.devInstance`; it was in the working tree before I started (visible in my first `git status` of this task) and I left it untouched. Keep it out of the commit.
