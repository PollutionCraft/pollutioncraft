# 011 review (Heimdall): CHANGES REQUESTED (three small items); design and engine logic accepted

## What I verified myself
- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache`: **exit 1**, "1 required tests failed": `measureTheEngineCost` with "100 emitters tracked, got 101". JUnit XML: core 58 and mod 144, 0 failures.
- `./gradlew runGameTestServer` straight after: exit 0, "All 52 required tests passed".
- So the suite is not reproducible run to run. Cause and fix below.
- **Ownership:**
  - `PollutionManager` changed only by the engine hook (one call before `scheduler().tick`) and the `stepNow` Javadoc line.
  - New `mod/emitter/**` (8 classes), the furnace mapping and its README under `data/pollutioncraft/pollution_emitters/`, the test lamp mapping under the gametest resources, `EmitterGameTests`, and two unit test classes.
  - Nothing outside the brief.

## Required changes

### 1. README path warning (required; the brief's mistake, not yours)
- Every server start and `/reload` logs `Invalid path in pack: pollutioncraft:pollution_emitters/README.md, ignoring`. Resource paths must be lowercase, and the file ships in the jar.
- **Fix:** move it to `docs/emitter-mappings.md` in this repo. That file is yours for this task, and the format doc belongs with the engineering docs, not in the datapack.
- Keep no file under `data/` that is not a mapping.
- Show that the warning is gone from the GameTest log.

### 2. Rescan budget (see answer 2 below)

### 3. The flaky test
- **Where:** `EmitterGameTests.java:542`, `trackedCount(level) == 100`.
- **What it counts:** every tracked position in the **whole level**.
- **Why it's flaky:** the GameTest world is `level-type=normal` with `generate-structures=true` and a new seed every run, now that 010 deletes the world first. A generated furnace or redstone lamp in any loaded chunk (a village, a structure) is a legitimately tracked position, so the count is sometimes 101. The engine is right; the assertion is wrong.
- **Fix:** assert that each of the 100 placed positions `isTracked`. Report the whole-level count only as information, or count just the `busy` chunk. Do the same for the `tracked` figure at line 555, which feeds the 1,000-emitter report.
- No other test asserts a whole-level count on the global engine: line 320 uses a fresh, empty engine.
- **Evidence:** the red is this run's output, quoted above. Then two consecutive `runGameTestServer` runs, both 52/52.

## Mutation check (scratch copies; the worktree was not touched)

**A+B:** `activeRate` ignores `active_when`, and the `NeighborNotifyEvent` handler is disabled. 10 GameTests fail:
- `anUnlitLampEmitsNothing`
- `theShippedFurnaceMappingIsActiveAfterANormalStart` ("an unlit furnace is not active")
- `aLampPlacedAfterTheChunkLoaded…` ("helper.setBlock (flag 3)")
- the M1 furnace test ("lit furnace: exactly 1 PU")
- `removingTheLamp…`, `theManagerTick…`, `aLitLamp…`, `unloading…`, `aLoadedChunkThatDoesNotBlockTick…`, `resolution…`

**C+D:** the ticking check is ignored, and the multiplier is not applied. These fail:
- the M1 test ("multiplier 2: exactly 2 PU")
- `aLoadedChunkThatDoesNotBlockTick…` ("a non-ticking chunk emits nothing")

The GameTests catch what they claim to.

## Answers to the lead's questions
1. **Red-first: accepted.**
   - The unit reds are missing-symbol compile errors, which the brief allows.
   - The GameTests failed on real assertions against a stub engine.
   - `noPlayerOnlineMeansNoEmission` cannot go red against a stub, because a stub emits nothing. A mutant that runs the engine outside the gate is the right red for an "absence" test, and you showed it.
2. **Rescan slice: lower `RESCAN_SECTIONS_PER_TICK` from 4 to 3 (required).**
   - Your run measured max 93.7 µs. My passing run printed `rescan slice, worst case: mean 73.0 us, max 120.2 us per tick`, which is over the 0.1 ms ceiling, so 4 has no margin on an ordinary dev machine.
   - At 3 sections the worst-case slice drops by about a quarter, and throughput (60 sections/s) is still ample: chunks without mapped blocks cost only the `maybeHas` check.
   - Report mean and max from two runs. Accept if the mean is ≤ 70 µs and no max is more than about 1.2× the ceiling, because single-tick maxima include GC and JIT noise.
   - The total stays far inside 0.5 ms/tick either way (0.068 ms/tick measured).
3. **Your concerns:**
   - **No cap on tracked positions per chunk:** follow-up, not now. The emission pass is O(tracked): 152 µs once per interval at 1,024 emitters, so the spike grows only with very large or WorldEdit-built bases. Memory per position is one long; `EmissionBuffer` already caps attributed positions. Recorded for the P4 run: if it shows, slice the emission pass across ticks or cap positions per chunk.
   - **Sampling aliasing:** accepted by design (step-0 review). It matches the PU definition, so there is no follow-up.
4. **The M1 GameTest proves M1 for the furnace.**
   - It uses a real burning furnace (coal and cobblestone, waiting until `lit` is true) in a forced, block-ticking chunk, and an unlit furnace in another chunk. It runs through the production path (`PollutionManager.tick`, so the gate, the engine hook and the scheduler).
   - After one interval: exactly 1.0 PU in the lit chunk, 0.0 in the cold one, and `grossEmitted` +1.0. Then +2.0 with `emissionMultiplier` 2.
   - Both mutants above break it.

## Pass 2: quality (no action)
- The design matches the approved step 0 with all amendments:
  - rescan budgeted by work, with the cursor kept between ticks;
  - a rule swap requeues every loaded chunk;
  - events run on the server thread (`ChunkEvent.Load` observed on "Server thread"; anything else goes through `server.execute`);
  - the ticking check is a seam;
  - the `PollutionManager` path cache was not needed.
- `EmitterEngine.tick` catches its own failures and logs once, so an engine fault never abandons a diffusion interval.
- Rules are one immutable reference. Conflict resolution follows id order. `MappingValidator` has no Minecraft or Gson types.

Re-review: the three changes, two consecutive passing runs with their PERF lines, and no `Invalid path` warning. Then I commit.

## Round 2: APPROVED

**What I verified myself:**
- `clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0, "All 52 required tests passed".
- `runGameTestServer` straight after: exit 0, 52/52.
- JUnit XML: core 58 and mod 144, 0 failures, 0 errors.

**The three fixes:**
1. **README:** moved to `docs/emitter-mappings.md`. `data/pollutioncraft/pollution_emitters/` holds only `minecraft/furnace.json`. No `Invalid path` line appears in either GameTest log.
2. **Rescan budget:** `RESCAN_SECTIONS_PER_TICK = 3`. Worst-case rescan slice on my machine: mean 59.9 µs, max 76.1 µs (run 1); mean 56.0 µs, max 72.3 µs (run 2). Both are under the 0.1 ms ceiling.
3. **Measurement test:** it checks that each of its own placed lamps `isTracked` (helper at line 625). No assertion on a whole-level count of the global engine remains; the only `trackedCount` assertion left (line 320) is on a fresh, empty engine.

**Measured on my machine (two runs):**
- Total diffusion + emission pass at 100 emitters + idle rescan: 0.071 / 0.092 ms/tick, against a 0.5 budget.
- Emission pass: 17.0 / 18.7 µs per interval at 100 emitters, 147.7 / 148.2 µs at 1,024.
- Chunk-load scan: 0.3 / 0.5 µs plain, 482.8 / 438.9 µs worst case.
- `NeighborNotifyEvent`: 18 / 20 ns per event.

**Committed by Heimdall.**

**Follow-ups recorded:**
- A cap on tracked positions per chunk, or slicing the emission pass: re-check at the P4 performance run.
- Data-path convention (`pollution_emitters/<target mod id>/`) for Mimir to record in the design.
