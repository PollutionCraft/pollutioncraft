# 010 review (Heimdall): CHANGES REQUESTED (one Javadoc item); the code is accepted

## What I verified myself

- **Full build:** `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache` exits 0 with "All 28 required tests passed". JUnit XML: core 58 tests, mod 104, 0 failures, 0 errors.
- **Performance:** `DiffusionPerformanceTest` on my run: plain step 0.081 ms/tick; drain + step with 100 emitting chunks, median 2.360 ms per interval = **0.118 ms/tick**. Your report says 0.082; the difference is machine noise. Both are well inside the 0.5 ms/tick budget.
- **File ownership:** every changed or new file is in the brief's list.
  - `WorldPollution`, `WorldRecord` and `ConfigRegistryTest` are untouched.
  - The `api` change is Javadoc only.
  - Nothing under `mod/command/**`.
- **GameTest mutants** in scratch copies (the worktree was not touched):
  - **M1:** the thread check in `emit` is disabled and `abandon()` no longer re-queues. `anOffThreadCallIsRejectedAndChangesNothing` and `abandoningAfterTheDrainRequeuesAndCountsOnce` fail.
  - **M2:** `tickDimension` drops the commit callback. `emitAppearsAfterOneInterval…`, `abandoningAfterTheDrain…` and `pendingAndUncommittedEmissionsAreLost…` fail ("gross rose by 0.0").
  - The GameTests catch what they claim to.
- **Combined with 009:** a scratch tree of this worktree plus Sindri's current `mod/command/**`, its tests and `DebugCommandGameTests.java`.
  - `./gradlew build` exits 0: everything compiles and all unit tests pass.
  - `runGameTestServer`, first run: one failure, in Sindri's new decode test (y −60 shown as 4036).
  - **Correction:** that was not a 009 bug. My copy captured Sindri's own mutation in progress (y decoded without sign extension). The decode code is unchanged and correct.
  - Re-run against Sindri's final files: `clean build runGameTestServer` exits 0 with "All 37 required tests passed".

## Pass 1: spec compliance

- **Binding rule 1 (write rule):** `emit` only adds to the buffer. The field changes only in `DiffusionJob.apply`.
- **Binding rule 2 (`isPreserved()` refuses writes):** a paused world or dimension drops the emission. It is logged once per dimension and nothing is buffered (GameTest `emitIntoAPausedDimension…`).
- **Binding rule 3 (decision 2026-10-26, plan page 010):**
  - Drained emissions go back into the buffer on every path that drops an interval: `abandon()`, a failure while finishing, a failing `stepNow` interval, and a failing `startJob`.
  - `emittedLastInterval` and the dominant source are written only in `apply`. `grossEmitted` is added only through `onCommit`, after the field write.
  - `tickDimension`'s `abandon()` after a `finishNow` failure does nothing the second time (`job == null`), so nothing is re-queued twice.
  - This matches the plan page and architecture 3.3.
- **Frozen interface for 009:** unchanged.
  - `PollutionManager.world`/`dimension`/`stepNow(ServerLevel,int)`/`StepResult`
  - `DimensionPollution.isPreserved`/`field`/`scheduler`/`setDirty`
  - `IntervalScheduler.abandon()`/`inFlight()`
  - `WorldPollution.isPreserved`/`record`
  - The combined build confirms 009 compiles against it.
- **Heimdall decisions in the brief:** all followed.
  - Re-queue is behind `abandon()`.
  - The buffer is per dimension and never saved.
  - The source key comes from `StackWalker`, only when a call is rejected.
  - Excess positions go to an unattributed amount, at most `cap + 1` entries.
  - `maxEmittersPerChunk` defaults to 4096 within [1, 1e6].
  - Sums are taken in a fixed order; ties go to the lowest packed position.
  - A chunk that emitted nothing is cleared.
  - `onCommit` feeds `grossEmitted`, `stepNow` included.
  - `pendingEmission` is provided.

## Required change

1. **`PollutionApi` Javadoc**, part of the semver-covered contract (`api/.../PollutionApi.java`):
   - Delete the stale sentence "Interface and contract only; the implementation arrives with the storage and emit tasks."
   - Make the log-once wording match the behaviour: invalid amounts are logged once per calling class **per dimension**, and off-thread calls once per calling class.
   - No code change. Re-review is a diff read plus `./gradlew build`.

## Your questions, decided

1. **Red-first not followed.** This breaks the iron law, and I'm recording it. I won't send the work back:
   - your unit-test mutants and my two GameTest mutants show that every new test catches what it claims to;
   - the GameTest that failed on your first run was a real isolation bug, and it is fixed.

   **For 011 and later:** paste a red run per test before implementing. Missing it again is grounds for rejection.
2. **Scheduler API:** the constructor `(Supplier<Settings>, EmissionBuffer)`, `tick(field, capacity, DoubleConsumer)` and `stepNow(field, capacity, n, DoubleConsumer)` are accepted. A callback rather than a return value is the right call, because `stepNow` can commit several intervals before one throws.
3. **Behaviour changes, both accepted:**
   - An in-flight interval that fails while finishing is now dropped and re-queued. That is exactly binding rule 3.
   - `stepNow` marks the dimension dirty in `finally`, because earlier intervals may already have committed. `setDirty` on a paused record already does nothing.
4. **Your concerns:**
   - **Data-file path rebuilt on every `emit` call:** follow-up, not 010. For 100 emitters it is about 200 lookups per interval, which is negligible next to the measured budget. The 011 brief will require 011 to measure `emit` cost inside its 100-emitter budget. If it shows up there, 011 caches the `DimensionPollution`/`WorldPollution` lookup in `PollutionManager`.
   - **Bad-amount log-once per dimension:** accepted. Extra lines are bounded by the number of dimensions. Fix the Javadoc wording (required change 1).
   - **Overflow to infinity:** follow-up, escalated to Mimir (product-architect). The real gap is wider than `addGrossEmitted`:
     - every emitted amount is finite, but a sum (per position, per chunk, or the interval total) can overflow to infinity when amounts near `Double.MAX_VALUE` are emitted;
     - the field would then hold infinity, and later NaN through spread, and the codec skips those values on reload;
     - `addGrossEmitted` would throw after the field write.

     No legitimate source comes near that, and the server does not crash: `tickDimension` catches the exception. The fix needs a design decision on a PU upper bound (per call, per chunk, or saturating sums). It changes the documented API contract, so it isn't mine to make silently.

## Pass 2: quality, notes (no action)

- `EmissionBuffer` and `DrainedEmissions`: clear ownership. Arrays are sorted once per drain, and lookups use binary search.
- Running `pending` vs the drain total in fixed order: documented, fine.
- An unattributed-only chunk is impossible with `cap ≥ 1`, as you say. No test needed.

## Integration order

The two tasks touch disjoint files. Merge 010 first, then 009 after its re-review; 009 already uses `ConfigRegistry.KEYS.size()`. After both are in, the 009 follow-up adds the `pending` line and `ServerConfig.value(ConfigKey)`.

## Round 2: CHANGES REQUESTED (GameTest world isolation; the Javadoc is accepted)

### Combined-tree decode failure: cause found, no owner, no fix needed
- The scratch copy I combined at 18:28:56 has `long y = pos & 0xFFF;` in `PollutionText.java:75`. That is Sindri's mutation check (decode without sign extension), caught in progress.
- Sindri's current file (modified 18:29:28) has `long y = pos << 52 >> 52;`. A diff of the two copies shows that one line as the only difference.
- `ChunkPollution` in the combined tree is identical to 010's.
- With Sindri's final files the combined tree passes `clean build runGameTestServer` with "All 37 required tests passed".
- Not an interaction with 010 and not a 009 bug. Neither Brokkr nor Sindri has anything to fix.

### Javadoc fix: accepted
`git diff api/` shows the stale sentence removed and the wording "logged once per source (the calling class) per dimension", with off-thread calls once per calling class. That matches the code.

### New finding: GameTests are not isolated across runs (required)
**What I saw:** `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache` in this worktree exited **1**: "1 required tests failed". The failing test is 007's `configOverrideSetMidIntervalAppliesFromTheNextInterval` ("the running interval must keep the old fraction, got 2.615365937495672"; expected 2.0). Unit tests: core 58 and mod 104, all pass.

**Cause, proven:**
- `clean` does not delete `mod/run/gametest/world`, so the pollution field saved by earlier runs is loaded again.
- I decoded `mod/run/gametest/world/data/pollutioncraft_field.dat`. Around (80000, 80000) it holds 21 chunks. The test's `clearAround` clears only the centre and its 4 orthogonal neighbours.
- The diagonals (80001, 79999) and (80001, 80001) each hold 15.385 PU, above `spreadThreshold` 15. Each sends 0.02 × 15.385 = 0.308 into (80001, 80000), and 2 + 2 × 0.308 = 2.615, exactly the value observed.
- The residue builds up because each run's later `PollutionManager.tick` calls (from other tests) keep diffusing the test's region, and the world is saved.
- 010's new GameTests add more of those ticks, which is why this shows up now.
- **Control:** a scratch copy with no `mod/run` (fresh world) passes, with exit 0 and "All 28 required tests passed". CI is unaffected because it always starts fresh. Local runs are not reproducible.

**Fix (Brokkr, in 010):**
- `runGameTestServer` must start from a fresh world every run. Delete `mod/run/gametest/world` before the run, configuration-cache friendly, and keep `run/gametest/config`.
- For this task I give you `mod/build.gradle`, for this change only.
- Do not change the test itself: with a fresh world, only that test touches the 80 000 region within a run.
- **Evidence:** run `./gradlew runGameTestServer` **twice in a row** in this worktree, without deleting anything by hand, and paste both "All N required tests passed" lines. Also show the red: the current worktree fails on the second consecutive run, as seen above.

009's worktree has the same exposure, since its tests also clear small squares in a persistent world. The Gradle fix covers it once 010 merges, so 009 needs no change.

## Round 3: CHANGES REQUESTED (one line in a 007 test helper); everything else accepted

### What I verified myself
- **`mod/build.gradle` diff:** only the `runGameTestServer` `doFirst`, which also deletes `run/gametest/world` (`deleteDir`). `run/gametest/config` is kept, and the change is configuration-cache friendly (captured `File`, no `project` at execution).
- **Two runs in a row in this worktree:**
  - `clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0, "All 28 required tests passed".
  - `runGameTestServer` again: exit 0, "All 28 required tests passed".
  - The world-isolation fix is accepted.
- **The `abandon()` you added at the end of `pendingAndUncommittedEmissionsAreLost…`:** accepted. Leaving no interval in flight is good hygiene.

### 007's spread test is order-fragile: fix it now (Brokkr)
**The fragility, by reading the code:** `pollutionSpreadsToNeighboursWhileAPlayerIsOnline` writes the field (`clearAround`, `getOrCreate(...).absorbable = 100`) without `abandon()`, then ticks exactly one interval (20 ticks). Suppose any earlier test left an interval in flight with k ticks done:
- the first 20 − k ticks finish the **old** snapshot, which never saw the seeded chunk;
- the test's own interval then starts and cannot finish within the remaining ticks;
- the neighbour stays empty and the test fails.

Seeding without `abandon()` also breaks the binding write rule: field writes go only after `abandon()`, through `stepNow`, or queued.

**Evidence:** a scratch copy with your cleanup line removed passed this time (exit 0, 28/28), so the failure depends on timing and order, as you suspected. It is not deterministic per run. A scratch copy with `abandon()` added to `clearAround` also passes, 28/28.

**Fix:**
- Make `PollutionSimulationGameTests.clearAround` (line 37) call `dimension.scheduler().abandon()` before removing chunks. Every seeding test in that file goes through it.
- Keep your end-of-test `abandon()` as well.
- You own `PollutionSimulationGameTests.java` in this run, and this is a test-only change exposed by 010's new ticking, so it belongs here rather than in a follow-up.
- 009's `DebugCommandGameTests.clearSquare` already abandons first, and `ConfigOverrideGameTests` drives `DiffusionStep` on its own field, so neither needs a change.

**Evidence to return:** the one-line diff, plus `runGameTestServer` twice in a row, both "All 28 required tests passed". There is no red to show, because the failure is timing-dependent. This line enforces a binding rule; it does not change behaviour.

Once that is in, 010 is approved and I commit it.

## Round 4: APPROVED

- The `clearAround` diff is one line: `dimension.scheduler().abandon()` before the removals.
- **Two runs I did myself:**
  - `clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0, "All 28 required tests passed" (18:44:21).
  - `runGameTestServer` again: exit 0, "All 28 required tests passed" (18:44:44).
  - JUnit XML: core 58 and mod 104, 0 failures, 0 errors.
- **Committed by Heimdall.**
- **Follow-ups recorded:**
  - PU upper bound / finite sums → Mimir.
  - `emit` lookup cost → 011 brief.
  - Red-first evidence is mandatory from 011.
