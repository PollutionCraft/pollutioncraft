# 005 review (Heimdall) — CHANGES REQUESTED

What I verified myself: `clean build runGameTestServer --rerun-tasks --no-build-cache` exits 0, 26 tests pass, all architecture checks (API purity included) pass, and the GameTest passes. The design and the task 005 brief are met apart from the items below.

## Required

1. **A NaN or negative capacity silently corrupts the field.** `DiffusionStep.java:92`: `Math.max(0, NaN)` is NaN in Java (checked in jshell), so `absorbed` and then `absorbable` become NaN. A NaN chunk is never pruned (`NaN <= 0` and `NaN < epsilon` are both false). It is then saved and spreads NaN to its neighbours on the next interval. A negative capacity is quietly clamped, which hides a broken `AbsorptionModel`. Handle capacity the way you handle emissions: a capacity that is non-finite or `< 0` throws `IllegalArgumentException` naming the chunk. It must throw **before any change is applied**, so the field stays untouched (the compute phase already runs before the apply loop, so throwing there is enough). Write a test first, like `invalidEmissionsAreRejectedAndLeaveTheFieldUntouched`: NaN, −1 and +∞ capacity, field unchanged.
2. **Evidence that the tests failed first.** Send a short report as `docs/tasks/005-brokkr-report.md`: for each brief test (1–5), how you saw it fail before the implementation existed (paste the failing line), then the green run. Include the new test from item 1.
3. **`gradle.properties` must not change in this task.** The working tree adds `pollutioncraft.devInstance=/home/nss/...`. Design §7 says the dev-instance path lives in `~/.gradle/gradle.properties` or the env var and is never committed. If you added it, revert it. If the user did, leave it alone; I'll keep it out of the commit either way.

## Rulings on your open decisions

1. **The sub-epsilon remainder of pruned chunks is dropped and not in the stats:** accepted. It is bounded by `epsilon` per pruned chunk, and the architecture 3.2 edit says so.
2. **Interface signatures:** accepted.
   - `AbsorptionModel.capacity(long chunkKey)`, `GlobalPressure.pressure(long activeTicks, double grossEmitted)` and `AggressionPolicy.isAggressive(double local, double pressure)` match ADRs 0002 and 0004 and 3.3.
   - The capacity function argument fits `model::capacity`.
3. **`PollutionApi` uses `ServerLevel`/`BlockPos`/`MinecraftServer`:** accepted. It matches 3.5 and API purity.
4. **`ChunkKey` matches `ChunkPos.asLong`:** accepted.
5. **Fixed-order pull with ascending keys:** accepted. It gives determinism without depending on map order.
6. **Per-run `TreeSet`/`HashMap` allocation:** accepted for now (core is JDK-only, so no fastutil). Task 007 must measure one full step at 2,500 chunks against the 0.5 ms/tick budget (architecture §5), using its time slicing. If it doesn't fit, task 007 raises it and switches to primitive arrays.

## Noted for later tasks (no action here)

- `dominantSource` is never cleared when a chunk stops emitting, while `emittedLastInterval` is. Resetting it per interval belongs to the emit buffer (task 010); I'll put it in that brief.
- `ChunkPollution` has public mutable fields. That's acceptable for a core state holder. Storage (007) and emit (010) write through them; if invariants appear, encapsulate then.

## Round 2 — APPROVED

What I verified myself: `clean build runGameTestServer --rerun-tasks --no-build-cache` exits 0 with 27 tests passing. The capacity check runs in the compute phase, before any change is applied, and the new test covers NaN, ±∞ and −1. The report shows red for every brief test except 3. Brief test 2 was proven red with a mutant. The determinism test (brief 3) is accepted as a regression guard only: with identical key sets, `HashMap` order can't differ, so no mutant can make it fail. Capacity being asked only for chunks with absorbable pollution is accepted (documented). `gradle.properties` is kept out of the commit.
