# 007 review (Heimdall) — ACCEPTED (not committed yet)

What I verified myself: `clean build runGameTestServer --rerun-tasks --no-build-cache` exits 0, 48 unit/architecture tests pass, and "All 6 required tests passed".

Read: `DiffusionJob`, `IntervalScheduler`, `SimulationGate`, `WorldRecord`, `FieldCodec`/`EncodedField`, the `SavedData` classes, `PollutionManager` and `PollutionApiImpl`.
- The sliced and single-pass paths share one job, so they can't diverge.
- The field is untouched until `apply`, and an error abandons the interval cleanly.
- The gate is one named predicate.
- The codec is lossless (raw bits) and skips corrupt entries.
- The save is 43.9 KB at 2,500 chunks.
- No chunk loading.

## User decisions applied
- Concern 1 (interval write rule): binding on 009 and 010, recorded in their briefs.
- Concern 2: `emit` and `isAggressive` keep throwing until 010 and 016.
- Concern 3: the static `PollutionCraftMod.config()` stays; read it once at the scheduler boundary.
- Concern 5: the 0.122 ms/tick unit timing is accepted. Spark profiling, including the interval-start tick, comes later.

## For the 008 adoption (next change to these files)
1. `IntervalScheduler` fixes `intervalTicks` and `DiffusionConfig` in its constructor, and `DimensionPollution` builds it from the static `DEFAULTS`. Overrides would then never apply. Read `PollutionCraftMod.config()` **at the first tick of each interval** (pass `intervalTicks` and `diffusionConfig()` into `tick`/`stepNow`, or into the job start), so changes apply from the next interval as 008 specifies. Delete `PollutionDefaults`. Add the scheduler-level test (concern 7): an override set mid-interval doesn't change the running interval, but does change the next.
2. Absorption stays `key -> 0.0` until P2. Once 008 merges, use `defaultCapacity()` only if the 013 brief says so. Don't decide it here.

## Noted for other tasks
- `abandon()` drops any emissions already drained into the snapshot. Task 010 must say whether they are re-queued or knowingly lost; I'll add it to the 010 brief.
- A corrupt or unknown-version world record restarts `activeTicks` at 0, so pressure can go down. Architecture 3.2 already carries the "keep unreadable records and write them back" recommendation for the first release. It needs its own task before any tester release.
- `dominantSource` is still never cleared (task 010, as noted in 005).

## Re-review: downgrade protection — CHANGES NEEDED

What I verified myself: `clean build runGameTestServer --rerun-tasks --no-build-cache` exits 0, 48 unit tests pass and "All 12 required tests passed". The preserved-tag path itself is right:
- An unknown or missing version, or an invalid world record, is kept as the very tag that was read.
- `save` returns it, and nothing marks a preserved record dirty, so the file isn't even rewritten.
- A preserved world stops `activeTicks` and every dimension.
- A preserved dimension is skipped.
- `stepNow` refuses both.

### Required
1. **One reset path is left: a data file Minecraft can't parse.** In vanilla `DimensionDataStorage.readSavedData` (NeoForge 21.1.264 sources), an exception while reading the file (a truncated or corrupt `.dat` after a crash or a full disk, or any exception thrown from our `load`) is caught and logged, and `null` is returned. `computeIfAbsent` then builds a **fresh** `WorldPollution`. `PollutionManager.tick` marks it dirty on the next active tick, and the next autosave **overwrites the existing file with `activeTicks = 0`**. The same goes for a dimension's field. Fix: the factory's constructor must tell "no file" (new world, so start fresh) apart from "file exists but didn't load" (unreadable). For the second case, create the record in a preserved state that has no tag. It is never simulated, and it is never saved, e.g. `isDirty()` returns false, so the file on disk stays untouched. Log one error naming the file. Work out the data-file path from the level's storage folder (overworld `data/`, other dimensions under their own folder). Test first: write garbage bytes to `pollutioncraft_world.dat` (and to one dimension's `pollutioncraft_field.dat`), load, tick with a player online, force a save, and show the file bytes are unchanged and nothing counted.
2. **Preserved records must not hand out writable stand-ins.** `WorldPollution.record()` and `DimensionPollution.field()` return fresh in-memory objects for a preserved record. Task 010 (`addGrossEmitted`, emissions) or 009 (`/pollution set`) could write into them and silently do nothing. Make `record()` and `field()` throw `IllegalStateException` when the record is preserved. Readers check `isPreserved()` first: `getPressure` returns 0 and `getLocal` returns 0 for a preserved record, documented as "paused". Add a test that calling them on a preserved record throws.
3. **`stepNow` must report a refusal.** It currently returns silently, so 009 can't tell the operator why nothing happened. Return a result, e.g. an enum `RAN / PAUSED_WORLD_RECORD / PAUSED_DIMENSION_RECORD`, and test both refusals.

### Ruling: stopping the whole simulation on a preserved world record is right
The field and the pressure inputs must come from the same mod version. Running the field while pressure is frozen would mix versions once 016 makes aggression depend on pressure, and anything counted while the record is unreadable could never be merged back. Pausing is safe, reversible (upgrade again) and loses nothing. Operators need to see it, though: besides the load-time error, task 009's status output must show "paused: unreadable world record" (or which dimension). I'll add that to the 009 brief.

## Round 3 — ACCEPTED

What I verified myself: `clean build runGameTestServer --rerun-tasks --no-build-cache` exits 0, with 48 unit tests and "All 18 required tests passed".

How I checked that the garbage-file tests prove what they claim, using mutants in a scratch copy (the worktree was not touched):
- **Mutant 1:** the factory ignores the existing file and starts fresh. Both `unparsable*FileIsNeverOverwritten` tests fail.
- **Mutant 2:** a paused dimension may be marked dirty and saves a fresh field. `unparsableDimensionFileIsNeverOverwritten` fails on "the file must be byte-identical after a forced save". So the byte comparison, run after `IOUtilities.waitUntilIOWorkerComplete()`, really catches an overwrite.
- `missingFilesStartFreshAndAreWritten` is the positive control: the same storage setup does write files.

Code: the paused state blocks `setDirty`/`isDirty`. `save` throws for a paused record with no tag. `record()`/`field()` throw. Reads return 0. `stepNow` returns a `StepResult`. The data path comes from `DimensionType.getStorageFolder`, and `dataFilePathMatchesWhereMinecraftSaves` checks it.

Minor, no action: `world()`/`dimension()` build a `Path` and a factory on every call, i.e. every tick per dimension. That's negligible; revisit only if profiling says otherwise.
