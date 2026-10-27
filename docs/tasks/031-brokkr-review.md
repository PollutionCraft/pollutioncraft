# 031 review (Heimdall): APPROVED

## What I verified myself (in this worktree)
- `clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0, "All 79 required tests passed" (GameTest phase 1.82 min), class-load check passed.
- `runGameTestServer` again: exit 0, 79/79 (2.36 min).
- JUnit XML: core 58 and mod 200, 0 failures.
- **Ownership:**
  - `mod/emitter/**` (new `ChunkTrack` and `ScanHeap`).
  - `ConfigRegistry` and `ServerConfig`: exactly the one key (`maxTrackedPerChunk`, INTEGER, 2,048, [1, 98,304], no cross-field rule) and its accessor.
  - `docs/emitter-mappings.md`, the test-only `glowstone.json`, `MappingValidatorTest`, the new `ChunkTrackTest`, `EmitterGameTests`.
  - **No shipped data changed:** nothing under `mod/src/main/resources`.

## My measurements (two runs, this worktree, btrfs)

| Scenario | Total ms/tick | Rescan slice mean / p99 (with the 512 limit) | p99 without the limit | Per tracked block |
|---|---|---|---|---|
| 300 in one chunk | 0.082–0.102 | 45–54 / 93–129 µs | 63–83 µs | 0.154–0.177 µs |
| One chunk at the cap (2,304 placed, 2,048 kept) | 0.092 | 19–27 / 99–112 µs | 185–194 µs | 0.131–0.146 µs |
| 300 in each of 20 chunks (6,000) | 0.157–0.164 | 31 / 48–51 µs | 39–45 µs | 0.146–0.150 µs |
| Dense: every cell of 8 sections (32,768 placed) | 0.099–0.105 | 34–35 / 143–155 µs | 250–265 µs | 0.126–0.132 µs |

- Every p99 is taken over at least 300 busy ticks.
- Break-even: 57,800 to 60,400 active tracked blocks for the whole 0.5 ms/tick.
- Every total is at or under 0.165 ms/tick, against 0.5.

## Mutation check (scratch copies on tmpfs; the worktree was not touched)

| Mutant | Where | Failed |
|---|---|---|
| U1: `ScanHeap` keeps the highest instead of the lowest | `ChunkTrackTest` | 3 of 7 |
| U2: `ChunkTrack.offer` allows `cap + 1` | `ChunkTrackTest` | 5 of 7 |
| G: no repair after a tracked removal, and the cap-log period guard removed | GameTests | 4 |

The four G failures:
- `removingATrackedBlockFromACappedChunkIsRepairedWithinTheBound` ("did not converge within 16 engine ticks");
- `theCapLineIsWrittenOncePerPeriodAndNotPerPlacement`;
- `aBlockRankingInsideTheCapEntersAndTheBoundaryOneLeaves` ("events never add a line per placement: 3");
- `aChunkOverTheCapTracksTheLowestPositionsAndEmitsForThemOnly`.

Your own mutants cover cap removed, tick check removed and section resume removed.

## Decisions on the lead's questions
1. **Red-first for `ChunkTrackTest`: accepted, recorded as a process deviation.**
   - The cap's **behaviour** was test-first: the GameTests failed against stubs, and the absence tests via mutants.
   - `ChunkTrack` and `ScanHeap` replaced the approved tree set after a measurement. Their unit tests came after the rewrite, which you disclosed.
   - My mutants show those tests catch real faults.
   - **Next time a measured redesign replaces a structure, write the new structure's unit tests first, then swap it in.** The old structure is a fine stub to go red against.
2. **Dense-layout p99 of 143–155 µs: accepted; no split of the finishing tick now.**
   - The dense case (32,768 mapped blocks in one chunk) is deliberately pathological, and it costs one heavy tick per chunk per scan.
   - The **realistic worst case** is a chunk at the cap: p99 99–112 µs, mean 19–27 µs. Even a plain 300-block chunk shows p99 up to 129 µs on this machine, so p99 sits at the noise level of this machine under load.
   - The approved criterion from the 030 review is the mean (≤ 70 µs), and every mean passes.
   - Totals stay under a third of the budget.
   - Follow-up for the P4 performance run: if spark on target hardware shows the finishing tick, slice the validation reads across ticks.
3. **Run time: accepted; not caused by the measurement test.**
   - Batch timestamps in this worktree: the measurement batches take 4–6 s. The time goes to the chunk-forcing batches (`emitters_unload` 23 s, `emitters_furnace` 27 s, `emitters_always_unload` 20 s, `emitters_always_nonticking` 14 s) and `defaultBatch:1` (28 s).
   - **The same tree on tmpfs: 79/79 in 6.7 s.** It is disk I/O on the btrfs worktree (chunk generation and saves), the same pattern found in the 030 review.
   - Follow-up (Heimdall): check the CI job time on this PR. If CI's GameTest step goes over about 3 minutes, move the `measure…` tests into an opt-in GameTest namespace enabled by a Gradle property.
4. **The `long[]` plus heap replacing the approved `LongRBTreeSet`: accepted.**
   - It is data-driven: the tree set's rescan p99 was 870 µs dense and 559 µs at the cap.
   - It keeps the approved semantics ("keep the `cap` lowest", order-independent), and my mutants confirm them.
   - The 512-kept-positions limit is justified by the "with and without" figures above.
   - **No level-wide cap:** confirmed. Break-even is about 58,000–60,000 active tracked blocks; 20 chunks at the default cap is 41,000. Already recorded for Odin to show the user with 029's rates.
5. **Ownership and shipped data:** clean, see above.

## Pass 2: quality (no action)
- **`ChunkTrack`:** the sorted array keeps emission iteration in ascending packed order, so attribution stays deterministic above `maxEmittersPerChunk` (comment present at the loop).
- **Repair:** the front-of-queue path and the single `dirty` restart match the design.
- **Rescan finish:** the merge re-validates both inputs with one read each.
- **Cap log:** the per-level tick guard survives unload and is pruned at each refill.

Committed by Heimdall.
