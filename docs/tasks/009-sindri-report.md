# 009 (Sindri): report

**Status: DONE. Approved by Heimdall in round 2.**

## Files added (new files only; no production file touched)

- `mod/src/main/java/io/github/pollutioncraft/mod/command/`
  - `CommandLimits` (bounds, one place), `PollutionText` (reply text), `RatesText` (effective config lines, `(overridden)` marker), `TuneCommand` (tune logic): plain classes, no Minecraft or Brigadier types.
  - `PollutionCommandHandlers` (world access, interval write rule, paused refusal), `PollutionCommands` (Brigadier tree, `RegisterCommandsEvent`, permission level 2).
- `mod/src/test/java/.../mod/command/`: `CommandLimitsTest` (5), `TuneCommandTest` (8), `PollutionTextTest` (7) = 20 new unit tests.
- `mod/src/gametest/java/.../DebugCommandGameTests.java`: 10 GameTests.

## Final state

`clean build runGameTestServer --rerun-tasks --no-build-cache --no-daemon -q`: passes; `All 29 required tests passed :)` (19 earlier + 10 of mine); Heimdall's count: 35 core and 115 mod unit tests.

## Review round 1 (Heimdall's four items)

1. `get`/`rates` now put `paused: unreadable world record` first when the world record is unreadable (field values stay below). `pausedWorldRecordIsRefusedAndShown` checks both. Mutation (notice removed) fails it: `pollution get -> chunk 0, 0 ...`.
2. `TuneCommandTest` asserts `ConfigRegistry.KEYS.size()` and the line `spreadFraction: 0.1 (overridden)`. Mutation (marker text altered) made `:mod:test` fail (BUILD FAILED).
3. Removed `CommandLimits.MAX_CHUNKS_PER_SQUARE` with its test, and `TuneCommand.Reply.failed()` (tests use `ok()`).
4. New GameTest `dominantSourceIsDecodedLikeBlockPosIncludingNegativeY` compares with `new BlockPos(100, -60, -20).asLong()`. Mutation (y decoded without sign extension) fails it: `get reply: chunk 95000, 95000 ...`.
No `eula.txt` written; manual transcript dropped per Heimdall. Latest run: `All 29 required tests passed :)`, build exit clean (command above, `--rerun-tasks --no-build-cache`).

## Fresh results (round 1, superseded by the final state above)

`./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache --no-daemon -q`: build passes, and the GameTest log reads `All 28 required tests passed :)` (19 earlier plus my 9). Unit tests across modules: 151, 0 failures (21 are new).

## Red evidence

- Unit tests: written first; the first `:mod:test` run failed to compile with `error: cannot find symbol` in `CommandLimitsTest`/`TuneCommandTest`, because the classes did not exist. Green once implemented.
- GameTests: written together with the handlers, so there was no separate red run. Instead I did a mutation check: removing the `abandon()` call from `set` made `setMidIntervalIsNotOverwrittenByTheAbandonedInterval` fail with `the abandoned interval must not overwrite the set value, got 92.0`. Reverted. Honest note: the other GameTests had no deliberate red run.

## Brief tests, mapped

1. Bounds: `CommandLimitsTest`, plus GameTest `boundsAreRefusedWithoutChangingTheField`.
2. tune refusals: `TuneCommandTest` (out of bounds, non-whole INTEGER, unknown key, `spreadFraction 0.25`; overrides unchanged after refusal).
3. Precedence, reset, sorted list, exact `(overridden)` marker: `TuneCommandTest`.
4. Paused text and `none` dominant source: `PollutionTextTest`. The `StepResult` to text mapping lives in the handler (Minecraft side), so it is covered by the GameTests `pausedDimensionIsRefusedAndShownEverywhere` and `pausedWorldRecordIsRefusedAndShown` (they swap in an unreadable record in `finally`-restored storage), not in `mod:test`.
5. set, step 1, get on a neighbour, clear radius 1 (9 chunks): `setStepGetShowsSpreadAndClearEmptiesTheSquare` (asserts field and reply text).
6. Mid-interval set: `setMidIntervalIsNotOverwrittenByTheAbandonedInterval`.
7. Level 0: `aPermissionLevelZeroSourceCannotRunAnySubcommand` (10 subcommand forms; field, overrides, `activeTicks` unchanged).
8. Config file: `tuneLeavesTheConfigFileByteIdenticalAndResetRestoresTheValue`.

## Decisions and deviations

- `RatesText` reads each effective value by reflecting on the same-named public `ServerConfig` accessor (the documented, test-checked convention). `ServerConfig` has no by-name getter and I may not add one. A `ServerConfig.value(String)` would remove the reflection; it is a small request for Heimdall.
- The `dominantSource` block position is unpacked in `PollutionText` with the `BlockPos.asLong` bit layout (x 26 bits at 38, z 26 bits at 12, y 12 bits at 0), to keep the text unit-testable. Tested with a hand-packed value; not cross-checked against `BlockPos` in a GameTest.
- `set` and `clear` mention when an interval in flight was dropped (`inFlight()` read before `abandon()`).
- The gametest server keeps the server config in `run/gametest/config/`, not in the world; the test looks in both places.
- `set` with amount 0 creates zero-valued entries (pruned by the next interval).

## Decisions on earlier open items

- Reflection in `RatesText`: accepted for this merge. Follow-up after 010 merges: public `ServerConfig.value(ConfigKey)` replaces it (Sindri owns `ServerConfig` for that follow-up only).
- Manual `runServer` transcript: not required; the GameTests run every subcommand through the real dispatcher.

## Follow-ups (after 010 is on main; not started)

- `pending` line in `get` from 010's accessor.
- `ServerConfig.value(ConfigKey)` to drop the reflection.
- Deferred by design: per-neighbour inflow in `rates` (needs a `ChunkIntervalStats` change). `pressure` reads 0 until task 016.
