# 011 report (Brokkr): block emitter engine and furnace mapping

**Status: DONE_WITH_CONCERNS.** Nothing committed. Design as approved in `011-brokkr-design.md` / `011-brokkr-design-review.md`.

## Fresh results
- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache --no-daemon -q`: build passes, **"All 52 required tests passed :)"** (19:27:28).
- `./gradlew runGameTestServer` twice more, no cleaning: **"All 52 required tests passed :)"** at 19:28:49 and 19:30:06 (a fresh world each run, per the build.gradle fix from 010).
- Unit tests: core 58 and mod 144, 0 failed. The architecture checks are inside mod's 144 and pass. New GameTests: 16 in `EmitterGameTests` (the tests below that say "GameTest").
- The `…compat.minecraft` adapter-import check (plan test 5) passes trivially: no Java class lives in that area, the furnace mapping is data only.

## The `lit` finding (condition 3)
`AbstractFurnaceBlock.LIT = BlockStateProperties.LIT`, added in `createBlockStateDefinition`; `Blocks.FURNACE` is a `FurnaceBlock extends AbstractFurnaceBlock`. `AbstractFurnaceBlockEntity.serverTick` sets it with `level.setBlock(pos, state.setValue(LIT, ...), 3)`. Runtime proof: the M1 GameTest puts coal and cobblestone in a furnace, waits for the block entity to flip `lit`, and measures.

## What was built (`mod/.../emitter/`)
`MappingEntry`, `MappingValidator` (plain Java), `IntervalAmounts` (plain Java), `EmitterRules` (resolution against registries, conflicts), `EmitterEngine` (tracker, rescan, emission pass), `EmitterEvents` (Load, Unload, NeighborNotify, ServerStopped), `EmitterRulesLoader` (reload listener plus `TagsUpdatedEvent` resolution), `JsonPlain`. Data: `data/pollutioncraft/pollution_emitters/minecraft/furnace.json` and a `README.md` for the format. Test-only: `gametest/resources/.../pollution_emitters/test/redstone_lamp.json`. `PollutionManager`: one line calling `EmitterEngine.global().tick(level, !scheduler().inFlight())` before `scheduler().tick`, and the `stepNow` Javadoc line.

## Discovery strategy as approved, and what changed
As approved: positions of every mapped block are tracked; the state is read at each interval start, so `lit`, removal and replacement need no event; events only add positions. The engine runs every gated tick; the emission pass only on the interval-start tick; other ticks do only the rescan slice. Rescan budget: up to 8 palette pre-checks and at most 4 section walks per tick, resuming on the next tick. A rule-set swap, including the first, queues every loaded chunk (engine compares the rules reference each tick). Refill every 1,200 ticks. No new config key.
Differences from the design note:
- Rule resolution is a volatile write from `TagsUpdatedEvent`; the engine notices the new reference on the server thread. At startup that event arrives on the thread named `main`, not `Server thread`, which is why nothing is mutated there.
- Unload: `getChunkNow` turns null **before** `ChunkEvent.Unload` is delivered. The emission pass skips such chunks (null check), and the test waits for the handler to drop the positions rather than asserting at once.
- Events deliver on the server thread; all handlers still hand off via `server.execute` if they are not.

## Thread observed for ChunkEvent.Load
Logged once at runtime: `ChunkEvent.Load first delivered on thread 'Server thread' (server thread: true)`.

## How the "loaded but not block-ticking" and "unload" tests were set up (amendment D)
Both use the **real** paths; the injectable `TickCheck` seam exists but is used only to build private engines for the "empty rules" and "rescan" tests, not to avoid a flaky one.
- Non-ticking: a custom 600-tick region ticket at distance 0 (level 33) plus a blocking `level.getChunk`. The test asserts `getChunkNow != null` and `!shouldTickBlocksAt` before relying on it, then upgrades the ticket to distance 1 (level 32) and shows emission starts. It passed in all three consecutive full runs plus the build run. (A first version without the blocking `getChunk` timed out because the test server ticks faster than worldgen; that was a test bug, fixed.)
- Unload/reload: `setChunkForced(false)`, wait for `getChunkNow == null`, then wait for the handler to drop the positions; `setChunkForced(true)` and the load scan finds the saved lamp again, and it emits.

## Red evidence
Plain unit tests (compile error counts as red because the symbols did not exist):
```
MappingValidatorTest.java:35: error: cannot find symbol  symbol: class MappingEntry
IntervalAmountsTest.java:10: error: cannot find symbol  symbol: class IntervalAmounts
MappingValidatorTest.java:36: error: cannot find symbol  symbol: variable MappingValidator
```
GameTests were written against a stub (`EmitterEngine`/`EmitterRules` with the final signatures and no behaviour), so they compiled and failed on assertions. Failing lines from that run (stub, before any engine code):
```
resolutionskipsunknownthingsandexpandstags failed at -1174734, -60, -196240! one log line per bad entry, got []
theshippedfurnacemappingisactiveafteranormalstart failed at -1174686, -60, -196240! furnace is mapped without any rescan wait
alitlampemitsitsrateandisbufferedbeforethedrain failed at -1174726, -60, -196231! pending before the drain: 0.0
aplacementwithoutanupdateisfoundbytherescanaftertheruleschange failed at -1174710, -60, -196222! the rescan found the block
anunlitlampemitsnothing failed at -1174710, -60, -196213! a mapped block is tracked whether or not it is active
alampplacedafterthechunkloadedistrackedatoncebyeveryplacementpath failed at -1174726, -60, -196222! helper.setBlock (flag 3)
themanagertickemitsonceperintervalandattributesthelamp failed at -1174678, -60, -196213! one emission per interval
anenginewithnorulesemitsnothingforalitfurnace failed at -1174734, -60, -196204! control: the shipped rules do emit
lowestresourceidwinsaconflictwithonewarning failed at -1174718, -60, -196204! the lowest id wins, got -1.0
removingthelampstopsemissionfromthenextinterval failed at -1174678, -60, -196204! emitted while present
unloadingforgetsthechunkandreloadingfindsthelampagain failed at -1174734, -60, -196186! tracked while loaded
aburningfurnaceemitsonepuperintervaltimesthemultiplierandacoldonenothing failed at -1174726, -60, -196186! lit furnace: exactly 1 PU
aloadedchunkthatdoesnotblocktickemitsnothinguntilitdoes failed at -1174718, -60, -196186! tracked even though the chunk does not tick
```
- `noPlayerOnlineMeansNoEmission` passes against the stub (a stub emits nothing). Mutation check after implementing: I made `PollutionManager.tick` run the engine before the closed-gate return; it then failed with `noplayeronlinemeansnoemission failed at ...! nothing buffered while the gate is closed`. Mutation reverted.
- `unloadingForgets...` and `aBurningFurnace...` reached real assertions on the stub (`tracked while loaded`, `lit furnace: exactly 1 PU`), so the furnace really burns in the test.
- Test list mapping: brief 1 = `MappingValidatorTest`; 2 = `IntervalAmountsTest`; 3 = `resolutionSkipsUnknownThingsAndExpandsTags`, `lowestResourceIdWins...`, `theShippedFurnaceMappingIsActive...`; 4 = lamp tests (`aLitLamp...`, `theManagerTick...`, `anUnlitLamp...`, `removingTheLamp...`, `aLampPlacedAfter...` covering `helper.setBlock`, `/setblock` and `/fill`), plus `aPlacementWithoutAnUpdateIsFoundByTheRescan...`; 5 = non-ticking and unload tests; 6 = `aBurningFurnace...` (multiplier 1 then 2, cleared in `finally`); 7 = `anEngineWithNoRules...` (with a control engine using the shipped rules); 8 = `noPlayerOnline...`; 9 = `measureTheEngineCost`; 10 = architecture checks green.

## Measurements (`measureTheEngineCost`, one run on this machine; JIT-warmed)
```
PERF 011 total (diffusion @2500 chunks + emission pass @100 emitters + idle rescan): 0.0701 ms/tick
PERF 011 emission pass @100 emitters: 18.5 us/interval = 0.00092 ms/tick
PERF 011 emission pass @1024 emitters: 151.7 us/interval = 0.00758 ms/tick
PERF 011 chunk-load scan: plain chunk 0.4 us, worst case (mapped block in all 24 sections) 516.6 us
PERF 011 rescan slice, worst case: mean 79.8 us, max 93.7 us per tick (192 busy ticks)
PERF 011 NeighborNotifyEvent, non-matching, incl. bus dispatch (event allocated in the loop): 37 ns/event
```
- Total at 100 emitters: 0.070 ms/tick averaged over 30 intervals through `PollutionManager.tick` (all three dimensions), with 2,500 spreading chunks in the field. Limit 0.5.
- Worst-case rescan slice (every queued chunk has a mapped block in every section): mean 79.8 us, **max 93.7 us** per tick, under the 0.1 ms limit but with little margin. It only runs while a rescan is in progress: after a rule change, and for one pass over the loaded chunks every 1,200 ticks. Bounded by 8 palette checks and 4 section walks per tick.
- **Path cache was not needed**: 100 emits cost 18.5 us per interval in total, including `PollutionApiImpl.emit`'s dimension and world lookups (about 0.19 us per emitter). I did not add a cache.
- The 1,000-emitter figure is for information only. Chunk-load scans are unbudgeted (inside vanilla's chunk load); typical 0.4 us, worst 517 us.

## Concerns / notes for the reviewer
- Rescan slice max 93.7 us is close to the 0.1 ms ceiling; the slice size constant (`RESCAN_SECTIONS_PER_TICK = 4`) is the knob.
- No cap on tracked positions per chunk (at most ~98k reads per full chunk; a pathological chunk of mapped blocks would be tracked in full). Only emission is capped, by 010's `maxEmittersPerChunk`.
- Sampling once per interval means a short pulse can alias (accepted in the design review).
- `emit` for an invalid product (`rate x multiplier` overflowing to infinity) is dropped and logged by 010's buffer per calling class, which here is the engine; a rate near 1e308 is the only way to get there.
- The batch names (`emitters_*`) make the long GameTests run one after another so a lit block waiting for a real tick cannot add to another test's `grossEmitted`. All other EmitterGameTests are synchronous and remove their blocks in `finally`.
- Not tested separately: a paused record. The engine is not called for a paused world or dimension (`tick`/`tickDimension` return first) and `emit` refuses anyway (010's test).
- Mapping JSON requires `active_when.value` to be a string; a bare `true` is rejected with a clear message.

## Review fixes (Heimdall)
1. `measureTheEngineCost` no longer counts world-wide tracked blocks. It now checks that each of its own placed lamps (100, then 1,024) is tracked (`allTracked`), so structures generated in the test world cannot change the result.
2. The format note moved from the shipped data folder to `docs/emitter-mappings.md` (the `MappingEntry` Javadoc points to it). `grep -ciE "Invalid path|README" mod/run/gametest/logs/latest.log` now prints **0**; the log still shows `Emitter mappings active: 2 file(s), 2 block(s)`.
3. `RESCAN_SECTIONS_PER_TICK` is 3 (was 4).
- Runs: `./gradlew runGameTestServer --no-daemon -q` three times in a row, each "All 52 required tests passed :)" (19:37:28, 19:38:45, 19:40:05).
- New measurement of the worst-case rescan slice (every queued chunk has a mapped block in every section): **mean 53.4 us, max 64.2 us per tick** over 256 busy ticks (before: mean 79.8, max 93.7 with 4 sections). The same run: total at 100 emitters 0.0954 ms/tick, emission pass 16.8 us/interval, worst chunk-load scan 427 us.
- Heimdall measured 120 us at 4 sections on his machine; at 3 sections that scales to about 90 us, under 0.1 ms. I did not measure on his machine.
