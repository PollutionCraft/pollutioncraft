# 029 review (Heimdall): APPROVED (not committed; the lead asked for no commit)

## What I verified myself
- **A first attempt was invalid.** My first two runs (around 18:35) overlapped with your red-run round in the same worktree: the data files were rewritten at 18:32:40 and a second Gradle run was active. One run passed but its log check failed (the log had been replaced); the other saw half the data missing. I discarded both, waited until the worktree was quiet, and ran again.
- **Quiet worktree:**
  - `clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0, "All 89 required tests passed", class-load check passed.
  - `runGameTestServer` again: exit 0, 89/89.
  - JUnit XML: core 58 and mod 200, 0 failures.
- **Data:** the seven `minecraft` files are exactly as briefed: furnace 1.0; blast furnace and smoker **2.0** (user, 2026-10-27); campfire 0.25 with `lit`; torch, wall torch and lantern 0.01, always active. Nothing else under `resources/data`. No change in `mod/emitter`, `config` or `PollutionManager`.
- **Docs:** `docs/emitter-mappings.md` gains only the "Shipped mappings" table, with the twice-as-fast reason for the blast furnace and smoker, and the Out list.
- **Mutant (tmpfs copy):** smoker set back to 1.0 makes exactly `theBlastFurnaceAndSmokerBurnTwiceAsFastSoShipAtTwiceTheFurnaceRate` fail ("smoker 1.0 is not twice the furnace's 1.0"). The smoker's own test passes, because it reads the rate from the data. That is the intended split: the ratio test guards the user's rule, and the block tests guard the mechanics.

## Measurements (my two runs, worktree)

| Scenario | Total ms/tick | Emission pass | Rescan mean / p99 |
|---|---|---|---|
| 300 torches and lanterns in each of 20 chunks (6,000 tracked) | 0.178–0.186 | 841–863 µs/interval (0.140–0.144 µs per block) | 35–39 / 63–68 µs |
| One chunk at the cap (2,304 torches placed, 2,048 kept) | 0.080–0.083 | 270–289 µs/interval | 26–28 / 142–176 µs |

- Chunk-load scan: 24–28 µs (300 mixed), 90–92 µs at the cap, 1.4 µs plain.
- Every total is well under 0.5 ms/tick. The capped chunk's p99 is the finishing tick (sort, merge, validation), as accepted in the 031 review: the mean is the criterion, and the P4 performance run revisits it.

## The lead's points
1. **Red-first: enough, no redo.**
   - The blast furnace was red alone first, as briefed.
   - The other blocks were red together in one run, each failing for its own reason before its file existed ("emitted nothing" or "and is tracked"), and you repeated that round after the rate change.
   - The negative controls and the rate-ratio test were red through mutants.
   - Every test has its own quoted failing line before its data existed. "One block at a time" was a sequencing instruction; the evidence it was meant to produce is there.
2. **Rate doubling is asserted:** `theBlastFurnaceAndSmokerBurnTwiceAsFastSoShipAtTwiceTheFurnaceRate` checks both are exactly twice the furnace's shipped rate. Its red is quoted, and my mutant confirms it. It encodes the user's *rule* (rate follows fuel burn), not absolute numbers, so retuning all three together in P4 needs no test change.
3. **Slow GameTests: the tests are not the cause; nothing goes behind an opt-in property now.**
   - On tmpfs the same tree runs all 89 GameTests in **8.7 s**; in the btrfs worktree they take 3 to 5 minutes.
   - The slow batches (`vanilla_torch`, `vanilla_smoker`, the 031 unload and non-ticking batches) are the ones that force and generate far chunks. Their cost is disk I/O on this machine, the same finding as in the 030 and 031 reviews.
   - The CI follow-up from 031 stands: if CI's GameTest step goes over about 3 minutes, the `measure…` tests move behind an opt-in property.
   - New low-priority follow-up (Heimdall): a README tip for local runs on a copy-on-write filesystem (a tmpfs `mod/run`).
4. **Clean build and two consecutive runs after the rate change:** done above, on a quiet worktree.

## Quality notes (no action)
- `cleanChunks` makes every pollution-delta assertion depend on a chunk with no mapped blocks. It fails loudly if it can't find enough, so it never passes silently.
- Torch sums are compared within `1e-9`, with the reason stated; 1.0, 2.0 and 0.25 are compared exactly.
- **Process:** two engineers' runs in one worktree corrupt each other's results, because the run directory and log are shared. When the lead asks you for more runs while I'm verifying, the lead should tell me first. I'll note it in the handoff.
