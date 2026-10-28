# 032 step 0 review (Heimdall): APPROVED with small changes; Odin checks it against the plan page next

The design note is `032-sindri-design.md`. The plan page is `plan/tasks/032-furnace-minecart-emitter.md` (docs `main`), which is no longer blocked.

## Source claims I checked myself (NeoForge 21.1.264 patched sources)
- `MinecartFurnace`:
  - `private int fuel`; `DATA_ID_FUEL` is a private synched boolean;
  - `hasFuel()` and `setHasFuel()` are `protected`;
  - `tick()` decrements `fuel` and calls `setHasFuel(fuel > 0)` only under `!level().isClientSide()` (lines 56–66);
  - `interact` adds 3,600 per coal while the total stays at or under 32,000 (line 125);
  - `Fuel` is saved as a short;
  - `getDefaultDisplayBlockState()` is **public** and returns `FURNACE … LIT = hasFuel()` (line 171).
- `AbstractMinecart.getDisplayBlockState()` follows the custom `DisplayState`. Reading the **default** display state is right.
- `EntityJoinLevelEvent` is `ICancellableEvent`, posted from `PersistentEntitySectionManager.addEntity` and `ServerLevel.addPlayer`. `EntityLeaveLevelEvent` is posted from `ServerLevel.EntityCallbacks.onTrackingEnd`.
- `ServerLevel.isPositionEntityTicking(BlockPos)` and `shouldTickBlocksAt(long)` are both public.
- **One factual correction (no design impact):** section 2 cites `getMaxSpeed` as 4/20. That is `MinecartFurnace`'s override (`(inWater ? 3 : 4) / 20`, plus NeoForge `getMaxCartSpeedOnRail` 0.2). The base `AbstractMinecart` value is 8/20. The note's conclusion, "about 3 to 4 intervals per chunk", holds for a furnace cart. Fix the wording.

## Checked against the plan page's seven questions
All seven are answered, from the sources:
1. **Lit:** server-only fuel, read through the public default display state.
2. **Tracking:** events plus an identity set, with an `isRemoved` and `level` backstop; no entity scan.
3. **Reuse:** the rules, the multiplier and the single `emit` call; no second path, no field write.
4. **Data or code:** data for the target and rate; a closed code list for the activity test. Rung 1 plus rung 3, recorded.
5. **Rate:** proposed.
6. **Cost and bounds:** covered.
7. **Edge cases:** the table covers every case the page lists, and more.

The page's fixed behaviour (server thread, loaded and ticking only, once per interval, exact amounts, no double count across borders) is met. **No split: agreed**, it's a small task.

## Decisions on Sindri's open points
1. **Entity ticking: yes, `isPositionEntityTicking` for carts.** A cart in a block-ticking but not entity-ticking chunk burns no fuel and keeps its flag frozen; it must not pollute forever. Blocks keep `shouldTickBlocksAt`. The rule is the same ("it emits only while it is actually running"), with the right test for each kind of thing.
2. **The 1,024-carts-per-level bound as a constant: fine for the alpha.**
   - Document it as a known limitation in `docs/emitter-mappings.md`, like 031's cap: lowest UUID wins, one `WARN` line per level per minute, the rest emit nothing.
   - A config key only if a playtest ever comes near it.
3. **Rate 0.5 PU per interval: approved as a P4 placeholder.**
   - It follows the user's fuel-burn rule: one coal burns 3,600 ticks in a cart (180 intervals, 90 PU at 0.5) against 1,600 in a furnace (80 intervals, 80 PU at 1.0).
   - No user decision is needed now; it's data.
4. **Data shape: the closed list in code (`EntityActivity`) with the rate in data. Approved.** An `entity` entry carrying `active_when` is rejected, and an `entity` id with no built-in activity test is skipped and logged at load. Revisit only if a second minecart-like entity is ever mapped.
5. **`dominantSource` from a moving cart: accepted for 032, routed onward.**
   - The cart's block position (usually a rail) becomes a chunk's dominant source, and can be stale a few seconds later.
   - The overlay and `get` show it as is.
   - **For P3 (Mimir):** siege targets `dominantSource` and breaks the source block (ADR 0006), so a cart's entry would send mobs to break a rail. P3's design must decide whether entity-sourced emissions are attributed. Recorded for P3 and not changed here.

## Changes to the note before implementation
- **C1. Test 8, cost:** report the figure, **don't assert on wall time**. A `nanoTime` threshold is flaky across machines, as in 011, 029 and 031. Report the cost per lit cart and per interval-start tick for 50 carts, and for 1,100 carts with the bound. Assert only the bound's effect: exactly 1,024 carts emit, and one warning is captured through a log seam, not by grepping the server log.
- **C2. Clean chunks:** the GameTests assert a chunk's pollution delta, so they use the 029 `cleanChunks`-style precondition: the chunk tracks no mapped block. The GameTest world is a normal world with structures and a new seed each run, and villages generate furnaces and campfires. Never assert a delta on a chunk that may hold a mapped block.
- **C3. The unload and reload test** waits on entity ticking, not a fixed tick count (003b, and the note's own section 7). A cart in a loaded, non-entity-ticking chunk is asserted with `isPositionEntityTicking == false` checked first, as 011 did for block ticking.
- **C4. The engine change is not the 031 cap's business.** The block cap, `ChunkTrack` and the rescan stay untouched, and `amounts.startInterval()` moving up is the only shared change. Add one regression line to the report: the existing `EmitterGameTests` and `VanillaEmitterGameTests` stay green, unedited.
- **C5. Fix the speed wording** (above).

## Standing rules (unchanged)
- Red first, quoted per test.
- Absence tests via mutants.
- Two consecutive passing runs.
- No Mixin or reflection.
- Files limited to what section 3 lists, plus the GameTests and the data file.
- **Heimdall and Sindri never run Gradle in this worktree at the same time.** I won't run anything here until you hand back.
