# 031 step 0: design note (Brokkr)

Status: proposal for Heimdall, no production code written. The brief named `031-brokkr-step0.md`; this file is `031-brokkr-design.md`, the name used in the request. Based on the 011 engine as merged (`EmitterEngine`, `EmitterRules`, `MappingValidator`, `MappingEntry`).

## 0. Summary of the proposal

- The kept set of a chunk is **the `cap` lowest packed positions** (`BlockPos.asLong()`, signed `Long.compare`) of the blocks that currently hold a mapped block. Call that set `T(M)`, where `M` is the set of mapped positions in the chunk.
- Per chunk the engine keeps a **sorted set** (fastutil `LongRBTreeSet`) of at most `cap` positions, plus three small flags. Insertion is "add; if over the cap, remove the last". The same rule is used by the chunk-load scan, the placement event and the rescan, which is why they converge.
- Removal needs no event (as in 011). The one thing it cannot do alone is refill a slot from the blocks the engine never saw, so a chunk that **was over the cap** and loses a tracked position is **re-queued at the front of the rescan queue**.
- A cap change (`/pollution tune`) is noticed on the next engine tick and re-queues all loaded chunks, exactly like a rule swap.
- `active_when` becomes optional; absent means always active; a half-specified one is rejected.
- No cross-field rule with `maxEmittersPerChunk` (agreed, section 7).

## 1. The cap policy and why every path gives the same set

**Order.** Signed `Long.compare` on `BlockPos.asLong()`. Inside one chunk that orders by x, then z, then y (x is the top field of the packed long). The same order the 010 buffer and `dominantSource` tie-break already use, so there is one ordering rule in the mod.

**Data structure.** `ChunkTrack` (new package-private class in `mod.emitter`) holds:
- `LongRBTreeSet positions` (size at most `cap`; sorted, so iteration order is deterministic too);
- `int cap`, the cap read when this chunk was last scanned (or when the track was created);
- `boolean overCap`: at least one mapped position is known to exist outside `positions`;
- `boolean dirty`, `boolean rescanQueued` (see below).

**One insertion rule**, `offer(p)`: if `p` is already in the set, nothing. If `size < cap`, add. Otherwise, if `p < last()`, add `p` and remove the old `last()` and set `overCap`; else ignore `p` and set `overCap`. Cost O(log cap), at most about 11 steps at the default.

**Why the paths converge.** Let `M` be the mapped positions of the chunk and `T(M)` its `cap` smallest.
1. **Chunk-load scan** (synchronous, whole chunk): feeds every mapped position through `offer` into a fresh set. After the walk the set is exactly `T(M)`, whatever order the sections and cells are visited in, because "keep the `cap` smallest seen" is order independent. The load scan does not depend on any earlier state (the chunk's track was dropped on unload), so unload, reload and restart give the same set.
2. **Placement event** (`onBlockChanged`): `offer(p)`. If the set was `T(M)` before, it is `T(M ∪ {p})` after: every position outside the set is larger than every position inside it, so the `cap` smallest of `M ∪ {p}` are the `cap` smallest of `T(M) ∪ {p}`.
3. **Rescan** (sliced over ticks): builds a fresh set with `offer` and, at the end, merges it with the old set after re-validating both (section 3), then keeps the `cap` smallest. With no concurrent change the result is `T(M)`.

Hence: after any complete scan that ran without a tracked position being removed in that chunk meanwhile, the tracked set is `T(M)` exactly, independent of load, placement and event order. In between, the set can only be missing entries (never hold more than `cap`, and never hold an unmapped position for longer than one interval).

## 2. A block placed in a chunk that is already at the cap

`offer` applies. A placement at a position that ranks inside the cap enters and evicts the highest tracked position; the evicted one becomes "outside, unknown to the engine" and `overCap` stays true. A placement ranking outside the cap is ignored (and sets `overCap`). Neither path logs per placement (section 6).

## 3. Removal under the cap and how the set returns to `T(M)`

A tracked block that is removed is dropped at the next interval-start read (as in 011). A slot has been freed, but the engine does not know the over-cap blocks, so it cannot refill it by itself.

Mechanism: in the emission pass, when a position is dropped from a chunk whose `overCap` is true, the engine **enqueues that chunk at the front of the rescan queue** (`LongArrayFIFOQueue.enqueueFirst`, once, guarded by `rescanQueued`). The rescan rebuilds the set with the new freed slot filled by the next-ranked block.
- **Transient until then:** the chunk emits for `cap - 1` blocks, never for more than `cap` and never for an unmapped block. This is fewer than the deterministic set by the number of removals not yet repaired.
- **Bound:** the repair starts when the queue reaches it (at the front, so after the chunk currently being scanned, at most one chunk of 24 sections / 3 per tick = 8 ticks plus pre-check) and takes at most 8 ticks for 24 sections. So the set is back to `T(M)` at most about 16 ticks after the interval-start read that noticed the removal, and the removal is noticed at most one interval after it happened. For intervals shorter than 16 ticks the repair can span more than one interval; that is the transient above.
- A removal of an **over-cap** block changes nothing for the engine (the tracked set is still `T(M)`), and `overCap` stays set until the next scan; a stale `overCap` costs one unnecessary rescan at most.
- A removal from a chunk that was **not** over the cap needs no rescan: `M` equals the tracked set.

**Events during a scan.** A scan spans several ticks, so blocks can change under it. The rescan's final step merges the freshly built set with the old set, keeping only entries whose block is still mapped (a read of at most `2 x cap` states), then trims to `cap`. A placement event during the scan went through `offer` into the old set, so it survives the merge. If a tracked position was **dropped from this chunk while the scan was running**, the chunk is flagged `dirty` and, at the end of the scan, re-enqueued once at the front (one restart per scan, no loop). This closes the only race that could leave a ranked-in block out; anything else is repaired by the next 60 s refill.

## 4. Cap changes through `/pollution tune`

- The cap is read **once per chunk scan** (load scan, or when a rescan starts the chunk) and stored in the chunk's `ChunkTrack`. Placement events use that stored value, so "a change applies from the next scan" holds for events too.
- Each engine tick compares the effective cap with the one last seen (one config read per level per tick). On a difference the engine **re-queues all loaded chunks**, the same call a rule swap uses (`enqueueAllLoaded`), and aborts the scan in progress (it restarts with the new value). It does not wait for the 60 s refill. Time to finish: the same as a rule swap, bounded by the rescan budget (about 8 pre-checks per tick, 3 sections per tick), typically seconds for a normal number of loaded chunks.
- **Lowering:** the rescan builds a fresh set with the new cap and merges the old entries; the result is trimmed to the new `cap` by the same rule, so the highest positions leave. Until the chunk is rescanned it keeps the old, larger set (so it can emit for more than the new cap for a few seconds).
- **Raising:** the rescan lets previously ignored positions in. Until then the chunk emits for the old, smaller set.
- Test seam: tests set the key through `RuntimeOverrides` (as the brief says) and read it through the engine's real supplier.

## 5. Reload, rescan and the work budget when a chunk holds thousands of mapped blocks

- **Rule swap:** unchanged from 011: every loaded chunk is queued; the rescan builds each chunk's fresh set under the new rules, merges the old entries validated under the new rules, and trims to the cap. A block that stopped being mapped is dropped by validation, not just at the next read.
- **Cost of the new work.** The section walk is unchanged (about 4 ns per cell, 3 sections per tick, measured at 54 to 67 us mean per tick in 011). What is new is the `offer` per mapped cell: a comparison when the set is full and the candidate is larger than the last, and a tree insert plus removal (O(log cap), around 100-200 ns) otherwise. In a chunk with a few hundred mapped blocks that is a few tens of microseconds in total. In a pathological chunk where thousands of cells in a section are mapped and the scan order is unfavourable, a section can cost a few hundred microseconds.
- **Proposal: a second work limit on the rescan slice.** Besides 3 sections per tick, a slice stops after **512 set mutations** (inserts and evictions). To make this resumable inside a section, the scan keeps an in-section cell cursor (an `int` 0..4095). Chunk-load scans stay unbudgeted, as in 011. The constant is a starting value that I will tune from the measurement of the "chunk at the cap" case; if the numbers show it is unnecessary I will report that rather than keep dead code, but I think the pathological case is exactly what the cap exists for.
- **Emission pass.** Cost is linear in tracked positions: about 0.19 us per emitter in 011's measurement (state read plus `emit`). One chunk at the default cap is about 0.4 ms per interval (0.02 ms/tick); 20 chunks of 300 is about 1.1 ms per interval (0.06 ms/tick). **Concern for 029:** the cap is per chunk only, so the level-wide total is unbounded; at 0.19 us per emitter the 0.5 ms/tick budget (with 0.1 ms for diffusion) is exhausted at roughly 40,000 active tracked positions. 20 chunks at the full default cap (41,000) would be at the limit. I am not proposing a level-wide cap (out of scope), but the 031 measurements will show where we are, and the number should go in front of the user when 029 sets rates and the default.

## 6. The log line: exact condition

One line per chunk per rescan period, never per block and never per event.
- Per level, the engine counts gated ticks (the refill already uses such a counter). `LevelState` keeps a small map `chunk -> tick of last cap log`.
- A cap line is written **only** at these two points, and only when the chunk's last cap line is older than `REFILL_INTERVAL_TICKS` (1,200 gated ticks) or there is none:
  1. a **load scan or a completed rescan** finds more than `cap` mapped blocks (the line says how many and what the cap is);
  2. a **placement event** is ignored or evicts (so a chunk pushed over the cap by building is reported at once, without waiting for a rescan).
- A placement into an already-reported full chunk therefore costs nothing (one tick-number comparison). A chunk that stays over the cap is reported again once per period when the refill rescans it, which is the "per rescan period" wording.
- The log map survives chunk unload (so walking back and forth does not repeat the line); the refill prunes entries older than two periods, so it stays small.
- Line: `Chunk <x>, <z> in <dimension> holds N mapped emitter blocks; only the lowest <cap> positions (maxTrackedPerChunk) are tracked and emit, the rest emit nothing`. N is exact for a scan and "more than <cap>" for an event.
- Testability: the engine takes a `Consumer<String>` for log lines and an `IntSupplier` for the cap through a new constructor (the existing four-argument constructor delegates to it with the global logger and `PollutionCraftMod.config()::maxTrackedPerChunk`). The GameTests use private engine instances with a capturing consumer, not the server log.

## 7. The key

- Name `maxTrackedPerChunk`, integer, **default 2,048**, **bounds 1 to 98,304**. 98,304 is 16 x 16 x 384, the number of block positions in one overworld chunk, so a larger value could never be reached; the bound is a ceiling, not a recommendation. 1 is allowed (a degenerate but well-defined cap: only the lowest position).
- Comment, in the style of the existing keys: `Caps the mapped blocks (furnaces, torches and so on) the emitter engine tracks and emits for in one chunk. Past it, the blocks with the lowest positions are kept and the rest emit nothing, with one log line per chunk per minute. Applies from the next chunk scan. Different from maxEmittersPerChunk, which caps attributed positions in the pollution buffer.`
- `ServerConfig.maxTrackedPerChunk()` returns an `int`; the registry-lockstep test passes unchanged.
- **No cross-field rule with `maxEmittersPerChunk`.** I agree with the brief. If `maxTrackedPerChunk` is larger, the extra tracked positions still emit and still count in the chunk total and in `grossEmitted`; 010 only leaves them **unattributed** (they cannot be `dominantSource`). Nothing is lost. The 010 buffer attributes the first `maxEmittersPerChunk` positions it is given, and the engine always offers them in ascending packed order (it iterates the sorted set), so even then the attributed set is deterministic. A cross-field rule would also be the first multi-key rule and so trigger 008's open obligation (re-check effective values on reload, define what happens to an override that becomes invalid). The default pair (2,048 below 4,096) keeps attribution complete.

## 8. Validation change and `MappingEntry`

- `MappingEntry` replaces `property`/`value` by a nullable nested record: `ActiveWhen(String property, String value)`, with `isAlwaysActive()` meaning `activeWhen == null`. A nested record keeps "both or neither" unrepresentable as a half state, instead of two nullable strings that could disagree. (The 011 tests that read `entry.property()` and `entry.value()` are updated; I own them.)
- **Accepted:** no `active_when` key at all (always active); `active_when` with a non-empty string `property` and a non-empty string `value` (as today).
- **Rejected, one log line naming the file (never guessed):** `active_when` present but not an object (including JSON `null` and a string); an empty object `{}`; `property` without `value`; `value` without `property`; empty or blank strings for either; a non-string `property` or `value` (for example the boolean `true`, as today).
- `EmitterRules`: a rule with no property matches every state of its block. Conflict detection: an always-active rule overlaps every other rule on the same block, so it produces the usual "lowest id wins" warning against any sibling; two entries with different `active_when` properties are already treated as overlapping.
- `docs/emitter-mappings.md`: `active_when` optional (with the example of a torch-like entry), the cap and its key, and the "lowest packed positions win" rule.

## 9. Test plan (each with its red before implementing)

Assertions are always on the test's own placed positions (`isTracked`), never on a level-wide count.
1. **Unit, validator:** the forms in section 8, including each rejected form with exactly one log line.
2. **GameTest, always active (test-only `glowstone.json`, rate 0.25):** placed emits exactly 0.25; removed stops; non-ticking chunk emits nothing; unload and reload emits again (the 011 setups).
3. **GameTest, regression:** the test lamp still emits only while lit.
4. **GameTest, cap 8 through `overrides().set`:** 12 glowstones, exactly the 8 lowest tracked (positions asserted), emission 8 x 0.25 = 2.0, exactly one captured log line, a second chunk with 4 unaffected.
5. **GameTest, determinism:** same 12 in a different order and after unload and reload give the same set; a placement ranking inside the cap enters and the boundary one leaves; removal from a capped chunk is repaired within the stated bound (a deadline measured in engine ticks).
6. **GameTest, cap change via `tune`:** lowering trims, raising admits, each at the next scan within the stated bound.
7. **Measurements** as the brief lists (300 in one chunk, a chunk at the cap, 300 in each of 20 chunks): emission pass, chunk-load scan typical and worst, rescan slice 99th percentile and mean, total ms/tick; `measureTheEngineCost` switches to the same reporting.
8. Absence tests (nothing emitted in a non-ticking chunk; over-cap positions emit nothing) get their red from mutants.

## 10. Decisions I need
1. The rule "the `cap` lowest packed positions win" with a `LongRBTreeSet` per chunk, and the front-of-queue repair after a tracked removal from an over-cap chunk (sections 1 to 3).
2. The second rescan work limit (512 mutations per tick, in-section cursor), section 5.
3. Re-queue all loaded chunks when the cap changes (section 4).
4. The log rule with the per-level tick guard that survives unload (section 6).
5. Bounds 1 to 98,304, default 2,048, and no cross-field rule (section 7).
6. `MappingEntry.ActiveWhen` nullable nested record (section 8).
