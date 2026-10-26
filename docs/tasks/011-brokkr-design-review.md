# 011 step 0 review (Heimdall): APPROVED with amendments; start implementing

The design note is `011-brokkr-design.md`; that file name is accepted in place of `011-brokkr-step0.md`.

## What is approved
- **`lit` finding:** recorded from the NeoForge 21.1.264 sources, `AbstractFurnaceBlock.LIT`, set with flag 3 by `serverTick`. Furnace block entities tick only where `shouldTickBlocksAt` is true. That is enough for condition 3. The M1 GameTest is the runtime proof.
- **Core strategy:** track the positions of every mapped block, read the current state at each one once per interval, and emit when `active_when` matches.
  - `lit` toggles, removal and replacement need no event, and stale positions drop out on read.
  - This is simpler and more robust than event-driven state tracking.
- **Hooks:**
  - `ChunkEvent.Load` scan with `maybeHas`;
  - `NeighborNotifyEvent` for placements (your reading of `/setblock`, `/fill` and flag-3 paths is accepted);
  - `ChunkEvent.Unload`;
  - a reload listener that parses, plus `TagsUpdatedEvent` that resolves and swaps one reference;
  - `ServerStoppedEvent` clears everything.
- **Public API only:** no Mixin, reflection or access transformer.
- **Sampling once per interval (section 6.1):** accepted. It matches the PU definition: one lit furnace for one interval. Short pulses alias, and the long-run mean is right.

## Decisions on your two questions
1. **Call the engine on every gated tick: approved.**
   - `PollutionManager.tickDimension` calls `engine.tick(level, intervalStarts)` on each gated tick, with `intervalStarts = !scheduler().inFlight()`, before `scheduler().tick(...)`.
   - The emission pass runs only when `intervalStarts` is true. Every other tick runs only bounded rescan work.
   - This spreads the cost the way the scheduler already slices diffusion, and avoids a spike once a second.
   - Paused records and closed gates already return earlier, so the engine does not run for them.
   - This replaces the "interval start only" line in the brief.
2. **Keep the 60-second refill: approved, with a work-based budget (amendment A).** GTM-scale packs carry building tools and machines from other mods that place blocks without the update flag. A bounded safety net that finds them within about a minute is worth it. No config key; constants with a comment.

## Amendments (required)
A. **Budget the rescan by work, not by chunks.**
   - The problem: one chunk whose sections all hold a mapped state costs about 100k reads, a few hundred µs (your section 4). Eight of those in one tick would blow the 0.5 ms/tick budget on their own.
   - The rule: per tick, run `maybeHas` on up to 8 queued chunks, but fully walk at most a fixed number of sections per tick, about 4, roughly 16k state reads. Keep partial progress, a chunk and section cursor, across ticks.
   - **Show** with a measurement that the rescan costs at most **0.1 ms/tick** in the worst case: every queued chunk has a mapped block in every section.
   - Chunk-load scans stay unbudgeted, because they run inside vanilla's own chunk-load cost. Report their typical and worst-case µs.

B. **Every rule swap enqueues all loaded chunks, including the first one at server start.** If `TagsUpdatedEvent` arrives after some chunks have loaded, those chunks are covered by the queue and not missed. One GameTest or a log line in the report must show the furnace mapping active after a normal start without waiting for a rescan, or say how long it took.

C. **Server thread only.** Every tracker mutation happens on the server thread.
   - The parse in the reload listener's prepare stage runs off-thread and must stay pure: it builds raw entries only.
   - Resolution and the swap run on the server thread (`TagsUpdatedEvent`).
   - In each event handler, check `level.getServer().isSameThread()` (or the equivalent). If a call ever arrives off-thread, hand it to `server.execute(...)`. Do not mutate from another thread.
   - Say in the report which thread you observed for `ChunkEvent.Load`.

D. **Fallback for test 5 (loaded but not block-ticking).**
   - Try the region-ticket setup as you describe, and assert `shouldTickBlocksAt` is false before relying on it.
   - If you can't make it deterministic in two attempts, inject the ticking check: a small functional seam defaulting to `level::shouldTickBlocksAt`. Test the gating through the seam, and say so in the report. Do not leave a flaky test.
   - The same applies to unload: the real `setChunkForced(false)` path first; driving the handler directly is acceptable only if the real path is not deterministic, and you report it.

E. **Measurements to report** (in addition to brief test 9):
   - total ms/tick, averaged over an interval: diffusion + engine emission pass + rescan, at 100 active emitters; it must be ≤ 0.5;
   - the emission pass at **1,000** tracked emitters, for information only;
   - `NeighborNotifyEvent` handler cost for a synthetic burst of non-matching updates, in ns per event;
   - whether the `PollutionManager` path cache was needed (condition 2).

## Unchanged from the brief
- File ownership.
- Red-first with quoted lines for every test.
- The test list.
- No new config key.
- No `stepNow` engine call.

The amendments only add measurements and one budget rule; they don't change any test's purpose.
