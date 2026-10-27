# 030 review (Heimdall): APPROVED

## What I verified myself (in this worktree)

| Run | Result | GameTests | Test phase | Class-load check |
|---|---|---|---|---|
| `clean build runGameTestServer --rerun-tasks --no-build-cache` | exit 0 | 67 passed | 43.9 s | 23,807 classes, none from `mod.client` or `net.minecraft.client` (3,985 KB) |
| `runGameTestServer` | exit 0 | 67 passed | 35.2 s | passed |
| `runGameTestServer --configuration-cache` | exit 0 | 67 passed | 35.0 s | passed; "Reusing configuration cache", "Configuration cache entry reused" |

- JUnit XML: core 58 and mod 175, 0 failures, 0 errors.
- No `mod/run/gametest/ops.json` exists after the runs.
- No test adds a player to the player list: no `placeNewPlayer`, `getPlayerList().op`, `makeMockServerPlayer…` or `addNewPlayer` in `OverlayGameTests`, which uses a `FakePlayer` subclass through the sender's seams.
- **Sender cost** (three runs): 0 subscribers 0.019–0.026 µs; 1 subscriber 1.0–1.1 µs; 8 subscribers 6.7–7.1 µs per interval = 0.00035 ms/tick. That is far under the 0.01 ms/tick target.

## Ownership
- `mod/command/`: exactly the allowed three. New `OverlayCommand.java`, one `.then(OverlayCommand.node())` line in `PollutionCommands`, and `RatesText.line` made `public`.
- `PollutionManager`: two `OverlaySender.afterInterval(level)` calls only, after a committed tick and after a `RAN` step.
- `mod/build.gradle`: the `-Xlog` argument plus the delete and check.
- `README.md`: one section.
- `neoforge.mods.toml`: unchanged, as step 0 found.
- New `overlay/**` and `client/**` packages, `ClientIsolationTest` with its fixtures, a test-only `net.minecraft.client.Minecraft` stand-in for the fixtures (`mod:test` has no Minecraft; same pattern as the existing `Level` stand-in), and the overlay unit tests and GameTests.
- The class-load log is git-ignored via `run/`.

## Mutation check (scratch copies on tmpfs; the worktree was not touched)

**X:** the interval path ignores permission, and an unknown version is not skipped. 3 GameTests fail:
- `aLevelZeroPlayerWithTheOverlayOnGetsNothingBuilt` ("level 0 receives nothing and nothing is built")
- `aDeoppedPlayerStopsReceivingAtOnce` ("only the interval before the de-op, got 2")
- `anUnknownVersionDecodesToUnsupportedAndStopsExactlyAtItsEnd` ("the reader stopped exactly at the end of the payload")

**Y:** the subscription is ignored (builds for every permitted candidate), and `overlay on` does not send at once. 2 GameTests fail:
- `withTheOverlayOffNothingIsBuiltOrSent` ("no payload was built")
- `overlayOnAndOffToggleTheExecutingPlayerAndOnSendsOnceAtOnce` ("exactly one payload straight away")

Your own reds cover the ArchUnit rule (`rogue()`), the runtime check (method reference) and the positive control (wrong class name).

## Pass 1: spec compliance
- **Binding decisions 1–5:**
  - non-optional channel;
  - server-to-client only;
  - level-2 toggle, per player, in memory, default off, cleared on logout and server stop;
  - the payload is built only for a subscribed player holding level 2 now;
  - protocol version with an exact skip to `unsupported`, and `no data` for missing, stale, other-chunk and unsupported data.
- **Step-0 amendments:**
  - A: length-prefixed body;
  - B: install/reset guarded and test-only;
  - C: immediate send on `on`;
  - D: the lambda comment;
  - E: configuration cache, verified above;
  - F: log size reported and git-ignored.
- **Labels:** `pressure: 0 (until task 016)` and `aggressive: n/a (until task 016)`; `isAggressive` is never called.
- **Reuse:** formatting comes from `PollutionText` and `RatesText`, with no copy.

## Your concerns, decided
- **(a) `ClientOverlay` and `ClientOverlayHud` have no automated test.** Accepted.
  - Their logic sits in `OverlayDisplay` (plain, unit-tested); the two client classes are thin adapters.
  - Loading them in a test would defeat the dedicated-server check.
  - The user's manual F3 check covers them.
- **(b) Real join, refusal of a client without the mod, and a real dedicated server.** Manual, as briefed.
  - The pack smoke covers the dedicated server once task 022 adds our mod.
  - The user's checks are listed at the end of this review.
- **(c) Rescan max above 0.1 ms: measurement noise, no change.** Same machine, same session:

  | Variant | Rescan mean | Rescan max |
  |---|---|---|
  | `main`, tmpfs | 59.1–61.9 µs | 93.0–94.4 µs |
  | 030 without the flag, tmpfs | 59.4–60.1 µs | 100.9–101.9 µs |
  | 030 with the flag, tmpfs | 60.7–66.9 µs | 113.9 µs, then 417.5 µs |
  | 030 worktree, btrfs | 64.5–68.4 µs | 105.9–149.3 µs |

  - The mean is stable at about 60 µs, under the 70 µs line set in the 011 review.
  - Single-tick maxima include GC and JIT pauses (one run printed 417.5 µs). The rescan code is unchanged since 011.
  - `RESCAN_SECTIONS_PER_TICK` stays 3.
  - Follow-up for the P4 performance run: report p99 instead of max in `measureTheEngineCost`, so noise stops looking like a regression.
- **(d) Test phase 35–44 s, up from about 11–13 s: not caused by the new tests, and not by the flag on its own.**
  - The slow part is in 011's batches. In `defaultBatch` all 63 tests, including 030's, finish within about 1 s; `emitters_furnace`, `emitters_measure` and `emitters_nonticking` take about 7, 9 and 15 s in the worktree.
  - The same trees on tmpfs:

    | Tree on tmpfs | Test phase |
    |---|---|
    | `main` (53 tests) | 2.1 s, twice |
    | 030 without the flag | 3.3–3.4 s |
    | 030 with the flag | 3.3–3.4 s |

  - So the extra 14 tests cost about 1.2 s, and the flag costs nothing measurable on tmpfs.
  - The time goes to disk I/O in the btrfs worktree, as those batches force and generate far chunks and save them. 011's own worktree runs were already 11–13 s against about 2 s on tmpfs.
  - I could not isolate whether `-Xlog` writing about 4 MB to btrfs adds the rest (from about 13 s to 35 s), because I keep temporary trees on tmpfs.
  - CI is unaffected in substance. Non-blocking.
  - Follow-up (Heimdall, low priority): if local runs feel slow, try `-Xlog:…:file=` with a path on tmpfs on Linux, or write the log only when a Gradle property is set and run the check in CI only. Decide after one measurement on btrfs with the flag removed.

## Pass 2: quality, minor (no action required)
- `OverlaySender` repeats the default players lambda and sink in the field initialisers and in `reset()`. Two tiny duplicates; fold them into constants the next time the class is touched.
- The players default maps `level.players()` through a cast it does not need, which allocates a list per call. That only happens when someone is subscribed, so it is harmless.

## Manual checks for the user (at acceptance)
1. In `runClient` as an op, `/pollution overlay on` makes F3 show a `Pollution` section. It changes after `/pollution set 20` and `/pollution step`.
2. `/pollution overlay off`, or walking into another chunk, shows `no data` (immediately after `off`'s staleness window; at the next interval for a chunk change).
3. A client without the mod is refused when joining a server with it.

Committed by Heimdall.
