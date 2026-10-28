# 013 report (Brokkr): `AbsorptionModel` soft-cap curve, `r = 0` pinned

**Status: DONE_WITH_CONCERNS** (one clause of the brief's test 3 cannot hold with the placeholder curves; see Concerns). Core only, nothing in `mod`/`api`. Nothing committed.

## Files
- New `core/.../AbsorberCurve.java` (record `AbsorberCurve(cap, k)` with `absorb(int n)`), new `core/.../SoftCapAbsorption.java` (ordered table of `Type(id, curve)`, `capacity(int[] counts)`, `indexOf`, `size`). The names are my choice.
- `AbsorptionModel.java`: `@FunctionalInterface` and documentation only; no implementation.
- New tests: `AbsorberCurveTest`, `SoftCapAbsorptionTest` (including the anti-spam shape and the functional-seam check), `AbsorptionStepTest` (DiffusionStep, IntervalScheduler and the `r = 0` invariant), `AbsorptionPerformanceTest`.

## Fresh results
- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache --no-daemon -q`: exit 0, "All 89 required tests passed :)" (20:20:28), class-load check passed. Unit tests: core 86 (was 58 before this task, 28 new), mod 200, none failed.
- `./gradlew :core:test`: passes. Measurement line:
  `PERF 013 capacity(): 31 ns per call (4 types); recomputing 2500 chunks x 4 types once: 0.077 ms (= 0.0038 ms/tick if spread over a 20-tick interval; budget 0.05)`
  The full recompute is 0.077 ms, about 13 times inside the 1 ms-per-interval budget (0.05 ms/tick). `capacity` allocates nothing and sums in table order. No assertion on wall time.

## Design notes
- `absorb` computes `cap * -expm1(-n/k)`, which is `cap * (1 - e^(-n/k))` without losing precision for small `n/k`; it is finite, `>= 0` and never above `cap`, so `DiffusionJob`'s capacity check cannot trip. Because `exp` underflows only far above where the value rounds to `cap`, the sum of capped terms stays finite even for `Integer.MAX_VALUE` counts (tested).
- `Type` rejects a blank id or a null curve; the table rejects duplicate ids and copies its list.
- No `r` key or split code anywhere; `DiffusionJob` is untouched.

## Red evidence
**Classes that did not exist yet** (compile errors count here): all four new test classes failed to compile first, e.g.
```
AbsorberCurveTest.java:10: error: cannot find symbol  (AbsorberCurve)
AbsorptionPerformanceTest.java:19: error: package SoftCapAbsorption does not exist
```
**The functional seam** (the class existed, the marker did not), after the two new classes were in place:
```
theAbsorptionModelIsAFunctionalSeamThatFitsWhereTheDiffusionTakesAFunction() -> AssertionFailedError: expected: <true> but was: <false>
```
**Behaviour tests**, shown red with mutants after implementing (each reverted; `git status` shows no change to `DiffusionJob`):
- M1, `DiffusionJob` puts emissions into the persistent pool (the `r = 0` pin):
```
overManyIntervalsWithEmissionsSpreadAndAbsorptionPersistentStaysExactlyZero() -> interval 0, chunk 1,-4 ==> expected: <0.0> but was: <1.3375541478436141>
emissionsArriveOnlyInTheAbsorbablePool() -> expected: <7.5> but was: <0.0>
emissionAboveTheCapacityGrowsByTheDifference...() -> interval 1 ==> expected: <1.7357588823428847> but was: <3.0>
emissionAtOrBelowTheCapacityIsAbsorbedEveryInterval() -> emission 0.6321205588285577, interval 1 ==> expected: <0.0> but was: <0.6321205588285577>
```
  (the mutant also broke 4 older tests, which is expected: it removes the absorbable pool's emission input.)
- M2, the exponent without `k` (`expm1(-n)`):
```
atNEqualsKItGivesAboutSixtyThreePercentOfTheCap() -> expected: <1.2642411176571153> but was: <2.0>
everyExtraAbsorberAddsAlittleWhileBelowTheCap() -> n=39: 2.0 is not above 2.0
```
- M3, the last type skipped in the sum:
```
theCapacityIsTheSumOfEachCurve() -> expected: <1.698549427577124> but was: <1.6852446702726562>
theOrderOfTheTypesDoesNotChangeTheResultForTheSameCountsById() -> expected: <1.816429614009813> but was: <0.2489571456164766>
theResultIsFiniteAndNotNegativeEvenForHugeCounts() -> expected: <3.75> but was: <3.5>
```
Honest note: the other new tests (invalid `cap`/`k`, negative counts, duplicate ids, `indexOf`, empty table, the anti-spam shape, the one-chunk `DiffusionStep` cases) went red only as compile errors, because their classes did not exist; I did not mutate each of them.

## Concerns
1. **Brief test 3, second clause is false for the placeholder curves.** "Going from `k` to `10k` leaves adds less than adding `k` grass to `k` leaves": from `k = 64` to `640` leaves adds `2.0 (e^-1 - e^-10) = 0.736`, while `k` grass (cap 0.5, k 128, so n = 64 is half a `k`) adds `0.5 (1 - e^-0.5) = 0.197`. Even `k` crops (cap 1.0, k 64) add 0.632, still less than 0.736. So no placeholder type beats the extra leaves in that exact comparison. I kept the true first clause (9k more leaves add 0.736, less than the first `k`, 1.264) and replaced the second by the stronger version of the same idea that does hold: with 10k leaves already, **90k more leaves add under 0.001, while k crops add 0.632**, so diversity beats spam. If Heimdall meant a different comparison, tell me.
2. `AbsorptionStepTest`'s "E at or below C" case cannot read `absorbed` from `ChunkIntervalStats`: with no other pollution the chunk ends at 0 and is pruned, which also removes its stats. The test checks local is 0 (the chunk is pruned or empty), and `absorbed == min(E, C)` is checked in the `E > C` case where the chunk survives.
3. The `r = 0` test runs 150 intervals with spread on and decay on; it asserts persistent is exactly 0.0 in every chunk after every interval (the decay acts on persistent, so a leak there would show), and that the run really emitted (over 1,000 PU) and spread (over 10 chunks).
