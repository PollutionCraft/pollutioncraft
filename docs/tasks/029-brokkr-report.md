# 029 report (Brokkr): vanilla emitter mappings after the furnace

**Status: DONE_WITH_CONCERNS.** Nothing committed. Data only: nothing under `mod.emitter`, `config`, `PollutionManager`, `core` or `api` was touched.

## Files added
- `mod/src/main/resources/data/pollutioncraft/pollution_emitters/minecraft/`: `blast_furnace.json` (2.0, `lit=true`), `smoker.json` (2.0, `lit=true`), `campfire.json` (0.25, `lit=true`), `torch.json`, `wall_torch.json`, `lantern.json` (0.01, always active). The brief's contents, with the blast furnace and smoker at 2.0 by the user's later decision (first written at 1.0).
- `mod/src/gametest/java/io/github/pollutioncraft/gametest/VanillaEmitterGameTests.java` (all 029 tests).
- `docs/emitter-mappings.md`: the "Shipped mappings" section only.

## Fresh results
- First full run, at the original 1.0 rates (superseded by the final round below): exit 0, "All 88 required tests passed :)" (18:16:18). `Class-load check passed: 23981 classes loaded, none from mod.client or net.minecraft.client (log 4015 KB)`.
- A second plain `runGameTestServer`: **"All 88 required tests passed :)"** (18:20:30), class-load check passed. (The first test-only run before that, 18:12:31, also passed with 88.)
- Unit tests: core 58, mod 200, 0 failed.

## Blocks versus the findings
Every block behaved as `029-source-findings.md` says; none had to be left unmapped. At run time: a lit blast furnace and smoker (fuel and an item to smelt) turn `lit=true` and emit; a placed campfire is `lit=true`; a campfire with `lit=false` emits nothing and emits again when set back (the test sets the state, as water and the shovel do, it does not call the shovel). The rates of the blast furnace and smoker are read from the shipped rules in the tests, so a change to 2.0 needs no test edit.

**For the user (from the findings):** a campfire can be put out and relit, unlike the user's "it never goes out", so an out campfire emits nothing; and the blast furnace and smoker burn fuel twice as fast as a furnace, so they ship at 2.0 (user decision, see the last sections). Both are data only.

## Red evidence
**Blast furnace, first** (test run before `blast_furnace.json` existed):
```
[17:52:15] ... aburningblastfurnaceemitsitsmappedrateandacoldonenothing failed at -11980122, -60, -4040835! the lit blast furnace emitted nothing
```
Then the file was added and the run passed (80 tests then).
**Smoker, campfire, torch, wall torch, lantern, 100 torches** (written together, run before their files existed; I did not run them one block at a time, to save four-minute runs):
```
acampfireemitswhilelitstopswhenputoutandemitsagainwhenrelit failed at 9069794, -60, -11090390! the lit campfire emitted nothing
alanternemitsitsmappedratewhileplaced failed at 9069826, -60, -11090309! and is tracked
aburningsmokeremitsitsmappedrateandacoldonenothing failed at 9069842, -60, -11090309! the lit smoker emitted nothing
onehundredtorchesgiveonepuandtwicethatwithamultiplieroftwo failed at 9069794, -60, -11090300! all 100 torches are tracked
awalltorchemitsitsmappedratewhileplaced failed at 9069810, -60, -11090300! and is tracked
atorchemitsitsmappedratewhileplaced failed at 9069834, -60, -11090300! and is tracked
```
**Negative controls** (soul campfire, soul torch, soul wall torch, soul lantern, lit candle) cannot fail without a mapping, so the red is a mutant: I temporarily added a file for each of the five and the test failed naming each of them; the five files were removed afterwards (`ls` shows exactly the seven shipped files):
```
blockstheuserleftoutemitnothing failed at -2618854, -60, -10096787! blocks the user left out must emit nothing: [soul campfire is mapped, soul torch is mapped, soul wall torch is mapped, soul lantern is mapped, lit candle is mapped, soul campfire added 0.25, soul torc
```
(The line is cut at 330 characters; the offenders list also contains the added amounts for all five.)

## Test map
1. `aBurningBlastFurnaceEmitsItsMappedRateAndAColdOneNothing`; 2. `aBurningSmokerEmitsItsMappedRateAndAColdOneNothing`; 3. `aCampfireEmitsWhileLitStopsWhenPutOutAndEmitsAgainWhenRelit`; 4. `aTorchEmits...`, `aWallTorchEmits...`, `aLanternEmitsItsMappedRateWhilePlaced` (placed on a stone floor or against stone; asserts the block stayed placed and is tracked; compared within 1e-9 because 0.01 is inexact); 5. `blocksTheUserLeftOutEmitNothing` (also asserts none is mapped and each stayed placed); 6. `oneHundredTorchesGiveOnePuAndTwiceThatWithAMultiplierOfTwo` (override cleared in `finally`); 7. regression evidence: 011's `anEngineWithNoRulesEmitsNothingForALitFurnace` in `EmitterGameTests`, unedited and green in the 88; 8. `measureTheShippedVanillaMappings`.
**Clean chunks:** every test picks its chunks with `cleanChunks`: it forces a chunk from a fixed candidate list, and only uses it if `EmitterEngine.global().trackedIn` is empty after the load scan, otherwise releases it and tries the next; it fails the test if there are not enough. Pollution deltas are only asserted on those chunks. No test uses the lamp as a control (a lit candle is used).
Tests that wait for ticks run in their own batch each.

## Measurements (real shipped mappings, 2,500 spreading chunks seeded as in 011 and 031; full run 18:16)
```
PERF 029 [300 torches and lanterns (with wall torches) in each of 20 chunks]: tracked 6000; emission pass 861.4 us/interval = 0.04307 ms/tick (0.144 us per tracked block); chunk-load scan 26.4 us (a plain chunk: 1.7 us); rescan slice mean 35.5 us, p99 67.5 us over 302 busy ticks; total 0.1596 ms/tick (diffusion alone 0.0801)
PERF 029 [one chunk at the cap: 2,304 torches placed]: tracked 2048; emission pass 279.0 us/interval = 0.01395 ms/tick (0.136 us per tracked block); chunk-load scan 91.0 us (a plain chunk: 1.7 us); rescan slice mean 22.9 us, p99 100.8 us over 304 busy ticks; total 0.0848 ms/tick (diffusion alone 0.0801)
```
- Total ms/tick: **0.160** for 20 chunks of 300 (6,000 tracked), **0.085** for the chunk at the cap (limit 0.5). Diffusion alone is 0.080.
- Emission pass: 861 us per interval (0.144 us per tracked block) and 279 us (0.136 us).
- Chunk-load scan: 26 us per chunk (300 mixed), 91 us at the cap; a plain chunk 1.7 us.
- Rescan slice: p99 67.5 us, mean 35.5 us over 302 busy ticks (20 chunks); p99 100.8 us, mean 22.9 us over 304 busy ticks (the capped chunk, whose p99 is the finishing tick: sort, merge and validation).
- At about 0.14 us per tracked block, 6,000 tracked blocks cost 0.043 ms/tick.

## Concerns
- A full `runGameTestServer` takes about 4 minutes on this machine (more GameTests, each loading and forcing chunks, plus the measurement).
- The cap chunk's rescan p99 sits at about 0.1 ms, as in 031.
- Chunk generation finds villages only by luck; the clean-chunk search is what keeps the tests from depending on that. I never saw it run out of the 12 candidates (150 for the measurement).

## Update: rate decision (user)
The blast furnace and smoker now ship at **2.0** (they burn fuel twice as fast as a furnace). Only the two data files and the table in `docs/emitter-mappings.md` changed. The tests read the rate from the shipped rules and were not edited: `runGameTestServer` passed with the new rates, "All 88 required tests passed :)" (18:24:40), class-load check passed. The measurements above do not use these two blocks and stand. The "For the user" note about the rate is settled.

## Final round (rate 2.0, rate test, red runs, timing)
- Data and docs are at 2.0; the "Shipped mappings" table says "burns fuel twice as fast as a furnace" for both.
- **New test** `theBlastFurnaceAndSmokerBurnTwiceAsFastSoShipAtTwiceTheFurnaceRate`: the shipped rate of each must be exactly twice the furnace's, and the furnace's must be positive. Its red, with `blast_furnace.json` temporarily set back to 1.0 (the smoker file was also removed in that run, so its half was red too):
  `theblastfurnaceandsmokerburntwiceasfastsoshipattwicethefurnacerate failed at ... shipped rates: [blast furnace 1.0 is not twice the furnace's 1.0, sm...`
- **Red runs for smoker, campfire and torch, by removing the files.** The suite cannot be filtered, so I removed `smoker.json`, `campfire.json`, `torch.json`, `wall_torch.json` and `lantern.json` together (and set the blast furnace to 1.0) for one run. Failing lines:

```
acampfireemitswhilelitstopswhenputoutandemitsagainwhenrelit failed ... the lit campfire emitted nothing
aburningsmokeremitsitsmappedrateandacoldonenothing failed ... the lit smoker emitted nothing
atorchemitsitsmappedratewhileplaced failed ... and is tracked
awalltorchemitsitsmappedratewhileplaced failed ... and is tracked
alanternemitsitsmappedratewhileplaced failed ... and is tracked
onehundredtorchesgiveonepuandtwicethatwithamultiplieroftwo failed ... all 100 torches are tracked
theblastfurnaceandsmokerburntwiceasfastsoshipattwicethefurnacerate failed ... shipped rates: [blast furnace 1.0 is not twice the furnace's 1.0, sm...
measuretheshippedvanillamappings failed ... tracked 0 in [94006, 95000]
```

  All files were restored afterwards (identical to the saved copies, blast furnace and smoker at 2.0).
- **Final results with 2.0:** `clean build runGameTestServer --rerun-tasks --no-build-cache`: **"All 89 required tests passed :)"** (18:42:01); a second plain `runGameTestServer`: **"All 89 required tests passed :)"** (18:46:17); both with `Class-load check passed`. The blast furnace and smoker tests passed unchanged at 2.0 because they read the rate from the shipped rules. Unit tests unchanged: core 58, mod 200.

## Which GameTests are slow
From the GameTest log of the second run: time between batch starts, which includes generating the chunks a test forces and waiting for them to tick. The GameTest part is about 209 s of the roughly 4 minutes; the rest is Gradle and server start.

```
   39.3s  vanilla_torch              (029)
   24.5s  vanilla_smoker             (029)
   19.9s  emitters_always_unload     (031)
   17.5s  emitters_always_nonticking (031)
   15.8s  emitters_measure           (011)
   15.1s  vanilla_lantern            (029)
   14.8s  vanilla_wall_torch         (029)
   13.5s  emitters_unload            (011)
   11.5s  vanilla_measure            (029)
    9.7s  emitters_measure_cap       (031)
    8.9s  emitters_nonticking        (011)
    8.4s  vanilla_blast_furnace      (029)
    7.0s  emitters_furnace           (011)
    1.2s  the 23 tests of the default batch together
```

- Every sequence test is in its own batch and runs after the previous one. Each forces one to five chunks and waits for them to tick. The torch test took 39 s while the lantern, the same code on another chunk, took 15 s; I did not find out why (chunk generation time varies with the seed), so do not read 39 s as that test's own cost.
- The three measurement tests (`vanilla_measure`, `emitters_measure`, `emitters_measure_cap`) take about 37 s together. They assert only that their own chunks hold what was placed and that at least 200 busy rescan ticks were sampled, so they are the candidates for an opt-in property.
- The 029 behaviour tests take about 1.7 minutes together. They could share a batch, but then a lit block waiting for a tick could add to another test's exact `grossEmitted` assertions, which is why they are separate.
