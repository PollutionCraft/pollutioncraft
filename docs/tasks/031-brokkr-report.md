# 031 report (Brokkr): emitter engine, always-active rule and per-chunk cap

**Status: DONE_WITH_CONCERNS.** Nothing committed; no `eula.txt`. Design as approved in `031-brokkr-design.md` and `031-brokkr-design-review.md`, with one change of data structure (below).

## Fresh results
- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache --no-daemon -q`: exit 0, **"All 79 required tests passed :)"** (22:13:44). `Class-load check passed: 23832 classes loaded, none from mod.client or net.minecraft.client (log 3991 KB)`.
- Two further plain `./gradlew runGameTestServer --no-daemon -q` runs: **"All 79 required tests passed :)"** at 22:17:10 and 22:21:10, each with the class-load line.
- Unit tests: core 58, mod 200, 0 failed (architecture and adapter-import checks included).

## Files
`mod/emitter/`: `MappingEntry` (nullable `ActiveWhen`), `MappingValidator`, `EmitterRules` (always-active rule), `EmitterEngine` (cap, repair, scan slicing), new `ChunkTrack` and `ScanHeap`. `config/ConfigRegistry` + `ServerConfig`: the key `maxTrackedPerChunk` and its accessor only. `docs/emitter-mappings.md`. Test-only `gametest/resources/.../test/glowstone.json` (rate 0.25). Tests: `MappingValidatorTest`, new `ChunkTrackTest`, `EmitterGameTests`. Nothing shipped, `PollutionManager` untouched.

## The key
`maxTrackedPerChunk`, INTEGER, default 2,048, bounds 1 to 98,304, comment in the style of the others. No cross-field rule with `maxEmittersPerChunk`.

## What changed from the approved design, and why
1. **Sorted `long[]` plus a primitive scan heap instead of `LongRBTreeSet`.** My first implementation used the tree set as approved. The first measurement showed the tree insert (about 100-150 ns each, plus node allocation) made a dense section cost far over 0.1 ms: rescan slice p99 870 us for the unfavourable layout and 559 us for a chunk at the cap. The array (binary-search insert for events, at most 2,048 longs to shift) and a primitive max-heap for scans (`ScanHeap`, a rejected offer is one comparison) kept the same rule and the same convergence argument. With the tree set the p99 values were 870 us and 559 us; with the array and heap, before the second limit, 248 us and 186 us.
2. **The second rescan limit is kept (512 kept positions per tick, with a cell cursor inside the section)**, because "without" exceeds 0.1 ms p99 (table below). The finishing tick (sort, merge, one validation read per candidate) ends the slice, so it never adds a walk.
3. The finish step validates each merged candidate once (one block read each), not the two arrays separately.
4. `RESCAN_OFFERS_PER_TICK` is a public constant and a seventh constructor argument so the measurement can run both ways; production uses the constant.

## Red evidence
**Validator** (symbols did not exist): `MappingValidatorTest.java:47: error: cannot find symbol ... class ActiveWhen / method activeWhen() / method isAlwaysActive()`.
**GameTests**, against stubs with the final signatures (always-active entries skipped, the new engine constructor ignoring the cap and log, `trackedIn` of the old structure):
```
astatemappingstillemitsonlywhilelitbesideanalwaysactiveone failed at 7838511, -60, 6976183! lit lamp and glowstone: 2.0
everydiscoverypathkeepsthesamelowestpositionswhatevertheorder failed at 7838535, -60, 6976192! placement events starting at x=7838528: []
analwaysactiveblockemitsitsrateandstopswhenremoved failed at 7838551, -60, 6976192! tracked at placement
achangedcapappliesatthenextscanwithinthebound failed at 7838543, -60, 6976201! default cap tracks all 12
achunkoverthecaptracksthelowestpositionsandemitsforthemonly failed at 7838567, -60, 6976192! the 8 lowest positions, got []
removingatrackedblockfromacappedchunkisrepairedwithinthebound failed at 7838559, -60, 6976210! transient: one block fewer until the repair
thecaplineiswrittenonceperperiodandnotperplacement failed at 7838519, -60, 6976237! the scan wrote the line: []
ablockrankinginsidethecapentersandtheboundaryoneleaves failed at 7838543, -60, 6976255! starts with the 8 lowest
analwaysactiveblockemitsagainafterunloadandreload failed at 7838519, -60, 6976264! tracked while loaded
analwaysactiveblockinanontickingchunkemitsnothinguntilitticks failed at 7838527, -60, 6976264! tracked even though the chunk does not tick
```
**Absence tests**, shown red with mutants after implementing. Mutant 1: the cap removed from `ChunkTrack.offer`. Mutant 2: the block-ticking check removed from the emission pass.
```
everydiscoverypathkeepsthesamelowestpositionswhatevertheorder failed at 4430743, -60, 84350! placement events starting at x=4430736: [4430736, -58, 84341, 4430737, -58,
achangedcapappliesatthenextscanwithinthebound failed at 4430751, -60, 84359! trimming to 4 did not converge within 16 engine ticks
achunkoverthecaptracksthelowestpositionsandemitsforthemonly failed at 4430775, -60, 84350! the 8 lowest positions, got [4430768, -58, 84341, 4430769, -58, 84341, 443077
removingatrackedblockfromacappedchunkisrepairedwithinthebound failed at 4430767, -60, 84368! transient: one block fewer until the repair
ablockrankinginsidethecapentersandtheboundaryoneleaves failed at 4430751, -60, 84413! starts with the 8 lowest
analwaysactiveblockinanontickingchunkemitsnothinguntilitticks failed at 4430735, -60, 84422! a non-ticking chunk emits nothing
aloadedchunkthatdoesnotblocktickemitsnothinguntilitdoes failed at 4430751, -60, 84422! a non-ticking chunk emits nothing
```
**Resume inside a section** (new test, added after the second limit): mutant that restarts the section from cell 0 each tick:
```
arescanlimitedtofewkeptpositionspertickresumesinsideasectionandfindsthesameset failed at -10177535, -60, 3364792! the same 8 lowest after 200 ticks
```
**`ChunkTrackTest`/`ScanHeap`** were written after the first version of the classes (I rewrote the structure after measuring); a mutant that reverses the heap's comparison made 3 of its 7 tests fail (`7 tests completed, 3 failed`). That is not a red-before-green run and I am saying so.
All mutants were reverted.

## Test map
Brief 1: `MappingValidatorTest`. 2: `anAlwaysActiveBlockEmitsItsRateAndStopsWhenRemoved`, `...InANonTickingChunk...`, `...AfterUnloadAndReload`. 3: `aStateMappingStillEmitsOnlyWhileLitBesideAnAlwaysActiveOne` (and all 011 tests unchanged). 4: `aChunkOverTheCapTracksTheLowestPositionsAndEmitsForThemOnly` (exact positions, 8 x 0.25 = 2.0, one captured log line naming the chunk, second chunk with 4 unaffected) and `theCapLineIsWrittenOncePerPeriodAndNotPerPlacement`. 5: `everyDiscoveryPathKeepsTheSameLowestPositionsWhateverTheOrder` (three orders by events, plus the chunk-load scan), `aBlockRankingInsideTheCapEntersAndTheBoundaryOneLeaves`, `removingATrackedBlockFromACappedChunkIsRepairedWithinTheBound`. 6: `aChangedCapAppliesAtTheNextScanWithinTheBound` (through `overrides().set`). Also `aRescanLimitedToFewKeptPositionsPerTick...`. 7: `measureTheTrackedBlockCostAndTheCap`.
All assertions are on the test's own placed positions (`isTracked`, `trackedIn`, `pendingEmission` of its chunk), never on a level-wide count.
Timing tests use private engine instances and count engine ticks to a fixed deadline of 16, no wall clock: the repair converged after **1** engine tick and the cap change after **1** tick down and **1** tick up (the chunk has a single mapped section; the design bound is about 16).

## Measurements (glowstone mapping, 2,500 spreading chunks seeded as in 011, this machine; full run 22:13)
```
PERF 031 [300 in one chunk] rescan slice WITHOUT the limit: mean 31.0 us, p99 55.9 us over 300 busy ticks
PERF 031 [300 in one chunk]: tracked 300; emission pass 50.7 us/interval = 0.00254 ms/tick (0.169 us per tracked block); chunk-load scan 79.9 us (a plain chunk: 0.3 us); rescan slice mean 42.0 us, p99 89.9 us over 300 busy ticks; total 0.0915 ms/tick (diffusion alone 0.0597)
PERF 031 [one chunk at the default cap (2,304 placed, 2,048 kept)] rescan slice WITHOUT the limit: mean 84.5 us, p99 139.1 us over 300 busy ticks
PERF 031 [one chunk at the default cap (2,304 placed, 2,048 kept)]: tracked 2048; emission pass 281.3 us/interval = 0.01406 ms/tick (0.137 us per tracked block); chunk-load scan 79.3 us (a plain chunk: 0.3 us); rescan slice mean 18.2 us, p99 84.7 us over 302 busy ticks; total 0.0881 ms/tick (diffusion alone 0.0597)
PERF 031 [300 in each of 20 chunks (6,000)] rescan slice WITHOUT the limit: mean 27.1 us, p99 39.5 us over 301 busy ticks
PERF 031 [300 in each of 20 chunks (6,000)]: tracked 6000; emission pass 885.8 us/interval = 0.04429 ms/tick (0.148 us per tracked block); chunk-load scan 25.3 us (a plain chunk: 0.3 us); rescan slice mean 28.1 us, p99 46.2 us over 300 busy ticks; total 0.1551 ms/tick (diffusion alone 0.0597)
PERF 031 [unfavourable layout: every cell of 8 sections (32,768 placed)] rescan slice WITHOUT the limit: mean 158.0 us, p99 239.6 us over 301 busy ticks
PERF 031 [unfavourable layout: every cell of 8 sections (32,768 placed)]: tracked 2048; emission pass 282.1 us/interval = 0.01411 ms/tick (0.138 us per tracked block); chunk-load scan 467.4 us (a plain chunk: 0.3 us); rescan slice mean 34.4 us, p99 148.3 us over 303 busy ticks; total 0.0997 ms/tick (diffusion alone 0.0597)
PERF 031 break-even: at 0.148 us per tracked block and 0.0597 ms/tick for diffusion, the whole 0.5 ms/tick budget is used by about 59642 active tracked blocks
```
- **p99 and mean are over at least 200 busy ticks** (300 here; the count is printed on every line and the test asserts it).
- **With versus without the 512 limit** (rescan slice p99): unfavourable layout 148 us with, 240 us without (mean 34 vs 158 us); chunk at the cap 85 vs 139 us (mean 18 vs 85); 300 in one chunk 90 vs 56 us (noise; both are mostly the finishing tick); 20 x 300 46 vs 40 us. So the limit is justified by the two dense cases.
- **The remaining p99 above 0.1 ms** (148 us) is the layout with 32,768 mapped cells in one chunk: the finishing tick (sort of 2,048, merge, one read per candidate) is about 100-140 us on its own. I did not split it over ticks. That layout is pathological, and the mean is 34 us.
- **Cost per tracked block (emission pass): 0.14 to 0.17 us**, measured at 300, 2,048 and 6,000 per chunk (0.169, 0.137, 0.148; 0.138 for the dense one). With diffusion alone at 0.060 ms/tick, **the whole 0.5 ms/tick budget is used by about 59,600 active tracked blocks** (this replaces 011's estimate of about 40,000: the per-block cost fell from 0.19 to 0.15 us and diffusion in this run is lower). Machine noise on these numbers is about +-20%.
- Total ms/tick (diffusion at 2,500 chunks + emission pass + rescan + everything the manager ticks): 0.092 (300), 0.088 (at the cap), 0.155 (6,000 in 20 chunks), 0.100 (dense layout). All well inside 0.5.
- Chunk-load scan: typical 0.3 us (a chunk with nothing mapped); 300 mapped 80 us; at the cap 79 us; 6,000 over 20 chunks 25 us per chunk; worst (every cell of 8 sections mapped) 467 us.
- 011's `measureTheEngineCost` now reports p99 and mean of its rescan (257 busy ticks): mean 64 us, p99 108 us. Its other lines are unchanged.

## Concerns
- The GameTest run got slow: a full `runGameTestServer` now takes about 3 to 4 minutes (the new measurement test places up to 32,768 blocks per scenario and runs each rescan measurement twice, and it forces 24 chunks). It is deterministic, not flaky, but it is heavy for CI; the measurement could move out of the default run if you prefer.
- Rescan p99 for the pathological layout is 148 us, above the 0.1 ms target, caused by the finishing tick (see above). A split of the finish step would fix it at the cost of more state.
- The break-even of about 60,000 active tracked blocks is level-wide, with no level-wide cap (as decided). 20 chunks at the full default cap would be 41,000 (about 0.31 ms/tick including diffusion at today's cost).
- `ChunkTrack` is written without Minecraft types, but sits in `mod.emitter` as the brief's ownership says.
