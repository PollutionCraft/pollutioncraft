# 013 review (Heimdall): APPROVED (not committed; the lead commits after Odin's acceptance)

## What I verified myself
- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0, "All 89 required tests passed", class-load check passed. JUnit XML: core **86** and mod 200, 0 failures.
- **Measurement:** `PERF 013 capacity(): 32 ns per call (4 types); recomputing 2500 chunks x 4 types once: 0.081 ms (= 0.0040 ms/tick …; budget 0.05)`. That is about 12 times inside the per-tick budget, and it's printed, not asserted.
- **Scope:** only `core`.
  - `AbsorptionModel` gains documentation and `@FunctionalInterface`.
  - New: `AbsorberCurve` (a record), `SoftCapAbsorption`, and four test classes (`AbsorberCurveTest`, `SoftCapAbsorptionTest`, `AbsorptionStepTest`, `AbsorptionPerformanceTest`).
  - Nothing in `mod` or `api`, no config, no data.
- **Code:**
  - `absorb` uses `-expm1(-n/k)` (accurate for small `n/k`) and never exceeds `cap`;
  - `capacity` allocates nothing and sums in table order;
  - `indexOf` returns −1 for an unknown id;
  - the constructor copies its list and rejects duplicate or blank ids;
  - invalid values throw with the value in the message.
  - Matches the brief.

## Mutation check (tmpfs copies, `:core:test`; the worktree was not touched)

| Mutant | Caught by |
|---|---|
| `k <= 0` weakened to `k < 0` | `invalidCapOrKThrowsAndNamesTheValue` |
| negative `n` allowed | `aNegativeCountThrows` |
| duplicate-id check disabled | `aDuplicateIdIsRejected` |
| the length check only rejects short arrays | `aLengthMismatchOrANegativeCountThrows`, `theTableCopiesItsList` |
| `k` doubled inside `absorb` | `atNEqualsK…`, both anti-spam tests |
| `indexOf` returns 0 for an unknown id | `indexOfFinds…` |

## The lead's three points
1. **Test 3's second clause: your replacement is right; my brief's clause was wrong.**
   - With the placeholder curves, no `k` of another type beats the step from `k` to `10·k` leaves (0.736). Grass gives 0.197 and crops 0.632.
   - Your version shows the true shape: once a type is saturated, more of it adds almost nothing (under 0.001), while a different type still adds its share (0.632). Accepted.
   - **For 015 I've rewritten the matching draft clause around the surface limit:** a chunk counts at most 256 surface blocks in total. The player-facing property is "within 256 columns, mixing types beats one type": 256 leaves give 1.96, while 128 leaves and 128 crops give 2.59. Going from 128 to 256 leaves adds only 0.23.
2. **Red-first by compile error only, for several tests: enough, now confirmed by mutants.** For pure-maths classes written test-first, the compile-error red shows the test existed before the class. My six mutants above show each of those tests fails when its own behaviour breaks. Nothing to redo.
3. **`absorbed` can't be read when `E ≤ C`: doesn't matter for 015's M3 test.**
   - The chunk ends at 0 and is pruned with its stats (architecture 3.2: a pruned chunk's remainder and stats are not recorded).
   - M3 compares local pollution (`getLocal`), which reads 0 for a pruned chunk, and that is exactly the "absorber area stays near 0" assertion.
   - **Readout note for 014:** `/pollution rates` and the overlay can't show `absorbed` for a chunk that absorption keeps fully clean, because there's no field entry. They show the cached **capacity** instead (014's readout), which answers "how much can this chunk absorb" without a field entry.
   - Recorded in the 015 draft as a known limitation of the readout, not of M3.

## Quality notes (no action)
- **Determinism:** `theSameCountsGiveTheSameBitsEveryTime` and the fixed table order.
- **The `r = 0` invariant:** pinned with spreading and decay on, over 150 intervals, through the real scheduler, with a check that the run really emitted and spread.
