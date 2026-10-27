# 031 step 0 review (Heimdall): APPROVED with amendments; start implementing

The design note is `031-brokkr-design.md`; that name is accepted in place of `031-brokkr-step0.md`.

## Your six decisions
1. **"The `cap` lowest packed positions win", a `LongRBTreeSet` per chunk, and front-of-queue repair: approved.**
   - The convergence argument in section 1 holds. "Keep the `cap` smallest" does not depend on order, and `offer` keeps `T(M)` invariant under placement because every untracked mapped position ranks above every tracked one.
   - It uses the same ordering as 010's tie-break and `dominantSource`, so the mod has one ordering rule.
   - The race closure (merge with re-validation, plus one `dirty` restart per scan) is enough.
2. **The second rescan work limit (512 set changes per tick, with a cursor inside the section): approved provisionally.**
   - Keep it only if the measurement justifies it (amendment B).
   - If the pathological slice stays under about 0.1 ms p99 without it, remove it, along with the in-section cursor, and report that. No dead code.
3. **A cap change re-queues all loaded chunks, as a rule swap does: approved.**
   - One config read per level per tick is negligible.
   - A lowered cap may emit for the old, larger set until the chunk is rescanned. That is a few seconds and acceptable; state it in `docs/emitter-mappings.md`.
4. **The log rule** (one line per chunk per 1,200 gated ticks, from a scan or from a placement that is ignored or evicts; the guard map survives unload and is pruned after two periods): **approved.**
5. **Bounds 1 to 98,304, default 2,048, no cross-field rule: approved.**
   - The bound is a ceiling, not advice; the comment wording is fine.
   - Your point that the engine offers positions to the buffer in ascending order, so attribution stays deterministic even above `maxEmittersPerChunk`, is a good one. Put it in a comment at the emission loop.
6. **`MappingEntry` with a nullable nested `ActiveWhen` record: approved.** It makes the half-specified state impossible to represent. The rejection list in section 8 matches the brief.

## The questions from the lead
- **"Emits one fewer block until repair": acceptable.**
  - It happens only in chunks over the cap, which are pathological by definition.
  - It errs low, never above `cap` and never for an unmapped block.
  - It is bounded: noticed at the next interval start, repaired within about 16 engine ticks.
  - The repair test (your test 5) must measure the bound in **engine ticks**, driving a private engine instance directly (amendment C).
- **A level-wide cap or a lower default: neither, now.**
  - The per-chunk cap answers 011's follow-up: it stops a single chunk from making cost or memory unbounded.
  - A level-wide budget is a different, scope-level limit, and a lower per-chunk default would not bound the level total either.
  - Your break-even, about 40,000 active tracked positions for the whole 0.5 ms/tick, is about 7 times the "300 in each of 20 chunks" case (6,000).
  - **Recorded for 029 and the P4 playtest**, as information for Odin to put in front of the user when 029's rates are set. Not a blocker, and it does not need a user decision now.
  - Options if playtests come near it: a level-wide cap, aggregating positions per chunk into one emission (already listed as a possible later change), or spreading the emission pass across ticks. That last one smooths the spike but not the average.

## Amendments (required)
- **A. Confirm the per-emitter cost.**
  - Report the emission pass's cost per tracked emitter, measured in 031 (µs per position, at 300 and at 2,048 per chunk and at 6,000 total).
  - Report the resulting **break-even number of active tracked positions** at 0.5 ms/tick, with the measured diffusion cost.
  - This replaces the 011 estimate the design relies on.
- **B. Justify the 512 limit with data.** For the "chunk at the cap" case, also measure an unfavourable layout: a mapped block in every cell of several sections, offered in the worst order for the tree. Report the rescan slice p99 and mean **with and without** the second limit. Keep the limit only if "without" exceeds about 0.1 ms p99.
- **C. Make the timing tests deterministic.**
  - The repair bound (test 5) and the cap-change bound (test 6) drive a **private engine instance** with `engine.tick(level, …)` in a loop and count ticks to convergence, with a fixed upper deadline.
  - Never use wall-clock time.
  - Use the capturing `Consumer<String>` for the log assertions, as you propose.
- **D. Percentile sample size.** Compute p99 over at least 200 busy ticks (011's measurement already has about 256). Print the sample count next to it.
- **E. Docs.** `docs/emitter-mappings.md` gains:
  - `active_when` optional, with a torch-like example;
  - the cap, its key, and "the lowest packed positions win" in plain words (west and north first, then lowest);
  - the lowered-cap transient (amendment to decision 3).

## Unchanged from the brief
- File ownership.
- The glowstone test mapping at 0.25.
- Red first with quoted lines, absence tests via mutants.
- Assertions only on placed positions.
- Two consecutive passing runs.
- The three measurement scenarios plus amendment A.
