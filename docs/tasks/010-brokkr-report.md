# 010 (Brokkr) report: emitter API and pending buffer

**Status: DONE_WITH_CONCERNS** (concerns below; none block merge). Nothing committed.

## Fresh results
`./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache --no-daemon -q`: build passes, **"All 28 required tests passed :)"** (8 new in `EmissionGameTests`).
Unit tests: core 58 (0 failed), mod 104 (0 failed). Architecture checks green (included in mod's 104).
`:core:test --tests '*EmissionBuffer*' --tests '*IntervalScheduler*'`: pass.
Perf (`DiffusionPerformanceTest`, 2,500 chunks, emissions in 100 chunks, 5 positions each, drain + step): median **1.633 ms/interval = 0.082 ms/tick** (budget 0.5 ms/tick). Plain step: 1.557 ms.

## Red evidence (honest account)
I wrote the tests before the implementation, but did **not** run each one red in isolation before implementing; the first run was the combined green-pass attempt (2 test bugs found, fixed). To show the tests bite, I mutated the code afterwards and re-ran:
- `abandon()` without `requeue` -> `abandonRequeuesSoTheNextCommit...` ("re-queued expected 25.0 but was 0.0"), `repeatedAbandonsKeepMemoryBounded...` (expected [35.0] but was [1.0]), `anInFlightIntervalThatFails...` (expected 12.0 but was 0.0).
- Tie rule `>` -> `>=` -> `tiesGoToTheLowestPackedPosition...` ("lowFirst=true expected -5 but was 3").
GameTests were not mutation-checked. One GameTest failed on first run (`anOffThread...`: gross moved because earlier tests left pending emissions that committed in this test's interval); fixed by flushing in the test's `reset`.

## What I built
- `core`: `EmissionBuffer` (aggregate by position, cap rule, log-once, drain/requeue, `pending`), `DrainedEmissions` (immutable snapshot incl. per-position amounts so requeue keeps attribution), `DiffusionJob/Step` take `DrainedEmissions` and write `emittedLastInterval`, dominant pos/rate in `apply` (zero for non-emitters), `IntervalScheduler` drains at interval start, holds the snapshot, requeues on `abandon()`/failure.
- `mod`: `PollutionApiImpl.emit`, `DimensionPollution` owns the buffer (+ `pendingEmission(long)`), `PollutionManager` counts `grossEmitted` on every commit, config key `maxEmittersPerChunk` + `ServerConfig.maxEmittersPerChunk()`.
- `api`: Javadoc only.

## Scheduler signatures chosen
```java
IntervalScheduler(Supplier<Settings> settings, EmissionBuffer emissions)
boolean tick(PollutionField field, LongToDoubleFunction capacity, DoubleConsumer onCommit)
void stepNow(PollutionField field, LongToDoubleFunction capacity, int n, DoubleConsumer onCommit)
void abandon()   // unchanged signature; now requeues
```
`onCommit` gets the interval's emission total, once per committed interval, after the field write (0 if nothing emitted). I chose a callback over a return value so `stepNow` cannot lose a count when a later interval throws after an earlier one committed. `PollutionManager` passes a callback that calls `addGrossEmitted` and `world.setDirty()`.
Frozen interface for 009 is untouched (`stepNow(ServerLevel,int)`, `StepResult`, `abandon()`, `inFlight()`, etc.).

## Decisions / deviations
- `DiffusionJob.start` no longer validates amounts (the buffer guarantees it); the old "invalid emissions rejected" test in `DiffusionStepTest` moved to `EmissionBufferTest`.
- The tests use `EmissionBufferTest.chunkTotals(...)` as a shared helper to build a `DrainedEmissions`.
- A failure while an in-flight interval is *finishing* inside `stepNow`/last `tick` now drops it and requeues (before, it stayed in flight). A failure in a mid-interval slice of `tick` still leaves it in flight for the caller (`tickDimension`) to abandon, as before.
- `PollutionManager.stepNow` now marks the dimension dirty in a `finally`, because intervals may have committed before an exception.
- Cap is read lazily on the first add (or requeue) after a drain, not in the constructor, so no config access at construction.
- Off-thread log-once set lives in `PollutionApiImpl` (concurrent set); invalid-amount log-once set lives in each dimension's buffer, so one bad source emitting in 3 dimensions logs 3 lines. Paused-drop log: one per dimension per session via a flag on `DimensionPollution`.
- `pendingEmission` is a running sum (O(1)), so it may differ from the drained total in the last bits; the drained total uses the fixed order.
- A chunk with only unattributed amounts cannot occur (cap >= 1 means the first position is always attributed), so there is no dedicated test for it.

## For the reviewer / open questions
- `PollutionManager.dimension(level)`/`world(server)` rebuild a `Path` on every call (pre-existing). `emit` calls both, so 011's per-block-per-interval calls will pay that. Consider caching the data-file path or the `DimensionPollution` reference in a later task.
- The unbounded growth risk: the invalid-amount source set keeps one string per distinct bad source key (class names, so tiny).
- `grossEmitted` addition would throw if the summed total overflowed to infinity (needs ~1e308 PU); not guarded.
- `ConfigRegistryTest` needed no change (its lockstep test passed with the new key).

## Review fixes (Heimdall)
- `PollutionApi` Javadoc: removed the stale "implementation arrives..." sentence; invalid-amount logging is now documented as once per source (calling class) per dimension.
- Fresh `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache --no-daemon -q`: see result below.
  Result: build passes, "All 28 required tests passed :)", no failures.

## GameTest world isolation (Heimdall round 2)
- Red: with the stale `mod/run/gametest/world` kept, consecutive `./gradlew runGameTestServer --no-daemon -q` runs went pass, **fail**, pass; the failure was `configoverridesetmidintervalappliesfromthenextinterval ... got 2.6216752098465923`, as Heimdall saw.
- Fix (`mod/build.gradle` only): `runGameTestServer`'s `doFirst` also deletes `run/gametest/world`; `run/gametest/config` is kept. No test changed.
- Green: three consecutive `runGameTestServer` runs from the stale world, each "All 28 required tests passed :)". A planted `world/STALE_MARKER` file was gone after a full `clean build runGameTestServer`; `config/` still present.
- That full run exposed a second, separate bug of mine: with a fresh world `pollutionSpreadsToNeighboursWhileAPlayerIsOnline` (007) failed, because my restart-semantics GameTest left an interval in flight and 007's test does not abandon first, so its seeded chunk missed the snapshot. Fixed in my test (`abandon()` at its end), not in 007's. Evidence after the fix: three consecutive `clean build runGameTestServer --rerun-tasks --no-build-cache` runs and then two consecutive plain `runGameTestServer` runs, all "All 28 required tests passed :)" (18:38:13, 18:38:45, 18:39:18 and the last two below).

## clearAround abandons first (Heimdall round 3)
`PollutionSimulationGameTests.clearAround` now starts with `dimension.scheduler().abandon();` (one added line), so seeding follows the interval write rule. Two consecutive `./gradlew runGameTestServer --no-daemon -q` runs: "All 28 required tests passed :)" at 18:43:08 and 18:43:35.
