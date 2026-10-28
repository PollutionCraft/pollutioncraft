# 032 (Sindri): report

**Status: DONE, ready for Heimdall's review.** Nothing committed. Design: `032-sindri-design.md` (approved; rate changed to 0.45 by the user's rule that pollution follows fuel burn, about 80 PU per coal as in a furnace).

## What was built

- **Data:** `data/pollutioncraft/pollution_emitters/minecraft/furnace_minecart.json` = `{"entity": "minecraft:furnace_minecart", "rate": 0.45}`.
- **Mapping format** (`MappingEntry`, `MappingValidator`): a third target `entity`; exactly one of `block`/`tag`/`entity`; an `entity` entry may not carry `active_when`.
- **Rules** (`EmitterRules`): entity rates keyed by `EntityType`, `entityRate(type)` (−1 when unmapped), `hasEntityRules()`; the new `resolve(entries, blocks, entityTypes, log)`. The old three-argument `resolve` is kept and delegates, so existing callers and tests are unchanged. An unknown entity id, a type with no built-in activity test, and a duplicate (lowest id wins) are skipped with one log line.
- **`EntityActivity`** (new): the closed list of supported entity types and their activity test. Furnace minecart: `getDefaultDisplayBlockState().getValue(LIT)` (public vanilla API; `getDisplayBlockState()` is avoided because `/data` can override it).
- **`EntityEmitters`** (new): per-dimension identity set fed by the join and leave events; once per interval, on the interval-start tick, it emits once per working cart at `blockPosition()` through `PollutionApi.emit`. Skips removed carts and carts whose level changed, carts where `ServerLevel#isPositionEntityTicking` is false, and unlit carts. Bound: 1,024 carts per dimension (constant), lowest UUID wins, one warning per dimension per 1,200 ticks.
- **`BoundedLowest`** (new, plain): the deterministic lowest-k selection.
- **`EmitterEngine`**: the entity pass is called after the block pass on the interval-start tick; `amounts.startInterval()` moved up one level so both passes use one multiplier read. Cap and rescan code for blocks untouched.
- **`EmitterEvents`**: `EntityJoinLevelEvent` (lowest priority, cancelled joins not received) and `EntityLeaveLevelEvent`, with the same server-thread handoff as the chunk events.
- **`docs/emitter-mappings.md`**: the `entity` target, the furnace minecart section, the shipped-file table (eight files), and the **known limitation: at most 1,024 carts per dimension** (lowest UUIDs emit, one warning per dimension per minute).

## Red evidence (failing line quoted before each part was implemented)

Unit tests (`mod:test`), run before the symbols existed:
- `BoundedLowestTest.java:29: error: cannot find symbol` (class `BoundedLowest` did not exist).
- `MappingValidatorTest.java:142: error: cannot find symbol` (`TargetKind.ENTITY` did not exist).

GameTests, first a compile error before `entityRate`, `TargetKind.ENTITY` and the four-argument `resolve` existed:
- `EntityEmitterGameTests.java:58: error: cannot find symbol` (`entityRate`), `:331: error: method resolve in class EmitterRules cannot be applied to given types`, `:333: error: cannot find symbol` (`ENTITY`).

Then, with the data, rules and validator in place but no tracker, the behavioural red (`runGameTestServer`, "6 required tests failed"):
```
aLitFurnaceMinecartEmitsItsMappedRateAndAnUnlitOneNothing: exactly the mapped rate 0.45, got 0.0
aLitCartEmitsOncePerIntervalIntoTheChunkItIsInAtTheIntervalStart: first interval lands in the start chunk, got 0.0
aRemovedCartStopsEmitting: emits while it exists, got 0.0
aCartInAnUnloadedChunkEmitsNothingAndResumesAfterTheReload: emits again after the reload, got 0.0
aCartThatChangesDimensionEmitsInTheNewOneOnly: the nether chunk got the rate, got 0.0
installingChangedRulesAppliesAtTheNextInterval: the new rate applies at the next interval, got 0.0
```
Honest exceptions:
- `theDefaultDisplayStateOfAFurnaceMinecartFollowsItsFuel` passed before the tracker existed: it tests only vanilla behaviour (the accessor the design rests on), so it has no red; it is the guard that fails if a Minecraft bump changes the display state.
- The four tests in `EntityEmitterBoundGameTests` were written after `EntityEmitters`, so they have no natural red. I show a mutation instead: with the cap ignored, the tick check removed and the removal pruning removed (one run, all three together) they fail as follows, then the file was restored (`cmp` against the backup) and the suite re-run green:
```
overTheBoundOnlyTheLowestUuidsEmitAndOneWarningIsLogged: exactly 1024 carts emit, got 1100
aCartWhereEntitiesDoNotTickEmitsNothingUntilTheyDo: nothing emits where entities do not tick
anUnlitOrRemovedCartEmitsNothingAndARemovedOneIsDropped: neither emits, got [BlockPos{x=2, y=100, z=0}]
```
(the fourth, the cost report, asserts nothing by design.)

## Final run

(Superseded by the review round below: 102 tests.) First run: `All 100 required tests passed :)`; unit tests 266, 0 failures.

`EmitterGameTests` and `VanillaEmitterGameTests` are not edited (`git status` lists neither) and pass in that run.

## Tests added

- Unit: 4 in `MappingValidatorTest` (entity entry valid; entity with block or tag refused; entity with `active_when` refused; blank id, negative or missing rate refused), 4 in `BoundedLowestTest`.
- GameTests (`EntityEmitterGameTests`, own batch `entity_emitter`, each on chunks with no tracked mapped block; waits use `isPositionEntityTicking`, never a fixed tick count): lit versus unlit and the multiplier; border attribution (one emission per interval into the chunk it is in); removed cart; unload and reload (precondition `!level.hasChunk`, then waits for the reloaded cart to be entity-ticking and lit); dimension change; changed rules apply next interval; display-state guard.
- GameTests (`EntityEmitterBoundGameTests`, recording API, nothing touches the field): over the bound exactly 1,024 carts emit, they are the lowest UUIDs, and exactly one warning is logged (a second interval emits for the same number and logs nothing more); not-ticking seam; unlit and removed carts; a cost report.

## Cost (reported, not asserted)

From the cost test, `nanoTime` over 500 reads, tick check stubbed true (so it excludes the real `isPositionEntityTicking` lookup):
```
COST carts=50   perIntervalRead=4.7 us (0.09 us per cart)    (second run 6.5 us)
COST carts=1024 perIntervalRead=49.9 us (0.05 us per cart)   (second run 43.9 us)
```
That is well under the design estimate of 1 µs per cart, but the real chunk-ticking lookup adds to it. It happens once per interval, so the average per tick is a small fraction of the 0.5 ms budget. A real-pack measurement belongs to P4.

## Deviations and notes

- `EntityEmitters.emitAll` takes a `DoubleUnaryOperator` for the rate-to-amount step (the engine passes `IntervalAmounts::amountFor`) because `IntervalAmounts` is package-private and the bound tests live in the GameTest package.
- The "non-ticking" case is tested through the `TickCheck` seam, because a loaded chunk where entities do not tick cannot hold a cart that is already lit and would stay so (the flag only changes while the cart ticks). The real predicate is exercised by the unload and reload test, which waits for entity ticking.
- The cart in the bound tests is never added to a level; one `cart.tick()` gives it the "has fuel" flag, and gravity is switched off.
- The join event is handled at lowest priority; cancelled events are not received. Not tested with a cancelling handler.
- The dominant source of a chunk may now be a cart's position (a rail block); the design note flagged this and asked for no change.

## Review round 1 (Heimdall's R1 and R2)

**R1: the real non-ticking check.** New GameTest `aLitCartInAChunkThatStoppedEntityTickingEmitsNothingUntilItTicksAgain`:
- a lit cart in a forced (entity-ticking) chunk; the forced ticket is dropped and a region ticket of level 33 is added (`TicketType`, as 011's tests hold chunks), so the chunk stays loaded and the cart stays in it with its lit flag frozen;
- it waits until the preconditions hold (`level.hasChunk`, `!isPositionEntityTicking`, cart not removed and still lit), then checks that an interval adds 0 to the chunk;
- then raises the ticket to level 31 (entity-ticking again), waits for `isPositionEntityTicking`, and checks that the same cart emits the mapped rate.

Red with Heimdall's mutant (the engine's check replaced by `(level, pos) -> true`, restored afterwards and compared with `cmp`):
```
aLitCartInAChunkThatStoppedEntityTickingEmitsNothingUntilItTicksAgain failed: a lit cart where entities do not tick emits nothing, got 0.45
```
It was deterministic on the first attempt. In that mutant run nine block-emitter tests also failed, because the failing new test left its lit cart emitting into later tests' exact `grossEmitted` checks. That was a hygiene bug in my test, not a product problem: the test now removes its cart and tickets when its assertion fails (`cleanup` in a catch), and the green run below has no such failures.

**R2: cost through the production tracker with the real check.** New GameTest `costOfFiftyLitCartsThroughTheEngineIsReported` (reports, asserts nothing about time; it asserts only that the engine tracks at least 50 carts): 50 real, lit furnace minecarts in 5 entity-ticking chunks, then `EmitterEngine.global().tick(level, true)` (the production interval-start tick, real `isPositionEntityTicking`, real `PollutionApi.emit`) timed over 400 calls after 100 warm-up calls, with the carts and without them:
```
COST engine interval-start tick, 50 lit carts, real tick check: with=60.2 us without=17.0 us entity pass=43.2 us; not asserted
```
So about 43 µs per interval for 50 carts through the production path (about 0.9 µs per cart, matching the design estimate of about 1 µs; the earlier stubbed figures of 3.5 to 6.5 µs for 50 carts leave out the real check and the real emit). Once per interval, so about 0.002 ms per tick on average against the 0.5 ms budget. Same-run stubbed figures: 50 carts 3.5 µs, 1,024 carts 54.9 µs.

**Final run after the round:** `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache --no-daemon -q`: exit 0; `All 102 required tests passed :)`; unit tests 266, 0 failures. `EmitterGameTests` and `VanillaEmitterGameTests` still unedited.
