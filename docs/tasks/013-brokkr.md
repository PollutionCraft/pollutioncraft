# 013 (Brokkr): `AbsorptionModel` soft-cap curve, `persistent` fixed at `r = 0`

Read `docs/design.md` first. This brief carries the rest. Engineers do not commit; Heimdall reviews and commits.

**Worktree:** `~/workspace/minecraft/pollutioncraft-013`, branch `feature/013-absorption-model` (from `main` at `871ca21`).

**Core only. Pure Java, no Minecraft.** No design gate: the maths is fixed by ADR 0004, and the open design questions (where constants live, the save format, sampling) belong to 014 and don't affect this task.

## Goal

The maths of ADR 0004 B (accepted): **a soft cap per absorber type per chunk.** A chunk's absorption capacity per interval comes from its absorber counts per type:

```
capacity = Σ over types t of  cap_t · (1 − e^(−n_t / k_t))
```

- `n_t` is the number of absorbers of type `t` sampled in the chunk;
- `cap_t` is the most that type can ever absorb in one chunk per interval (PU);
- `k_t` sets how fast the curve approaches `cap_t`. At `n = k` a type gives `cap·(1 − 1/e) ≈ 63 %`, and every extra absorber still adds a little: no cliff.

For the alpha, the user decided (2026-10-27) that **only blocks absorb**: leaves, grass and plants, crops, water; no entities. Placeholder curves, used in 014 and not hard-coded here: leaves `cap 2.0, k 64`; grass `0.5, 128`; crops `1.0, 64`; water `0.25, 128`. `defaultCapacity` 0.5 for never-sampled chunks (014 sets that config default). This task is agnostic about which types exist.

**`r = 0` (ADR 0004 E):** in the alpha, no emission goes to the `persistent` pool. That is already true: `DiffusionJob` puts emissions into `absorbable`. **No `r` config key and no split code** (pending Mimir's confirmation; if he wants a key, it comes in 014). This task only **pins the invariant with a test**.

## Design references
- ADR 0004 (accepted): D + B + E; this task is B, plus E's invariant.
- Architecture 3.2 (`absorbable`, `persistent`), 3.3 (interval step 3: absorb `min(absorbable, capacity(chunk))`), 5 (field budget 0.5 ms/tick).
- Code: `core/AbsorptionModel.java` (interface from 005, no callers yet), and `DiffusionJob`, which already applies a `LongToDoubleFunction absorptionCapacity` per chunk and records `absorbed`.

## What to build
1. **`AbsorberCurve`**, an immutable value: `cap` finite and ≥ 0, `k` finite and > 0. Anything else throws `IllegalArgumentException` naming the bad value. `absorb(int n)`: `n ≥ 0`, returns `cap · (1 − e^(−n/k))`; a negative `n` throws.
2. **The type table and capacity:** a small immutable class, for example `SoftCapAbsorption`.
   - It is built from an **ordered list of (type id, curve)**. Type ids are plain strings; duplicate ids are rejected.
   - It returns `capacity(int[] counts)`, where `counts[i]` is the count for the i-th type of that table. A length mismatch or a negative count throws.
   - It offers `indexOf(String typeId)` for 014's sampler. An unknown id returns −1 and is never thrown on, so 014 can skip types a datapack removed.
   - **Not on the per-interval path:** 014 computes a chunk's capacity **when the chunk is sampled** (and again on a data reload) and keeps it as a plain `double` per chunk, so the interval only reads a number. `capacity` still allocates nothing and sums in table order (deterministic). (Odin, 2026-10-27.)
   - The result is finite and ≥ 0, so `DiffusionJob`'s capacity check never trips.
3. **`AbsorptionModel`:** keep it the seam the scheduler consumes, `capacity(long chunkKey)`. Make it a functional interface (or document that it is one), so 014 can pass it where `DiffusionJob` takes a `LongToDoubleFunction`. **No implementation of `AbsorptionModel` here.** Looking up a chunk's counts is 014's (sampler and cache).
4. **The `r = 0` invariant:** a unit test through `DiffusionStep`/`IntervalScheduler`. Over many intervals with emissions, spread and a positive capacity, every chunk's `persistent` stays exactly 0 when it started at 0, and emissions arrive only in `absorbable`.

## Tests to write first (each with its quoted red)

The red is a failing assertion, or a compile error **only** while the class under test doesn't exist yet.

1. **`AbsorberCurve`:**
   - `n = 0` gives 0;
   - `n = k` gives `cap·(1−1/e)` (within 1e-12);
   - large `n` approaches `cap` and never exceeds it;
   - strictly increasing in `n` while below `cap` in floating point;
   - `cap = 0` always gives 0;
   - invalid `cap`/`k` (negative, NaN, infinite, `k = 0`) and negative `n` throw.
2. **`SoftCapAbsorption`:**
   - the sum over types equals the sum of each curve;
   - order of types in the table doesn't change the result for the same counts by id;
   - duplicate ids rejected;
   - a length mismatch or a negative count throws;
   - `indexOf` of an unknown id is −1;
   - an empty table gives capacity 0.
3. **Anti-spam shape** (ADR 0004 B as a player sees it, using the placeholder leaves curve): going from `k` to `10·k` leaves adds less than the first `k` did, and less than adding `k` grass to `k` leaves. The test documents why many of one type doesn't scale.
4. **With `DiffusionStep`:**
   - one isolated chunk below `spreadThreshold`, emission `E` per interval, capacity `C` from a `SoftCapAbsorption`;
   - if `E ≤ C`, local is 0 after each interval;
   - if `E > C`, it grows by exactly `E − C` per interval;
   - `absorbed` in `ChunkIntervalStats` equals `min(E, C)`.
5. **The `r = 0` invariant** (item 4 above).
6. **Measurement (reported, with a budget per tick):**
   - report the cost of one `capacity` call (ns, 4 types) and of recomputing all 2,500 chunks × 4 types once (about 10,000 `exp` calls; expect a few tenths of a millisecond);
   - **budget:** that full recompute fits within one interval at **≤ 0.05 ms/tick averaged** (≤ 1 ms per 20-tick interval);
   - in normal play, recomputes are spread over the round-robin sampling (≤ 1 chunk per tick) and only a data reload recomputes everything.
   - No assertion on wall time; report the figures as `DiffusionPerformanceTest` does.

## Files owned (exclusive)
- `core/src/main/java/io/github/pollutioncraft/core/AbsorptionModel.java` (documentation and the functional-interface marker only);
- new `core/…/AbsorberCurve.java` and `core/…/SoftCapAbsorption.java` (names are your call; say so in the report);
- new tests in `core/src/test/java/io/github/pollutioncraft/core/` for the above, plus the `DiffusionStep` and `r = 0` tests (a new test class; don't edit `DiffusionStepTest` beyond adding one if you prefer a new class);
- `docs/tasks/013-brokkr-report.md`.

**Not yours:** everything in `mod/**` and `api/**`, other core classes, config keys, data files. Wiring into `PollutionManager` is 014's.

## Commands to run before handing back
- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0 (no GameTests change, but the full gate must stay green); report the counts.
- `./gradlew :core:test`: report the new tests and the measurement.

## Report (`docs/tasks/013-brokkr-report.md`)

Include:
- status, files added;
- a red line for each test;
- fresh command output;
- the measurement;
- concerns.

Do not commit.

## Out of scope
Sampling, tags, the type list, config keys, the save format, `PollutionManager` wiring (014); GameTests and equilibrium over spread (015); any `r` mechanism (post-alpha).

If you need anything outside your files, or hit a design gap, ask Heimdall; do not decide it.
