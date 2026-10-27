# 031 (Brokkr): Emitter engine: always-active rules and a tracked-position cap

Read `docs/design.md` first. This brief carries the rest, including decisions that are binding. Engineers do not commit; Heimdall reviews and commits.

**Worktree:** `~/workspace/minecraft/pollutioncraft-031`, branch `feature/031-emitter-always-active-and-cap` (from `origin/main` with everything through 030).

**Runs in parallel with spike 003 (Sindri, GregTech hook).** The spike lives on its own branch and is never merged; it touches none of your files.

**This task has a design gate (step 0).** The cap's deterministic policy and its interaction with discovery, rescan and reload are the main decision. Send the note before production code.

## Goal

Two additions to 011's engine, with **no shipped mapping**:
1. An entry without `active_when` is **always active**.
2. A **deterministic cap on tracked positions per chunk** bounds the engine's cost and memory.

They enable task 029 (torches, lanterns, campfire and the other vanilla emitters). Plan task 031.

## Design references

- Plan task 031 (`plan/tasks/031-emitter-engine-always-active-and-cap.md`); plan task 029 (what 031 must enable: light sources always active, rates such as 0.01 PU, "300 torches in each of 20 chunks").
- Your 011 work: `docs/tasks/011-brokkr-design.md`, `011-brokkr-design-review.md`, `011-brokkr-review.md`, and `docs/emitter-mappings.md` (this task owns the edit).
- Task 010: `maxEmittersPerChunk` caps *attributed* positions in the buffer (default 4,096). This task caps *tracked* positions in the engine. They are different limits.
- Architecture 3.3 (emission), 5 (budget: 0.5 ms/tick).

## Binding (Odin and the user, 2026-10-27)

1. **No `active_when` means always active.** The block emits its `rate` every interval while placed in a loaded, block-ticking chunk.
   - A **malformed** `active_when` is still invalid: skipped and logged. Examples: present but not an object, or a property without a value. The mod does not guess.
   - Entries with `active_when` behave exactly as today.
2. **Removal still needs no event.** Every tracked position is read at interval start. An always-active position is dropped when the block there is no longer mapped; only the "is the state active" test is skipped.
3. **A cap on tracked positions per chunk:**
   - a new config key, camelCase, in `ConfigRegistry`, working name **`maxTrackedPerChunk`**;
   - integer, default **2,048** (a placeholder, above "hundreds of torches" and below the 4,096 buffer cap);
   - past the cap, further positions in that chunk are **not tracked** and emit nothing;
   - **one log line per chunk per rescan period**, not per block.
4. **The cap is deterministic.** The same mapped blocks in the same chunk give the **same tracked set** after unload, reload and restart, whatever the load, placement or event order.
5. **The cap is read when a chunk is scanned or rescanned.** A `/pollution tune` change applies from the next scan.
6. **Everything else in 011 holds:** only block-ticking chunks emit; sampling is once per interval; the engine applies `emissionMultiplier`; the 60 s rescan with its work-based budget (`RESCAN_SECTIONS_PER_TICK = 3`); server thread only; no Mixin or reflection.

## Standing rules

- **Red first, quoted:** for every new or changed test, run it before the implementation and paste the failing line. A compile error counts only when the symbol under test does not exist yet. An absence test ("emits nothing") shows its red with a mutant, as in 011 and 030.
- **No flaky GameTests.** Two consecutive `runGameTestServer` runs must pass.
- **Lessons from 011:**
  - Assert on **your own placed positions** (`isTracked`), never on a whole-level count. The GameTest world is a normal world with structures and a new seed each run.
  - The test-only mapping uses a block the world never generates (below).
  - The path cache was not needed; don't add one unless a measurement shows it.

## Step 0: design note (send to Heimdall before production code)

Write `docs/tasks/031-brokkr-step0.md`, covering:

1. **Cap policy and its determinism.** The plan's example is "the lowest packed positions win" (`BlockPos.asLong()`, signed `Long.compare`). Propose the rule and the data structure, and show that every discovery path converges on the same set: chunk-load scan, `NeighborNotifyEvent` placement, rescan.
2. **Event placements against a full chunk.** What happens when a block is placed in a chunk already at the cap? Under "lowest wins": track it and evict the highest if it's lower, otherwise ignore it.
3. **Removal under the cap.** A tracked block removed from a capped chunk frees a slot, but over-cap blocks are unknown to the engine. Say how the chunk returns to the deterministic set: for example, mark the chunk "over cap" and queue it for a rescan when it loses a position. Also state the transient behaviour until then.
4. **Cap changes.** `tune` lowers or raises the cap, read at the next scan. Say whether a change re-queues all loaded chunks (as a rule swap does) or waits for the 60 s refill, and how lowering trims an already-tracked chunk.
5. **Reload and rescan interaction.** A rule swap with capped chunks, and the rescan's work budget when a chunk holds thousands of mapped blocks. The section walk is unchanged, but sorting and trimming cost something.
6. **The log line.** "One per chunk per rescan period": the exact condition, and how you keep it from repeating on every event placement into a full chunk.
7. **The key:**
   - bounds (the plan says "to be set in the design step"; a chunk has at most 98,304 block positions);
   - the default of 2,048;
   - the config comment, in the style of the existing keys;
   - and confirm there is **no cross-field rule** with `maxEmittersPerChunk`.

   My view on the last point: don't add one. If an operator sets `maxTrackedPerChunk` above `maxEmittersPerChunk`, the excess emissions still count; they are only unattributed (010's rule). A cross-field rule would also trigger 008's open obligation: the first multi-key rule must re-check effective values on reload and define what happens to an override that becomes invalid. Say so in the note, or argue otherwise.
8. **Validation change.** How `MappingEntry` represents "always active" (for example `property` and `value` both null, or an explicit flag), and which half-specified forms are rejected.

I reply with OK or changes. While you wait, you may write the unit tests for the validator.

## Decisions I am making for this task (Heimdall)

- **Test-only always-active mapping:** `mod/src/gametest/resources/data/pollutioncraft/pollution_emitters/test/glowstone.json` (`minecraft:glowstone`, no `active_when`, rate **0.25**).
  - Glowstone has no block state and is never generated in the overworld, so generated terrain can't add tracked positions or count toward a cap in your test chunks.
  - 0.25 is exact in binary, so "cap × rate" sums compare exactly.
  - Do **not** use torches or lanterns: 029 will ship those, and a test mapping for the same block would conflict with the shipped one (lowest id wins, plus a warning).
- **Low cap in tests:** set through `PollutionCraftMod.config().overrides().set("maxTrackedPerChunk", …)` and cleared in `finally`. Tests then exercise the real tune path (binding 5).
- **Rescan figure:** change 011's `measureTheEngineCost` to report the **99th percentile and the mean** of the rescan slice instead of the single-tick maximum (follow-up from the 030 review), and use the same reporting in the new measurements.

## Files owned (exclusive)

- `mod/src/main/java/io/github/pollutioncraft/mod/emitter/**` (`MappingEntry`, `MappingValidator`, `EmitterRules`, `EmitterEngine`, and any new class for the tracker).
- `mod/src/main/java/io/github/pollutioncraft/mod/config/ConfigRegistry.java` and `ServerConfig.java`: the one new key and its accessor only.
- `docs/emitter-mappings.md`: `active_when` becomes optional; the cap and its key.
- `mod/src/gametest/resources/data/pollutioncraft/pollution_emitters/test/glowstone.json` (new, test-only, never shipped).
- Tests: `mod/src/test/java/io/github/pollutioncraft/mod/emitter/**` (`MappingValidatorTest` and new ones); `mod/src/gametest/java/io/github/pollutioncraft/gametest/EmitterGameTests.java` (new tests and the rescan p99 change).
- `docs/tasks/031-brokkr-step0.md`, `docs/tasks/031-brokkr-report.md`.

**Not yours:**
- shipped data under `mod/src/main/resources/data/**` (029 adds the vanilla mappings; nothing ships here);
- `PollutionManager` (the engine hook is unchanged; if you need a change, ask);
- `mod/command/**`, `mod/overlay/**`, `mod/client/**`, other config classes;
- `core/**`, `api/**`, other GameTest files, Gradle files.

## Tests to write first (each with its quoted red)

1. **Unit, validator:**
   - an entry without `active_when` parses as always active;
   - rejected: `active_when` present but not an object; property without value; value without property; empty strings;
   - every existing case unchanged.
2. **GameTest, always active (glowstone mapping), in a forced block-ticking chunk:**
   - placed, emits exactly 0.25 per interval;
   - removed, stops from the next interval;
   - in a loaded, non-block-ticking chunk, emits nothing;
   - after unload and reload, emits again.
   - Reuse the 011 setups (ticket and forced chunk) that proved deterministic.
3. **GameTest, regression:** a 011 `active_when` entry (the test lamp) still emits only while lit.
4. **GameTest, cap,** with a low cap such as 8 set through overrides:
   - place 12 glowstone in one chunk;
   - exactly 8 tracked, namely **the 8 your rule keeps** (assert the exact positions);
   - emission per interval is 8 × 0.25 = 2.0;
   - exactly one cap log line for that chunk in the period (capture through a seam or a counting logger, not by grepping the server log);
   - a second chunk with 4 glowstone is unaffected.
5. **GameTest, determinism:**
   - the same 12 placed in a different order, and in a chunk unloaded and reloaded, give the same tracked set;
   - a block placed at a position the rule ranks inside the cap enters, and the boundary one leaves;
   - removing a tracked block from a capped chunk brings the set back to the deterministic one by the bound you state in step 0.
6. **GameTest, cap change:** lowering the cap through `tune` trims a chunk at the next scan; raising it lets the over-cap positions back in at the next scan, within your stated bound.
7. **Measurement (`measure…`, reported; must fit the budget).** All with the glowstone mapping and 2,500 spreading chunks seeded as in 011:
   - **300 always-active blocks in one chunk;**
   - **a chunk at the default cap (2,048);**
   - **300 in each of 20 chunks (6,000 tracked).**

   For each, report:
   - the emission pass (µs per interval and ms/tick);
   - the chunk-load scan (typical and worst);
   - the rescan slice (**p99 and mean**);
   - the **total ms/tick**, which must stay **≤ 0.5**.
8. Architecture and adapter-import checks stay green.

## Commands to run before handing back

- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0; report "All N required tests passed", the unit counts, the class-load check line, and the PERF lines.
- `./gradlew runGameTestServer` a second time: also passes.

## Report (`docs/tasks/031-brokkr-report.md`)

Include:
- status, files changed;
- the step-0 decisions as approved;
- a red line for every test;
- fresh command output;
- measurements;
- concerns.

Do not commit.

## Out of scope

Any shipped mapping (029), entities (032), a per-block-type cap, aggregating over-cap blocks into one emission, changes to `PollutionManager` or the overlay.

If you need anything outside your files, or hit a design gap, ask Heimdall; do not decide it.
