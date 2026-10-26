# 011 step 0: findings and discovery proposal (Brokkr)

Status: proposal for Heimdall. No engine code written. Sources read: NeoForge 21.1.264 (`neoforge-21.1.264-sources.jar` plus the patched Minecraft sources from the NeoForm cache). Everything marked "verified" below was read in those sources; "to verify" items are things only a running GameTest can prove, and each has a test in the plan.

The brief asked for `011-brokkr-step0.md`; your message named this file, so it is `011-brokkr-design.md`.

## 1. The `lit` finding (condition 3): verified
- `AbstractFurnaceBlock.java:29`: `public static final BooleanProperty LIT = BlockStateProperties.LIT;`, registered in `createBlockStateDefinition` (`builder.add(FACING, LIT)`, line 116), default `LIT=false` (line 33).
- `Blocks.FURNACE` is a `FurnaceBlock`, which `extends AbstractFurnaceBlock`. So `minecraft:furnace` has `lit`, serialized values `"true"`/`"false"`. (Same for `blast_furnace` and `smoker`; out of scope, task 029.)
- The block entity sets it: `AbstractFurnaceBlockEntity.serverTick` (line ~336): `state.setValue(LIT, blockEntity.isLit())` then `level.setBlock(pos, state, 3)`. Flag 3 includes the neighbour-update bit (1).
- Furnace block entities only tick where `Level.tickBlockEntities` finds `shouldTickBlocksAt(pos)` true (`Level.java:598`), so a furnace in a loaded, non-block-ticking chunk neither burns nor changes `lit`. The brief's rule "emit only where blocks tick" matches vanilla.

## 2. Proposed strategy in one paragraph
The tracker records **positions of blocks that have any rule** (not only the active ones). Each interval the engine reads the **current** block state at each tracked position and emits if it matches `active_when`. So `lit` toggling, removal and replacement need **no event at all**: they are seen when the state is read, at zero latency, and a position whose block is no longer mapped is dropped at that read. Events are only needed to **add** positions: a chunk-load scan, `NeighborNotifyEvent` for placements, and a bounded background rescan as a safety net (which also serves `/reload`).

## 3. Hooks, case by case

| Case | Mechanism | Verified how | Latency |
|---|---|---|---|
| Chunk load, including worldgen | `ChunkEvent.Load` (game bus). Scan the event's `LevelChunk` with `LevelChunkSection.maybeHas(state -> rules.mapsBlock(state))`; only sections that may hold a mapped state are walked (4096 reads). | `ChunkStatusTasks` posts `ChunkEvent.Load` on the main-thread mailbox at promotion to FULL, after the features stage, with the chunk already registered. `maybeHas` is `LevelChunkSection.java:175`. NeoForge's note says to avoid level lookups inside this event (deadlock): the scan reads only the event's chunk. | 0 (the chunk is scanned before it ticks) |
| Placed by a player, `/setblock`, `/fill`, a piston, GameTest `helper.setBlock`, structure placement | `BlockEvent.NeighborNotifyEvent` (game bus). Handler: `level instanceof ServerLevel`, `rules.mapsBlock(event.getState())` -> add `pos.asLong()` to the chunk's set. | The event is **not** fired by `setBlock` itself. It is fired by `ServerLevel.updateNeighborsAt` / `updateNeighborsAtExceptFromFacing`, which `Level.markAndNotifyBlock` reaches via `blockUpdated` **only when the update flag (1) is set**. `/setblock` places with flags 2 but then calls `serverlevel.blockUpdated(...)` itself (`SetBlockCommand:104`); `/fill` does the same (`FillCommand:186`); `helper.setBlock` and player placement use flag 3. The event carries the state at the position (`getBlockState(pos)`). | 0 (same tick). First emission is at the next interval start, so at most 1 interval after placement. |
| Removed (break, explosion, command, replaced) | Not needed: dropped at the next state read | Read of `chunk.getBlockState(pos)` each interval | 0 |
| `lit` true/false | Not needed: the state is read each interval | Same | 0 |
| `/reload` changing the mapping | A reload listener (`AddReloadListenerEvent`) parses the JSON files; `TagsUpdatedEvent` (cause `SERVER_DATA_LOAD`) resolves blocks and tags into the new rule set, because tags are only bound to the registries after the listeners finish (`ReloadableServerResources.updateRegistryTags`, called from `MinecraftServer` reload, posts the event at line 143). The new rule set is swapped in as one volatile reference, then **all loaded chunks are queued for the rescan** (below). | Source read; this is the pattern NeoForge's own `VillagerTradingManager` uses | rescan time of the queue (see 4); removed or retargeted blocks are right at the next read |
| Chunk unload | `ChunkEvent.Unload`: drop the chunk's tracked set and its entry in the loaded-chunk set. | `ChunkMap:541` posts it on the main thread | 0 |
| Server stop / dimension reuse | `ServerStoppedEvent` clears all engine state (the engine keeps per-level state in a map keyed by `ServerLevel`, nothing static survives an integrated-server restart). | Standard | n/a |

### Rescan safety net (the brief's "only if a placement path is not covered")
Not needed for any vanilla path I found. I still propose it because (a) `/reload` needs a rescan queue anyway, and (b) other mods' fast-placement (`setBlock(pos, state, 2)` without a following `blockUpdated`) are invisible to the event. It is one queue: loaded chunk keys are enqueued on `/reload` (all) and, when the queue is empty, refilled once per 1200 ticks (60 s). Each gated tick the engine pops up to a small fixed budget (proposal: 8 chunks) and rescans them with the same section scan. No config key. **Decision for you:** keep the 60-second refill, or drop it and keep the queue for `/reload` only.

## 4. Costs and bounds
Measured numbers come in the implementation phase; these are the bounds and my expected order of magnitude.

| When | Work | Bound |
|---|---|---|
| Chunk load / rescan of one chunk | one `maybeHas` per section (palette walk, small); plus 4096 reads for each section that may hold a mapped state | O(sections x palette) typically a few microseconds; worst case (every section holds a mapped block) about 100k reads, a few hundred microseconds, and only for such a chunk |
| `NeighborNotifyEvent` (fires for every neighbour update in the world: redstone, crops, fluids) | one `Block -> rules` identity lookup on the new state; a hit adds one long to a hash set; a miss costs the lookup only | O(1), no allocation on a miss. This is the per-event cost the budget has to absorb; it will be measured with a synthetic burst |
| Per tick | rescan queue: at most 8 chunks. Nothing else | bounded, about 8 x chunk-scan |
| Per interval (at interval start) | for each tracked chunk with `shouldTickBlocksAt(chunkKey)` true and `getChunkNow` non-null: for each tracked position, read the state, compare the rule, call `PollutionApi.emit(level, pos, rate x multiplier)` | O(tracked positions) reads + O(active) `emit` calls. Expected: 100 emitters is 100 emits; `emit` currently rebuilds a `Path` per call (see the path-cache condition), to be measured |
| Memory | one long per tracked position, one long per loaded chunk | O(tracked + loaded chunks); no per-chunk cap (a chunk can hold at most ~98k positions). No `LevelChunk` references are kept, so an unloaded chunk cannot be pinned |

The multiplier is read once per interval, at the start of the engine's interval pass.

## 5. Discovery latency, summary
- Chunk load, event placement paths: 0 ticks to be tracked; first emission at the next interval start, at most 1 interval later.
- State change (`lit`, removal): exact at each interval start.
- Placement that bypasses both the update flag and `blockUpdated`: up to the rescan cycle (about 60 s plus queue time at 8 chunks per tick), or never if you drop the refill.
- `/reload`: rule swap is immediate; newly mapped blocks already in loaded chunks are found by the rescan (1,000 loaded chunks at 8 per tick = 125 ticks, about 6 s).

## 6. What this does not catch, and why that is acceptable
1. **Sampling at interval start.** A furnace is sampled once per interval, so one that is lit for part of an interval counts fully or not at all depending on the moment of sampling. The rate is "per interval" by definition (glossary: 1 PU = one lit furnace's output for one interval), so over many intervals the mean is right for continuous burning; short pulses alias. Alternative (sampling more often) is out of scope.
2. **Bypassing placements** as above, bounded by the rescan.
3. **A rule whose block has a very large number of states** is fine: only blocks, not states, are indexed; the state is compared on read.
4. **Chunks that are loaded but not block-ticking** are skipped on purpose; their tracked positions stay tracked, so emission resumes when ticking resumes.

## 7. Changes I need approved
1. The brief says `PollutionManager.tickDimension` calls the engine on the tick an interval starts. The rescan budget needs a small amount of work on **every** gated tick, so I would call `engine.tick(level, intervalStarts)` each gated tick, where `intervalStarts = !scheduler().inFlight()`, with the emission pass only when `intervalStarts`. Alternative: do the rescan only at interval starts with a larger budget (about 160 chunks per interval, same throughput, a spike of roughly 0.5 ms once a second). I recommend the per-tick call. Either way it is one line in `PollutionManager`.
2. Keep or drop the 60 s rescan refill (section 3).
3. No Mixin, no reflection, no access transformer. Everything above uses public API: `ChunkEvent.Load/Unload`, `BlockEvent.NeighborNotifyEvent`, `AddReloadListenerEvent`, `TagsUpdatedEvent`, `ServerStoppedEvent`, `LevelChunkSection.maybeHas`, `ServerChunkCache.getChunkNow`, `Level.shouldTickBlocksAt`.

## 8. Test setup for step 5 (unloaded and non-ticking), to verify in the GameTest phase
- **Unloaded:** track a position, then let the chunk go (a chunk with no ticket unloads on a later tick; in the test I will call the engine's unload handler path through a real forced-chunk release `level.setChunkForced(x, z, false)` and wait ticks) and assert nothing is emitted and `getChunkNow` is null. If the chunk cannot be made to unload deterministically within a test, I will drive the `ChunkEvent.Unload` handler directly and assert the tracked set is emptied, and say so.
- **Loaded but not block-ticking:** `TicketType.UNKNOWN` region ticket with `distance 0` gives ticket level 33 (`FULL`), which loads the chunk (`ChunkEvent.Load` fires) but `ChunkLevel.isBlockTicking(33)` is false (`DistanceManager.addRegionTicket` computes `33 - distance`; `TickingTracker` drops levels >= 33). Distance 1 gives level 32, which is block-ticking. The test asserts `level.shouldTickBlocksAt(...)` is false before relying on it. Risk: tickets are processed in the chunk-system tick and `UNKNOWN` expires after 1 tick, so the test will need `GameTestHelper` delayed steps. This is the part most likely to need adjusting.
- Furnace tests use forced chunks (`setChunkForced`), which tick.

## 9. Test plan notes (for the red lines later)
- Test 1 (mapping validation) and test 2 (arithmetic) are plain-Java classes in `mod/emitter`; the red for each is the symbol missing, which the brief allows only when the symbol under test does not exist yet.
- Each new test gets its failing run quoted in the report before its implementation exists.
