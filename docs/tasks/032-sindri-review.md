# 032 review (Heimdall): CHANGES REQUESTED (one GameTest, one cost figure); design and code otherwise accepted

## What I verified myself
- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0, "All 100 required tests passed", class-load check passed.
- `./gradlew runGameTestServer` again: exit 0, 100/100.
- JUnit XML: core 58 and mod 208, 0 failures.
- **Rate 0.45:** recorded on the plan page as set by the lead on 2026-10-28 (fuel-burn rule, about 81 PU per coal against the furnace's 80). Accepted; it's a placeholder for P4.
- **Code:**
  - `EntityActivity`: a closed list, reading the **default** display state's `lit`.
  - `EntityEmitters`: an identity set per level, pruned on read (`isRemoved`, level changed), and the bound (lowest UUID through `BoundedLowest`, one warning per 1,200 ticks).
  - Join at `LOWEST` priority with cancelled events not delivered; leave; off-thread handoff; clear on stop.
  - `EmitterEngine`: one `startInterval()` shared by both passes, and the entity pass on the interval-start tick through the same `emit`.
  - **The block cap, `ChunkTrack` and the rescan are untouched.**
- **My five design-review changes:**
  - C1 (cost not asserted; bound asserted, with one warning through a log seam): done.
  - C2 (clean chunks): done.
  - C3 (wait on entity ticking): done for unload and reload.
  - C4: `git diff` shows `EmitterGameTests` and `VanillaEmitterGameTests` unedited, and they pass.
  - C5 (speed wording): fixed.

## Required changes
### R1. Test the real entity-ticking check, not only the seam
**Evidence:** in a tmpfs copy I replaced the production wiring in `EmitterEngine`, `(level, pos) -> level.isPositionEntityTicking(pos)`, with `(level, pos) -> true`. `runGameTestServer`: **"All 100 required tests passed"**. No test fails when the real check is removed, which is the guard against "a frozen cart pollutes forever" (design review, decision 1).

**Why the unload and reload test doesn't cover it:** after a reload the cart's synched "has fuel" flag starts `false` until its first tick, so a reloaded cart reads unlit anyway. The not-ticking window is masked by "unlit".

**The case that matters can be built:** a cart that is lit and burning while its chunk **drops** from entity-ticking to merely loaded.
- In 1.21.1, entities in a `FULL` (ticket level 33) chunk are still present but don't tick, so the flag stays frozen at `true`.
- 011's non-ticking test already holds a chunk at level 33 with a region ticket at distance 0.

**Sequence:**
1. Force the chunk, light the cart, and wait until it is lit and entity-ticking (it emits).
2. Add the level-33 ticket, then release the force.
3. Wait until `isPositionEntityTicking` is false **and** the cart is still present, with `getDefaultDisplayBlockState()` still `lit=true` (assert both, so the case is real).
4. Over the next interval: **no emission**.
5. Re-force: it emits again.

Show its red with the mutant above (`(level, pos) -> true`), quoted.

**Fallback (the 011 rule):** if the demotion isn't deterministic in two attempts, report the attempts and keep the seam test. In that case add a small GameTest that the engine's default `EntityEmitters` really uses `isPositionEntityTicking`. For example, construct the engine's tracker through a package-visible factory and call its `TickCheck` on a known ticking and a known non-ticking position.

### R2. Cost with the real check (report only)
The reported figures stub the tick check, so they exclude `isPositionEntityTicking`.
- Add a figure for **50 lit carts in loaded, ticking chunks through the production `EntityEmitters`** (real predicate, real `emit`, as the engine runs it): µs per interval-start tick and per cart.
- Keep the stubbed 1,024 figure for the bound.
- Not asserted.

My runs of the stubbed figure: 50 carts 6.0 to 11.3 µs, 1,024 carts 37 to 57 µs per interval. Far inside budget; the real check should add tens of nanoseconds per cart.

## The lead's questions
1. **The bound and seam tests were written after the code, with a mutation run instead of a red: accepted,** as ruled in 031.
   - The behaviour tests (`EntityEmitterGameTests`) went red against the missing code first.
   - The bound tests' mutation run (cap ignored, tick check removed, pruning removed) shows they catch what they claim.
   - The same mutation approach is how R1's red is shown.
2. **Real predicate coverage through unload and reload: not adequate** (R1 above, with the mutant as evidence).
3. **The five design-review changes:** all applied (above).
4. **Cost with the real check:** R2.

## Quality notes (no action)
- `BoundedLowest` allocates a priority queue only when over the bound: `O(n log k)` once per interval, acceptable.
- The cap warning fires even when no entity rule is loaded (over 1,024 carts with the mapping removed). That's harmless and arguably useful.
- A dominant source from a moving cart: accepted for 032 and routed to P3 (Mimir), as in the design review.

Re-review: R1's test with its quoted red, R2's figure, and one clean run plus one plain `runGameTestServer`, both green. Then I send Odin the acceptance request.

## Round 2: APPROVED (not committed; the lead commits after Odin's acceptance)

- **Two runs I did myself:**
  - `clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0, "All 102 required tests passed", class-load check passed.
  - `runGameTestServer` again: exit 0, 102/102.
  - JUnit XML: core 58 and mod 208, 0 failures.
- **R1, done.** `aLitCartInAChunkThatStoppedEntityTickingEmitsNothingUntilItTicksAgain`:
  - holds the chunk at ticket level 33 and asserts both preconditions (entities no longer tick there; the cart is present with its frozen `lit=true`);
  - asserts 0 over an interval;
  - at level 31, the same cart emits the rate.
  - **My mutant** (production wiring `(level, pos) -> true`, tmpfs copy): **exactly that test fails**, "a lit cart where entities do not tick emits nothing, got 0.45", with "1 required tests failed". The earlier knock-on into other tests is gone, because the test now removes its cart and tickets on assertion failure.
- **R2, done.** Through `EmitterEngine.global().tick` with 50 lit carts in entity-ticking chunks, using the real check and real `emit`:
  - entity pass **14.0 µs** and **28.1 µs** per interval-start tick in my two runs (with 15.0/35.3 µs, without 1.0/7.2 µs);
  - about 0.3 to 0.6 µs per cart, or under 0.0015 ms/tick averaged;
  - far inside the 0.5 ms/tick budget. Not asserted.
