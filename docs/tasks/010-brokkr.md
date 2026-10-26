# 010 (Brokkr): Emitter API and pending buffer

Read `docs/design.md` first. This brief carries the rest, including user decisions that are binding. Engineers do not commit; Heimdall reviews and commits.

**Worktree:** `~/workspace/minecraft/pollutioncraft-010`, branch `feature/010-emitter-api` (from `origin/main` with 005, 007, 008 merged).

**Runs in parallel with 009 (Sindri, debug commands).** 009 reads the classes you own through their public methods, so some signatures are frozen (see "Frozen interface"). Task 011 (block emitter engine) is the next consumer of `emit`.

## Goal

Implement `PollutionApi.emit` so anything (the block emitter engine in 011 first) can add pollution safely from the server thread. Emissions are buffered per chunk, drained into the snapshot at interval start, and only counted (`emittedLastInterval`, `dominantSource`, `grossEmitted`) when the interval commits. Acceptance: M1's emission path (architecture 3.3 "Emission"). Core ships no mappings, so tests call the API directly.

## Design references

- Architecture 3.2 (data model and stored shape, downgrade protection), 3.3 (emission flow, interval step), 3.5 (emitter API), 4 (errors: invalid `emit` amounts dropped and logged once per source).
- Plan task 010 (`plan/tasks/010-emitter-api.md` in the docs repo) and task 005 (API interface).
- Accepted code on `main`: `IntervalScheduler`, `DiffusionJob`, `DiffusionStep`, `PollutionManager`, `DimensionPollution`, `WorldPollution`, `WorldRecord`, `ServerConfig`/`ConfigRegistry`.

## Binding rules (user decisions; do not reinterpret)

1. **Interval write rule (2026-10-22).** An interval snapshots the field on its first tick and writes on its last, so any field write in between is lost. Field writes go only through `stepNow` or `abandon()`, or are queued until the interval ends. `emit` never writes the field; it only adds to the pending buffer.
2. **`isPreserved()` means refuse writes.** If the world record or the target dimension is preserved (paused), `emit` adds nothing to the field or the buffer. It is dropped, not buffered for later.
3. **Dropped interval (2026-10-26).** When an interval is dropped (`abandon()`, including the error path in `PollutionManager.tickDimension` and a failing `stepNow` interval), the emissions already drained into it are **re-queued** into the pending buffer and join the next interval. Nothing is lost and nothing is counted twice. `grossEmitted`, `emittedLastInterval` and `dominantSource` change **only when an interval commits**, so a dropped interval leaves them unchanged.

## Decisions I am making for this task (Heimdall)

- **Re-queue lives behind `IntervalScheduler.abandon()`.** The scheduler holds the drained emissions of the in-flight interval and puts them back into the buffer on `abandon()`. Every caller of `abandon()` (the error path, tests, 009's `set`/`clear`) gets the re-queue without knowing about the buffer. Keep `abandon()` as `public void abandon()`.
- **One `EmissionBuffer` per dimension**, transient (never saved), held by `DimensionPollution` next to the scheduler and given to the scheduler at construction. The scheduler drains it at interval start; callers stop passing an emissions map. Exact new signatures of `tick`/`stepNow` are yours; keep them minimal.
- **Source identity for log-once:** no change to the `PollutionApi` signature. The source key is the calling class name, resolved with `StackWalker` **only on the rejection path** (never on a valid call). Core's `EmissionBuffer` takes the source key as a `String`.
- **Over the cap, nothing is lost:** the per-chunk cap limits *distinct attributed positions*. Once a chunk's buffer holds `cap` positions, further new positions go into one per-chunk **unattributed** amount. It counts toward the chunk total, `emittedLastInterval` and `grossEmitted`, but can never be `dominantSource`. One log line per interval at most, the first time any chunk overflows (naming chunk and cap). Re-queue follows the same rule, so memory stays at most `cap + 1` entries per chunk however many intervals are dropped.
- **Cap config key:** add `maxEmittersPerChunk` to `ConfigRegistry` (INTEGER, default 4096, bounds [1, 1 000 000], comment says it caps distinct emitter positions tracked per chunk per interval; excess still counts but is not attributed). Follow the existing camelCase key style. Read it once per interval, at drain time, like the scheduler settings. Adding the key makes it tunable in 009 automatically. Do not add any other key.
- **Determinism:** chunk totals are summed over positions in ascending packed-`BlockPos` order (then the unattributed amount), so the total does not depend on call order. `dominantSource` = the position with the highest aggregated amount this interval; ties go to the lowest packed `BlockPos` by signed `Long.compare` on `BlockPos.asLong()`.
- **"No emission" means cleared:** on commit, every chunk in the job that emitted nothing this interval gets `emittedLastInterval = 0`, `dominantSourcePos = 0`, `dominantSourceRate = 0` (rate 0 means none, as `ChunkPollution` documents). A chunk that emitted only unattributed amounts also has no dominant source.
- **`grossEmitted`:** the scheduler reports the committed interval's emission total to its caller (return value or callback, your choice, documented on the method). `PollutionManager` adds it to `WorldRecord.addGrossEmitted` in the same tick as the field apply, and marks the world dirty. That includes `stepNow`.
- **`emissionMultiplier` is not applied here** (it belongs to 011's engine). API callers see exact amounts.

## Files owned (exclusive)

`core` (main and test):
- New: `core/src/main/java/io/github/pollutioncraft/core/EmissionBuffer.java` and at most one value type for the drained snapshot (e.g. `DrainedEmissions`) if you need it.
- `IntervalScheduler.java`, `DiffusionJob.java`, `DiffusionStep.java`, `ChunkPollution.java` (doc comments only), `WorldRecord.java` (only if needed).
- Tests: new `EmissionBufferTest.java`; `IntervalSchedulerTest.java`, `DiffusionStepTest.java`, `DiffusionPerformanceTest.java` (signature updates plus the new tests below).

`mod`:
- `PollutionApiImpl.java`, `PollutionManager.java`, `DimensionPollution.java`, `WorldPollution.java` (only if needed).
- `config/ConfigRegistry.java`, `config/ServerConfig.java` (the one new key and its accessor only), and `mod/src/test/.../config/*Test.java` only if the new key makes them need it.
- GameTests: new `mod/src/gametest/java/io/github/pollutioncraft/gametest/EmissionGameTests.java`; `PollutionSimulationGameTests.java` and `ConfigOverrideGameTests.java` only to follow your signature changes.

`api`: `PollutionApi.java`, **Javadoc only** (server-thread rejection, buffering, "applied at the next interval"). No signature change.

**Not yours:** anything under `mod/src/main/java/io/github/pollutioncraft/mod/command/**`, `mod/src/test/.../mod/command/**`, `mod/src/gametest/.../DebugCommandGameTests.java` (009), `PollutionCraftMod.java`, `RuntimeOverrides.java`, `ConfigKey.java`, `ConfigValues.java`, `ServerConfigSpec.java`, any Gradle file.

## Frozen interface (009 depends on these while you work)

You may add methods. Do **not** rename, remove or change the signature or meaning of:
- `PollutionManager`: `world(MinecraftServer)`, `dimension(ServerLevel)`, `stepNow(ServerLevel, int)` returning `StepResult`, and the enum `StepResult { RAN, PAUSED_WORLD_RECORD, PAUSED_DIMENSION_RECORD }`. `stepNow` keeps refusing paused records first, keeps bypassing the player-online gate, and keeps finishing an in-flight interval first.
- `DimensionPollution`: `isPreserved()`, `field()`, `scheduler()`, `setDirty()`.
- `IntervalScheduler`: `abandon()` (no arguments; now also re-queues), `inFlight()`.
- `WorldPollution`: `isPreserved()`, `record()`; `WorldRecord`: `activeTicks()`, `grossEmitted()`, `pressure()`.
- `ChunkPollution` public fields.

## Seam to provide for 009's follow-up and for 011

Add a read-only accessor for the pending amount of a chunk, e.g. `DimensionPollution.pendingEmission(long chunkKey)` returning the buffered (not yet drained) total in PU, 0 for a paused dimension. 009 will show it in `/pollution get` in a follow-up after you merge; 011's tests can use it. Server thread only; no allocation per call beyond a map lookup.

## What to build

1. **`EmissionBuffer` (core, pure Java):**
   - `add(chunkKey, packedPos, amount, sourceKey)`: `amount` finite and ≥ 0; NaN, infinite or negative is dropped and logged **once per source key** (injected `Consumer<String>` logger, like `ConfigValues`); `0` is a silent no-op. Aggregates by position: repeated calls from one position sum. Applies the cap rule above.
   - `drain()`: returns the drained snapshot (per-chunk totals, per-chunk dominant position and rate) and leaves the buffer empty. Draining twice never double counts.
   - `requeue(snapshot)`: merges a drained snapshot back, never dropping amounts.
   - Bounded memory as above. O(1) amortised per `add`.
2. **Scheduler drain hook (core):** on an interval's first tick, drain the buffer into the job's snapshot (step 1 of the interval). Hold the drained snapshot until the interval ends. On commit, apply `emittedLastInterval` and `dominantSource` per chunk with the field deltas (in `DiffusionJob.apply`, same pass), and report the total. On `abandon()`, re-queue it. `stepNow` drains into its first interval; an interval that throws re-queues its drain before the exception propagates.
3. **`emit` (mod):**
   - Not the server thread (`level.getServer().isSameThread()` false): reject, change nothing, log once per source.
   - World record or dimension preserved: drop silently, with at most one log line per dimension per server session.
   - Otherwise add to that dimension's buffer under `ChunkKey.pack(pos.getX() >> 4, pos.getZ() >> 4)`. Never load or generate a chunk; an unloaded or never-generated chunk is a valid target.
4. **`PollutionManager`:** pass the drained total into `grossEmitted` on every commit (`tick` and `stepNow`). Remove the "emissions arrive with task 010" stubs. The error path's `abandon()` now re-queues by construction.

## Tests to write first

Write each, run it, see it fail for the right reason, then implement. In your report, paste the failing output line for each.

1. **Unit, `EmissionBufferTest`:** sums by position; dominant source choice including ties (lowest packed pos); chunk total independent of call order; zero is a silent no-op; negative, NaN and ±infinity rejected; log once per source (two bad calls from one source = one line, from two sources = two lines); cap: overflow goes to unattributed, counts in the total, never dominant, one log line per interval.
2. **Unit:** draining twice does not double count; `grossEmitted` reported totals are monotonic over a run.
3. **Unit, `IntervalSchedulerTest`:** drain, `abandon()`, drain again: the next commit applies the amounts once and reports them once. Repeated abandons keep memory bounded (`cap + 1` per chunk). A failing `stepNow` interval re-queues. An emission added mid-interval waits for the next interval (the running one is unchanged). Sliced vs `stepNow` give bit-identical fields with emissions present. A non-emitting chunk ends the interval with no dominant source and `emittedLastInterval = 0`.
4. **GameTest, `EmissionGameTests`:** `emit` into a far-away chunk; after one interval `getLocal` rose by the amount, `grossEmitted` rose by the amount, `dominantSource` is the emitting position.
5. **GameTest:** an off-thread call (e.g. from `CompletableFuture.runAsync(...).join()`) is rejected: pending amount, field and `grossEmitted` unchanged after an interval.
6. **GameTest:** abandon mid-interval after the drain: the emission arrives exactly once in the next interval and `grossEmitted` counts it once.
7. **GameTest:** emit into an unloaded, never-generated chunk; the chunk is still not loaded afterwards (`getChunkNow`/`hasChunk` false) and the field holds the amount after one interval.
8. **GameTest, restart semantics (documents behaviour):** pending and drained-but-uncommitted emissions are transient and lost on restart; committed emissions are in the saved field and world record. Show it by saving and reloading the `SavedData` from storage (as 007's tests do) rather than restarting the server.
9. Architecture checks stay green (API purity: nothing in `…api.*` references internals).

## Commands to run before handing back

- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache` — exit 0, all GameTests pass (report the "All N required tests passed" line and the unit test count).
- `./gradlew :core:test --tests '*EmissionBuffer*' --tests '*IntervalScheduler*'` — pass.
- `DiffusionPerformanceTest` with emissions in 100 chunks still within the 0.5 ms/tick budget; report the number.

## Report (`docs/tasks/010-brokkr-report.md`)

Status, files changed, red→green evidence per test, fresh command output, the scheduler signatures you chose, and concerns. Do not commit.

## Out of scope

Mapping format and block scanning (011), `emissionMultiplier` (011), absorption, the removal call (M5), any shipped mapping, pressure maths (016), command changes (009).

If you need anything outside your files, or hit a design gap, ask Heimdall; do not decide it.
