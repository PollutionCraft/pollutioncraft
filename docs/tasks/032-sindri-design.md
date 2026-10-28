# 032 step 0: design note (Sindri)

Status: proposal for Heimdall, then Odin. No code written. Everything below about Minecraft and NeoForge behaviour was read from the patched 21.1.264 sources (NeoForm output `applyNeoforgePatches` plus `neoforge-21.1.264-sources.jar`), not from memory; the file and line are named where it matters. The engine facts are from `mod/emitter/**` as merged on `main` (871ca21) and `docs/emitter-mappings.md`.

## 0. Summary

- **Where "lit" is:** on the entity, as the synched boolean `MinecartFurnace.DATA_ID_FUEL`, refreshed on the server every tick from a private `int fuel`. Neither is readable directly (`DATA_ID_FUEL` is private, `hasFuel()` is protected). The public, vanilla way to read it is `AbstractMinecart#getDefaultDisplayBlockState()`, which for a furnace minecart returns a furnace block state whose `lit` equals `hasFuel()`.
- **Tracking:** an event-fed identity set of furnace minecarts per dimension (`EntityJoinLevelEvent` adds, `EntityLeaveLevelEvent` removes, `isRemoved()` re-checked at read time). No entity scan. Emission reads each cart once, on the interval-start tick, at the cart's block position at that moment.
- **Reuse:** the 011/031 engine's rules loader, multiplier handling and its single `PollutionApi.emit` call are reused. New: an `entity` mapping target, a small `EntityEmitters` class beside `EmitterEngine` (about 120 lines), and two event handlers. No second emit path, no field write.
- **Data or code:** target and rate are data (`{"entity": "minecraft:furnace_minecart", "rate": 0.45}`); the activity test is a few lines of code against public vanilla API. ADR 0015 rung 1 for the mapping, rung 3 (typed NeoForge events plus public vanilla API) for the activity test. No Mixin, no reflection.
- **Rate proposal:** 0.45 PU per interval while lit (placeholder; 81 PU per coal).
- **Cost:** about 50 carts is far inside the budget (estimate below, to be measured by the cost test); a per-level bound of 1,024 tracked carts caps the worst tick.
- **Size:** a small task. No split proposed.

## 1. Where is the "lit" state?

Source (`net/minecraft/world/entity/vehicle/MinecartFurnace.java`, 1.21.1):

| What | Field | Visibility | Who changes it |
| --- | --- | --- | --- |
| Remaining burn time | `private int fuel` | private, server only | `tick()` decrements it once per server tick while `> 0`; `interact(player, hand)` adds 3,600 for coal or charcoal (up to 32,000). Saved in NBT as the short `"Fuel"`, loaded in `readAdditionalSaveData`. |
| "Has fuel" flag | `DATA_ID_FUEL`, a synched `Boolean` entity-data entry | accessor `private static`; getter `hasFuel()` `protected`; setter `setHasFuel` `protected` | `tick()` ends with `setHasFuel(fuel > 0)` on the server side only. Entity data is then synced to clients, which use it for the smoke particles and the display state. |

Consequences:
- **Server authoritative:** the flag is written only under `!level().isClientSide()`. We read it on the server only.
- **Not synced to us:** it is synced to clients, but we have no client code in this task.
- **Delay:** the flag updates at the end of the cart's own `tick()`. Coal added by a player (`interact`) makes it true after the cart's next tick, so a cart can read unlit for up to one tick after refuelling, and lit for none after running out (the flag is cleared in the same tick `fuel` reaches 0). Both are far below the interval.
- **It only changes while the cart ticks.** A cart in an unloaded or non-entity-ticking chunk neither burns fuel nor changes the flag.

**How to read it without Mixin or reflection.** `MinecartFurnace#getDefaultDisplayBlockState()` is `public` and returns `Blocks.FURNACE.defaultBlockState().setValue(FACING, NORTH).setValue(LIT, hasFuel())`. Read `state.getValue(BlockStateProperties.LIT)` (`FurnaceBlock.LIT` is that property). `getDisplayBlockState()` must not be used: it returns the block set by the `DisplayState` NBT (`/data`, custom display) when one is present, and that would let a command turn a cart "lit" or "unlit". `getDefaultDisplayBlockState()` ignores the custom display. Both methods are in `AbstractMinecart` and `MinecartFurnace` in this version; a different Minecraft version would be a pin move that this test (section 7) catches.

## 2. Tracking path

**Facts about entity lifecycle** (read from `ServerLevel`, `PersistentEntitySectionManager`, `Entity`):
- `EntityJoinLevelEvent` is posted in `PersistentEntitySectionManager.addEntity` (fresh spawn, `/summon`, spawn from an item, and entities read from a chunk with `loadedFromDisk = true`) and in `ServerLevel.addPlayer`. Dimension change creates a **new** entity in the target level (`Entity.changeDimension` calls `getType().create(newLevel)`, `restoreFrom` copies the NBT including `Fuel`, then `addDuringTeleport` joins it) and removes the old one with `RemovalReason.CHANGED_DIMENSION`.
- `EntityLeaveLevelEvent` is posted in `ServerLevel.EntityCallbacks.onTrackingEnd`, which runs when an accessible entity is removed: killed, discarded, unloaded with its chunk (`unloadEntity` calls `setRemoved(UNLOADED_TO_CHUNK)`), or changed dimension.
- The join event is **cancellable** and the NeoForge javadoc warns it may fire before the chunk is `FULL`, so a handler must not touch the world. Ours only stores a reference.
- An entity that is added but not yet "accessible" (hidden chunk section) has no tracking start; to stay correct we do not rely on the leave event alone (see below).

**Design.**
- Per dimension, a `ReferenceOpenHashSet<Entity>` (identity) of loaded furnace minecarts, held in a `WeakHashMap<ServerLevel, ...>` like the engine's `LevelState`.
- **Add** on `EntityJoinLevelEvent` for a `ServerLevel` when `event.getEntity().getType() == EntityType.FURNACE_MINECART`, at lowest priority and not receiving cancelled events (so a cancelled join is never tracked). **Remove** on `EntityLeaveLevelEvent`. The check is one reference comparison on every entity that joins any level, so it costs nothing measurable.
- **Self-healing:** at every read the cart is checked with `isRemoved()` and `entity.level() == level`; a cart that fails is dropped from the set. This covers any missed leave event.
- **Thread:** both handlers run on the server thread in normal play; like `EmitterEvents`, a delivery on another thread is handed to `server.execute`.
- **Why not a periodic scan:** `ServerLevel#getEntities(EntityTypeTest, Predicate)` walks every entity section. That is the "scan every entity" the brief rules out. Events plus the self-check give the same set at no per-interval cost proportional to the entity count.
- **Why not per-chunk sets:** a cart moves, so a per-chunk set would need updating on every chunk change (a section-change callback or a per-tick check). A level-wide set read once per interval needs neither.
- **The set is enabled by code, not by the data.** The set holds every furnace minecart whatever the datapack says, and the rate is looked up at read time. A `/reload` that adds, changes or removes the mapping therefore applies at the next interval with no rescan. (The block engine has to rescan because it cannot enumerate blocks; for carts the set already is the enumeration.)

**Attribution of a moving cart.**
- `PollutionManager.tickDimension` calls `EmitterEngine.global().tick(level, intervalStarts)` on a gated tick after the level's own tick (the call site is the `ServerTickEvent.Post` path), once per dimension per tick, with `intervalStarts = !scheduler.inFlight()`.
- On that tick, and only that tick, each tracked cart is read once and emits once at `cart.blockPosition()`. The chunk is `pos >> 4` at that instant: "the chunk it is in at the interval start", exactly as the brief fixes.
- A cart crossing a border between two interval starts is read once per interval, in whichever chunk it stands at that moment: it is neither lost nor double counted, and it never emits twice in one interval because the set holds one reference per entity and is read in one pass.
- Between samples a cart moves up to 4 blocks per second on a rail (the furnace minecart's own `getMaxSpeed` override is 4/20 on land and 3/20 in water, as read from `MinecartFurnace`; the base `AbstractMinecart` is 8/20; `getMaxCartSpeedOnRail` is 0.2), so about 3 to 4 intervals per chunk at the default 20-tick interval; the emission trail follows the rail, which is the intended behaviour.

## 3. Reuse versus separation

**Reused unchanged:**
- the data-pack mechanism: `data/<ns>/pollution_emitters/**.json`, `EmitterRulesLoader`, resolution at `TagsUpdatedEvent`, the one-reference swap in `EmitterEngine.installRules`;
- `IntervalAmounts` and the `emissionMultiplier` (read once per interval; the multiplier is applied in the engine, as for blocks);
- the single emission call `PollutionApi.emit(level, pos, amount)` (010 path): buffered, drained into the interval that starts, server thread only, dropped for a paused dimension, no chunk load, no field write;
- the call site and gating (`tick(level, intervalStarts)`, the "at least one player online" gate, the paused-record behaviour).

**New:**
1. `MappingEntry.TargetKind.ENTITY` and the `entity` key in `MappingValidator` (exactly one of `block`, `tag`, `entity`; an `entity` entry may not carry `active_when`: the activity is built in).
2. `EmitterRules`: an entity rate map keyed by `EntityType` plus `entityRate(EntityType)` (−1 when not mapped). `resolve` takes the entity registry (`BuiltInRegistries.ENTITY_TYPE`), skips and logs an unknown entity id, and skips and logs an entity that has no built-in activity test.
3. `EntityActivity` (new, tiny): the built-in activity tests, one entry for now: furnace minecart, `AbstractMinecart#getDefaultDisplayBlockState().getValue(LIT)`. It is also the list of entity types the tracker keeps.
4. `EntityEmitters` (new, `mod.emitter`): the per-level set, add/remove, and `emitAll(level, rules, amounts, api)`.
5. `EmitterEvents`: two more `@SubscribeEvent` methods (join, leave) and the clear on server stop.
6. `EmitterEngine.tick`: after `emitAll(...)`, call the entity pass; `amounts.startInterval()` moves up so both passes use one multiplier read per interval.
7. `docs/emitter-mappings.md`: the `entity` target, its fixed activity rule, and the new shipped file; plus `data/pollutioncraft/pollution_emitters/minecraft/furnace_minecart.json`.

Not touched: `PollutionApi`, `PollutionApiImpl`, `ChunkTrack` (the per-chunk block cap and ranking stay block-only), the scheduler, the field. The block cap `maxTrackedPerChunk` does not apply to entities; section 6 gives the entity bound.

## 4. Data or code?

**Decision: the target and the rate are data, the activity test is code, for a fixed vanilla list.**

```json
{ "entity": "minecraft:furnace_minecart", "rate": 0.45 }
```

- No `active_when` for entities. For a block, `active_when` names a property of the block state, which `resolve` can verify against the block's state definition at load. An entity type has no state definition we can inspect without an instance (`EntityType` carries no class or default display block, and no level exists at `TagsUpdatedEvent`), so the same field for entities could only be checked lazily, on first use, with the failure showing up as a log line during play. I judge a closed list in code better: `entity` ids not in `EntityActivity` are rejected at load with one log line, exactly like an unknown block.
- Alternative considered, rejected: `{"entity": ..., "active_when": {"property": "lit", "value": "true"}}` evaluated against the cart's display block state. It is less code and would need no new class, but the property check is lazy (above), `getDefaultDisplayBlockState` is an `AbstractMinecart` detail that other entity types do not share, and it invites mapping entities this task does not cover. Revisit it if a second minecart-like entity is ever mapped.
- **ADR 0015 rung:** the mapping is rung 1 (data). The activity test is the "other NeoForge-typed signals" rung (rung 3: `EntityJoinLevelEvent`/`EntityLeaveLevelEvent` plus public vanilla API). Mixin (rung 2) and reflection (rung 4) are not used and not needed: every member read (`getType`, `isRemoved`, `level`, `blockPosition`, `getDefaultDisplayBlockState`) is public. The target is vanilla, so there is no version gate beyond the pinned Minecraft version.
- **Architecture rules:** all new code is in `mod.emitter` (vanilla target, no `compat`), so the four ArchUnit rules are unaffected.

## 5. Rate

The scale: a lit furnace is 1 PU per interval (default interval 20 ticks).

Per fuel item, from the sources:
- a furnace burns coal for 1,600 ticks, i.e. 80 intervals, 80 PU per coal at rate 1.0;
- a furnace minecart burns one coal for 3,600 ticks (`interact` adds 3,600), 180 intervals.

Setting the cart's rate so a coal burns about as much pollution as in a furnace gives 80/180 = 0.44. **Proposal (approved as 0.45): 0.45 PU per interval while lit**, which is 81 PU per coal, equal to a furnace (user rule: pollution follows fuel burn), a round number in the same family as the other placeholders (campfire 0.25). The cart is a mobile source that burns without producing anything, so a slightly higher per-coal figure than a furnace is defensible; this is a placeholder for the P4 playtest and is changed by replacing the data file. The `emissionMultiplier` scales it like every other rate.

## 6. Cost and bounds

Per interval, on the interval-start tick only:
- for each tracked cart: `isRemoved()`, `level()` compare, `isPositionEntityTicking(pos)` (a position-to-chunk-key and two lookups in the entity manager and the distance manager), `getDefaultDisplayBlockState()` (two cached `setValue` lookups, one synched-data read), and, if lit, one rule lookup, one multiply and one `emit` (a buffer add). I estimate about 1 µs per cart, no allocation per cart on the hot path.
- **50 lit moving carts: about 0.05 ms once per interval**, which is about 0.0025 ms/tick averaged, against the 0.5 ms/tick budget. The cost test (section 8, test 4) measures this rather than trusting the estimate.
- Other ticks cost nothing: the set is not touched. The join/leave handlers add one reference comparison per entity join or leave, in any level.

What is bounded:
- **Work per interval:** the engine tracks at most `MAX_TRACKED_CARTS_PER_LEVEL = 1,024` carts per dimension (a constant, no new config key; 1,024 carts is about 1 ms on the one tick per interval that reads them). Over the bound, the carts with the lowest UUID (`UUID` compared as two longs) are read, the rest emit nothing, and one `WARN` line per level per minute says so. Selection is only done when the set exceeds the bound (a bounded partial selection, not a sort of the whole set), and is deterministic across restarts. This mirrors the block cap's "lowest position wins".
- **Memory:** the set holds references to entities that are loaded anyway, and shrinks on leave and on the `isRemoved()` check; it is cleared on server stop. It cannot outlive the entities except for at most one interval for an entity whose leave event was missed.
- **No chunk is loaded or generated:** `emit` buffers; we only read entities that are already loaded.

## 7. Edge cases

| Case | Behaviour | Why |
| --- | --- | --- |
| **Out of fuel** | Emits nothing. | `tick()` clears the flag in the same tick `fuel` hits 0; the read sees `lit=false`. |
| **Fuel added while stopped** | Emits from the first interval start after the cart's next tick. | `interact` adds fuel; the flag flips on the next `tick()` (at most one tick). A cart in a non-ticking chunk cannot be fuelled by a player anyway. |
| **Carried or pushed by another entity** (pushed by a player or minecart, riding a boat, moved by water or a piston) | Emits at the cart's own block position at the interval start. | We read `blockPosition()`, not any velocity or owner. A passenger cart still ticks (`rideTick`), so it still burns fuel. |
| **Unloaded chunk** | Emits nothing; the cart leaves the set; it is back in the set on reload (`loadedFromDisk = true`) and resumes. | Leave event on `UNLOADED_TO_CHUNK`; the stored `Fuel` is not burning meanwhile, like blocks. Rejoin on reload is automatic. |
| **Loaded but not entity-ticking chunk** | Emits nothing; stays in the set. | The read requires `ServerLevel#isPositionEntityTicking`, because a cart there does not tick (no fuel burnt, flag frozen) and would otherwise pollute for free forever. Blocks use `shouldTickBlocksAt` (block ticking); entities need the stricter entity-ticking test, so the two engines are consistent about "the thing is actually running". Spike 003b observed a freshly forced chunk reporting `entityTicking=false` for dozens of ticks, so this case is real and is tested. |
| **Changed dimension** | Old object leaves, new object joins in the target level with the same `Fuel`. | Section 2. |
| **Removed (killed, broken, `/kill`)** | Leaves the set at once; the `isRemoved()` check is the backstop. | `onTrackingEnd`. |
| **Custom display block (`DisplayState` NBT)** | Ignored. | We read `getDefaultDisplayBlockState()`. |
| **A train of carts** | Each lit cart emits once. | One set entry per entity. |
| **No player online** | Nothing emits. | Same gate as every emitter (`tick` is called only on gated ticks). |
| **Paused (unreadable) dimension** | `emit` drops and logs once. | Existing 010 behaviour; no entity-side handling. |
| **A cart that exists for less than one interval** | May emit zero times. | Sampled once at the interval start, like a block that is lit for a moment between samples. |
| **Joined cancelled by another mod** | Not tracked. | Handler skips cancelled events. |
| **Server restart** | Carts rejoin as their chunks load; they emit from the next interval start in a ticking chunk. | `loadedFromDisk` joins. |

## 8. Tests (step 1 list, each with a quoted red before its code)

**Unit (`mod:test`, no Minecraft):**
1. `MappingValidator`: an `entity` entry with `rate` is valid; `entity` with `block` or `tag` is refused; `entity` with `active_when` is refused; a bad rate is refused. Red: the validator rejects `entity` today (needs exactly one of `block`/`tag`).
2. Over-bound selection: the lowest-UUID `k` of `n` keys, deterministic, `n <= bound` selects all. A plain helper.

**GameTests (`EntityEmitterGameTests`).** Rules for all of them (Heimdall's review): pollution assertions run only in chunks that hold no tracked mapped block (the 029 precondition; the GameTest world generates villages with furnaces and campfires, so each test picks a chunk and checks that precondition first); tests that unload, reload or need a non-ticking chunk wait for entity ticking (`isPositionEntityTicking`) instead of a fixed tick count, and check the not-ticking precondition before asserting that nothing emits.
3. A furnace minecart with `Fuel` (set through `load(tag)`) raises its chunk's pollution by its rate in one interval; the same one without fuel adds none; `emissionMultiplier` scales it.
4. Border: a lit cart placed in chunk A at the interval start emits into A; moved to chunk B before the next interval start it emits into B; across two intervals exactly one emission each (no loss, no double), asserted on the buffer/field.
5. Remove and unload: a removed cart (`discard`) stops emitting; a cart in a chunk that is unloaded stops and, after the chunk is reloaded, emits again (after waiting until the chunk is entity-ticking, as in 003b). A cart in a loaded non-ticking chunk emits nothing.
6. Dimension change: the cart in the new level emits there, the old level gets nothing.
7. `/reload` or `installRules` with a changed rate applies at the next interval with no rescan.
8. Cost and bound: 50 lit carts spread over several chunks, with the interval-start cost measured by `nanoTime` and **reported, not asserted** (wall time is flaky across machines; the budget is judged from the report). The only assertion is the cap: with 1,100 carts exactly 1,024 emit and exactly one warning is captured.
9. The vanilla accessor guard: a cart's `getDefaultDisplayBlockState()` has `LIT` and tracks `Fuel`. This is the check that fails loudly if a Minecraft bump changes it.

Process: each test is run once before its code and the failing line quoted in the report, as in 009b. Existing `EmitterGameTests` and `VanillaEmitterGameTests` and the architecture checks stay green.

## 9. Size and split

New code is one small class (`EntityEmitters`, about 120 lines), one tiny class (`EntityActivity`), about 40 lines changed in `MappingEntry`/`MappingValidator`/`EmitterRules`, about 20 in `EmitterEvents`/`EmitterEngine`, one data file and one doc section, plus the tests above. That is one small task. **No split proposed.**

## 10. Decisions recorded from review (Heimdall)

- Entity-ticking test (`isPositionEntityTicking`) for carts: **approved**.
- The bound of 1,024 tracked carts per dimension is a **constant**, documented as a known limitation: the lowest UUID wins and one warning per dimension per minute is logged.
- Rate **0.45 PU per interval** while lit (changed from 0.5 by the user rule: pollution follows fuel burn, about 80 PU per coal as in a furnace): placeholder.
- **No change to the block engine:** its cap and rescan stay untouched, and the report shows `EmitterGameTests` and `VanillaEmitterGameTests` passing unedited. (`EmitterEngine.tick` only gains a call to the entity pass and the earlier `startInterval`; section 3, item 6.)
- Cost test reports figures; the only assertion is the cap (test 8).

## 11. Open points that were raised (now answered above)

1. **Entity-ticking versus block-ticking:** I require `isPositionEntityTicking` for carts (section 7). If Heimdall prefers one rule for all emitters, the alternative is `shouldTickBlocksAt`, which would let a frozen cart in a block-ticking-only chunk pollute indefinitely. I recommend the stricter test.
2. **Bound as a constant (1,024) versus a config key.** A key would be one declaration in `ConfigRegistry` and `/pollution tune` picks it up for free; I left it a constant to keep the task small, since no realistic pack runs near it.
3. **Rate 0.5 versus 1.0.** 0.5 matches pollution per coal; 1.0 would make a cart equal to a furnace per tick. The playtest decides.
4. **Data shape (section 4):** closed list in code with a data rate, against `active_when` on the display state. I recommend the closed list; the alternative is recorded.
5. **Dominant source:** the carts' positions will show up as `dominantSource` of a chunk (a rail block position), possibly stale a few seconds later. I take that as acceptable for a moving source; say if the overlay should hide it.
