# 029 (Brokkr): Vanilla emitter mappings after the furnace

Read `docs/design.md` first. This brief carries the rest. Engineers do not commit; Heimdall reviews and commits.

**Worktree:** `~/workspace/minecraft/pollutioncraft-029`, branch `feature/029-remaining-vanilla-emitter-mappings` (from `main` at `b5d0c24`, with 031 merged).

**Data only.** Nothing in `…mod.emitter.*`, `config/**` or `PollutionManager`. If an engine change seems needed, stop and ask Heimdall: that would be a return to 031.

No design gate. The format, the engine, the cap and the always-active rule are accepted (011, 031). The block findings are done: `docs/tasks/029-source-findings.md` (Heimdall, from the NeoForge 21.1.264 sources). Read it first.

## Goal

Ship the vanilla "burns something" blocks as data mappings in 011's format, in the `minecraft` data folder. Plan task 029 (`plan/tasks/029-remaining-vanilla-emitter-mappings.md`). The scope and starting rates are the user's (2026-10-27), and the rates are placeholders for the P4 playtest.

## The mappings (one file per block)

Each file goes at `mod/src/main/resources/data/pollutioncraft/pollution_emitters/minecraft/<name>.json`:

| File | Content |
|---|---|
| `blast_furnace.json` | `{ "block": "minecraft:blast_furnace", "rate": 2.0, "active_when": { "property": "lit", "value": "true" } }` |
| `smoker.json` | `{ "block": "minecraft:smoker", "rate": 2.0, "active_when": { "property": "lit", "value": "true" } }` |
| `campfire.json` | `{ "block": "minecraft:campfire", "rate": 0.25, "active_when": { "property": "lit", "value": "true" } }` |
| `torch.json` | `{ "block": "minecraft:torch", "rate": 0.01 }` |
| `wall_torch.json` | `{ "block": "minecraft:wall_torch", "rate": 0.01 }` |
| `lantern.json` | `{ "block": "minecraft:lantern", "rate": 0.01 }` |

- **Not mapped (user decision):** soul campfire, soul torch, soul wall torch, soul lantern, candles, jack o'lantern, lava, fire, redstone parts and the rest of the plan's Out list. The furnace minecart is 032.
- **The blast furnace and smoker ship at rate 2.0** (user decision, 2026-10-27: pollution follows the fuel burn rate, and both burn fuel twice as fast as a furnace; see the findings). The furnace stays 1.0 and the campfire 0.25. **If you already wrote `blast_furnace.json` at 1.0, change it to 2.0.** Tests still **read the expected rate from the shipped rules**, for example `EmitterEngine.currentRules().activeRate(litState)`, and never hard-code a rate, so this needs no test change.
- No rate appears in Java code.

## Standing rules
- **A failing run before each test is implemented, with the failing line pasted in your report.**
  - For a mapping test, the natural red is the test running **before the JSON file exists**: the block emits nothing.
  - For an absence test (soul blocks, Out list), show the red with a mutant: temporarily add a mapping for that block, see the test fail, remove it.
- **Order:** the **blast furnace test first** (red, then its JSON, then green), then one block at a time.
- **No flaky GameTests.** Two consecutive `runGameTestServer` runs must pass.
- **The 011 lesson, which matters more here:** the GameTest world is a normal world with structures and a new seed each run. Villages contain torches, lanterns and campfires. Any assertion on a chunk's pollution delta is only valid if the chunk holds **no mapped block other than the test's own**.
  - Before placing anything, check the candidate chunk with `EmitterEngine.global().trackedIn(level, chunkKey)` after a scan.
  - If it isn't empty, move to the next candidate from a fixed list.
  - Assert the precondition, and never silently pass.
- **Test mappings that already exist** in the GameTest resources: `test/redstone_lamp.json` (rate 2, from 011) and `test/glowstone.json` (0.25, from 031). In GameTests the redstone lamp **is** mapped, so **don't use a lit redstone lamp as a negative control**. Use a lit **candle** (or a jack o'lantern) from the Out list instead.
- **Exact numbers:** 1.0 and 0.25 are exact in binary, so assert those exactly. 0.01 is not, so assert torch and lantern sums within `1e-9` and say so in the test.

## Files owned (exclusive)
- The six JSON files above, and nothing else under `mod/src/main/resources/data/**`.
- New `mod/src/gametest/java/io/github/pollutioncraft/gametest/VanillaEmitterGameTests.java` (all 029 tests, including the measurement).
- `docs/emitter-mappings.md`: one short section **"Shipped mappings"** listing the seven `minecraft` files (furnace plus these six) with their rates and rules, stating that the rates are playtest placeholders. It includes **one line of reason** for the blast furnace and smoker: they burn fuel twice as fast as a furnace (`getBurnDuration / 2`), so they emit 2.0 PU per interval while lit (user decision 2026-10-27). JSON has no comments, so the reason lives here, not in the data file. Nothing else in that file.
- `docs/tasks/029-brokkr-report.md`.

**Not yours:**
- `mod/emitter/**`, `config/**`, `PollutionManager`;
- `EmitterGameTests.java` and the other GameTest files;
- the test mappings;
- `core/**`, `api/**`, Gradle files.

## Tests to write first (each with its quoted red)

Use forced, block-ticking chunks (the 011 and 031 setup) and the precondition above. Drive intervals through the production path (`PollutionManager.tick(server, 1)` after `abandon()`), as 011's furnace test does.

1. **Blast furnace (first):**
   - with fuel (coal) and something to smelt (raw iron), wait until `lit=true`;
   - one interval raises its chunk by exactly the mapping's rate, and `grossEmitted` by the same;
   - an unlit blast furnace in another clean chunk adds 0.
2. **Smoker:** the same, smelting food (raw beef).
3. **Campfire:**
   - placed (lit by default), raises its chunk by exactly 0.25 per interval;
   - **put out** (set `lit=false`, or call `CampfireBlock.dowse` plus the state change, as the shovel does), then 0 from the next interval;
   - relit, emits again.
4. **Torch, wall torch, lantern:** one test each. Placed, raises its chunk by 0.01 per interval (within `1e-9`); removed, stops from the next interval. The wall torch is placed against a solid block with a valid `FACING`.
5. **Negative controls:** a lit soul campfire, a soul torch, a soul wall torch, a soul lantern and a lit candle each add nothing over one interval. Show the red with a mutant, as above.
6. **Scale:** 100 torches in one clean chunk give 1.0 PU per interval (within `1e-9`). With `emissionMultiplier` 2, set through `RuntimeOverrides` and cleared in `finally`, they give 2.0.
7. **Regression, mod without the `minecraft` data:** 011's `anEngineWithNoRulesEmitsNothingForALitFurnace` must stay green, and you don't edit it. Name it in the report as the evidence.
8. **Cost (`measureTheShippedVanillaMappings`, reported).** With the **real shipped mappings** and 2,500 spreading chunks seeded as in 011 and 031:
   - **300 torches and lanterns (mixed, including wall torches) in each of 20 chunks;**
   - **one chunk at the 031 cap:** 2,304 torches placed, 2,048 kept.

   Place them on a solid floor, as a player would. For each, report:
   - total ms/tick (must be ≤ 0.5);
   - the emission pass (µs per interval, µs per tracked block);
   - the chunk-load scan;
   - the rescan slice **p99 and mean**, over at least 200 busy ticks, with the count printed.

   Assert only on your own placed positions (`isTracked`), never a level-wide count.

## Commands to run before handing back
- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0; report "All N required tests passed", the unit counts, the class-load check line and the PERF lines.
- `./gradlew runGameTestServer` a second time: also passes.

## Report (`docs/tasks/029-brokkr-report.md`)

Include:
- status, files added;
- a red line for each test;
- fresh command output;
- the measurements;
- any block that did not behave as the findings say (then stop and report; don't map it).

Do not commit.

## Out of scope
Engine changes (031), entities (032), GregTech (012), any block not listed, rate tuning (P4), absorption.

If you need anything outside your files, or hit a design gap, ask Heimdall; do not decide it.
