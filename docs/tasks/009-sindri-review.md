# 009 review (Heimdall): CHANGES REQUESTED (small)

## What I verified myself

- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache` exits 0: "All 28 required tests passed". JUnit XML totals are 151 tests, 0 failures, 0 errors.
- **File ownership:** `git status` lists only new files: `mod/.../mod/command/**` (6 classes), `mod/src/test/.../mod/command/**` (3 tests), `DebugCommandGameTests.java`, and the two task docs. No existing file was modified.
- **Mutation check** in a scratch copy (the worktree was not touched). The GameTests other than the mid-interval one never had a red run, so I checked two of them:
  - **Mutant A:** `.requires(hasPermission(2))` removed. `aPermissionLevelZeroSourceCannotRunAnySubcommand` fails ("must be refused at level 0: pollution get").
  - **Mutant B:** `get` ignores the paused dimension. `pausedDimensionIsRefusedAndShownEverywhere` fails.
  - Run result: "2 required tests failed", exit 1. Both tests catch what they claim to.

## Pass 1: spec compliance

The brief is met, apart from the items below:
- Every subcommand is there, all at permission level 2.
- The interval write rule is kept: `set`/`clear` call `abandon()`, then write, then `setDirty()` in the same tick, and `step` goes through `stepNow`.
- Paused records are refused for writes and shown in `get`/`rates`/`pressure`.
- `tune` uses `RuntimeOverrides` and `ConfigRegistry.KEYS` only, with no second key list.
- The gate-bypass note is on every reply from `set`, `clear` and `step`.
- Both deferrals are recorded with no placeholder: per-neighbour inflow, and the pending-emission line.
- The command is registered with `@EventBusSubscriber`, so `PollutionCraftMod` is untouched.

## Required changes

1. **`get` and `rates` ignore a paused world record.**
   - Where: `PollutionCommandHandlers.java:41-61`. Only the dimension is checked.
   - Why it matters: with an unreadable world record the whole simulation is frozen (007 ruling), yet `get`/`rates` show numbers with no hint of it.
   - Fix: when `PollutionManager.world(server).isPreserved()`, put `PollutionText.pausedWorld()` as the first line and keep the field values below it (the field itself is readable).
   - A paused dimension stays as now: paused line, no numbers.
   - Test: extend `pausedWorldRecordIsRefusedAndShown` so that `get` and `rates` contain the paused-world line.
2. **A hard-coded key count will break when 010 merges.**
   - Where: `TuneCommandTest.java:103`, `assertEquals(7, lines.size())`.
   - Why it matters: 010 adds `maxEmittersPerChunk`.
   - Fix: assert `ConfigRegistry.KEYS.size()` instead. While you are there, also assert that the overridden line shows the value (`spreadFraction: 0.1 (overridden)`), so the effective value is checked and not only the marker.
3. **Dead code.**
   - `CommandLimits.MAX_CHUNKS_PER_SQUARE` (`CommandLimits.java:13`) is used only by a test that checks the constant against itself (`CommandLimitsTest.java:47-50`). Remove both.
   - `TuneCommand.Reply.failed()` (`TuneCommand.java:13-15`) duplicates `!ok()` and is used only by tests. Remove it, and have the tests use `ok()`.
4. **The `dominantSource` decode is hand-copied from `BlockPos` and never checked against it.**
   - Where: `PollutionText.java:68-76`. A wrong decode would mislead about the siege target.
   - Fix: keep the decode where it is (plain class, as briefed). Add a GameTest that sets `dominantSourcePos = new BlockPos(100, -60, -20).asLong()` with a rate of 2.5 on a far chunk and asserts that `get` shows `100 -60 -20`. The negative y covers sign extension.

## Pass 2: quality

- **Structure:** clear split. Plain helpers are unit-tested; the Minecraft side is thin and covered by GameTests.
- **Limits:** all bounds are in one class. Brigadier argument types are unbounded, so every limit is checked in the handlers, and the GameTests cover that path.
- **Paused-record tests:** they swap `SavedData` in shared storage, but each runs synchronously in one call and restores it in `finally`, so no other test can see the swap. Acceptable.
- **Scale:** at most 289 chunks per command, and `step` is capped at 1000. No chunk loading.
- **Decimal display (minor, no action):** `PollutionText.number` rounds to 6 decimals, so values below 5e-7 PU show as `0`. That is fine for a debug tool.

## Decisions

- **Reflection in `RatesText` (question 1):** accepted for this merge.
  - It depends on the accessor-name convention that `ConfigRegistryTest` already enforces, and it fails loudly if that convention breaks.
  - `ServerConfig` belongs to 010 in this run, so it does not move now.
  - After 010 merges, the 009 follow-up (the same one that adds the `pending` line) adds a public `ServerConfig.value(ConfigKey)` that returns the effective value, and removes the reflection. Sindri will own `ServerConfig` for that follow-up only.
- **Manual `runServer` transcript (question 2):** not required for my acceptance.
  - The GameTests run every subcommand through the real server dispatcher with real reply text, including the permission refusals.
  - Do not accept the EULA on the user's behalf.
  - I recommend the user run one smoke test by hand when they accept the work for project-owner (`/pollution pressure`, `get`, `set 20`, `step`, `tune` as an op). That is an acceptance step, not a blocker for merging.

Re-review: once items 1–4 are fixed, send back the fresh `clean build runGameTestServer` output and the red line for each new or changed test.

## Re-review (round 2): APPROVED

### What I verified myself
- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache` exits 0 with "All 29 required tests passed". JUnit XML: core 35 tests, mod 115, 0 failures, 0 errors.
- Ownership is unchanged: new files only, all under `mod/command/**`, its tests and `DebugCommandGameTests.java`.
- **The gap in the TuneCommandTest mutation is closed.** In a scratch copy I changed `RatesText.OVERRIDDEN` to `"(override)"` and ran `:mod:test --tests '*TuneCommandTest*'`. Result: `TuneCommandTest > overriddenMarkerAppearsExactlyOnOverriddenValues() FAILED`, 8 tests, 1 failed. The test asserts `ConfigRegistry.KEYS.size()` and the exact line `spreadFraction: 0.1 (overridden)`, so it checks the effective value as well as the marker.
- **Combined with 010:** a scratch tree of the 010 worktree plus these files passes `clean build runGameTestServer` with exit 0 and "All 37 required tests passed". The two tasks can be merged in either order with no textual conflicts. 010 goes first, as planned.

### The four items
1. **Paused world notice:** `withWorldNotice` puts `paused: unreadable world record` first in `get`/`rates`. The field values stay below it, and a paused dimension still refuses. `pausedWorldRecordIsRefusedAndShown` covers it, and your mutant fails it.
2. **Key count and marker:** fixed and verified above.
3. **Dead code:** `MAX_CHUNKS_PER_SQUARE` and `Reply.failed()` are gone, with no remaining references.
4. **Decode check:** `dominantSourceIsDecodedLikeBlockPosIncludingNegativeY` compares against `BlockPos.asLong()` with y = −60, and the decode is correct.

### Minor, before I commit (report only, no code)
`009-sindri-report.md` still contains first-round text that the fixes have made untrue: "21 new unit tests / 9 GameTests", "not cross-checked against `BlockPos` in a GameTest", the `ServerConfig.value` request (decided in round 1), and the console-transcript open item (decided in round 1). Update those lines so the committed report matches the code.

### Follow-up (after 010 merges; Sindri; `ServerConfig` owned for this follow-up only)
- Add a `pending: <PU>` line to `get` from `DimensionPollution.pendingEmission(long)`.
- Add a public `ServerConfig.value(ConfigKey)` and remove the reflection in `RatesText`.
- Per-neighbour inflow in `rates` stays deferred until a `ChunkIntervalStats` change is planned.
- The user's manual smoke test as an op is part of project-owner acceptance, not a merge blocker.

## Final: committed by Heimdall

- **Fresh run I did myself:** a fresh world, then `clean build runGameTestServer --rerun-tasks --no-build-cache` exits 0 with "All 29 required tests passed" (18:45:30). JUnit XML: core 35 and mod 115, 0 failures, 0 errors.
- **Report:** updated. Two bullets under "Decisions and deviations" are left over from round 1: the `ServerConfig.value` request, and "not cross-checked against `BlockPos`". The sections "Review round 1" (item 4) and "Decisions on earlier open items" supersede them. That is history only and accepted as is.
