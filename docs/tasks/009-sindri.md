# 009 (Sindri): Debug commands

Read `docs/design.md` first. This brief carries the rest, including user decisions that are binding. Engineers do not commit; Heimdall reviews and commits.

**Worktree:** `~/workspace/minecraft/pollutioncraft-009`, branch `feature/009-debug-commands` (from `origin/main` with 005, 007, 008 merged).

**Runs in parallel with 010 (Brokkr, emitter API).** You build only against code already on `main`. You add new files only; every existing production file is read-only to you (see "Files owned"). The commands must work from the first merge against whatever data exists: before 010, `emittedLastInterval` and `dominantSource` are always 0/none, and that is fine.

## Goal

Let a developer see and change pollution generation, spread and absorption in a running world without editing files. Acceptance: plan task 009 behaviour table, and the operator visibility of paused (unreadable) records required by the 007 review.

## Design references

- Architecture 3.2 (data model, `lastIntervalStats`, downgrade protection), 3.3 (interval step), 3.5 (commands, permission level 2), 4 (security: mutating commands need level 2; config values are bounded).
- Plan task 009 (`plan/tasks/009-debug-commands.md` in the docs repo), task 005 (`ChunkIntervalStats`), task 008 (`RuntimeOverrides`).
- Accepted code on `main`: `PollutionManager` (`world`, `dimension`, `stepNow`, `StepResult`), `DimensionPollution`, `WorldPollution`, `WorldRecord`, `ChunkPollution`, `ChunkIntervalStats`, `IntervalScheduler.abandon()`, `ServerConfig`, `RuntimeOverrides`, `ConfigRegistry`.

## Binding rules (user decisions; do not reinterpret)

1. **Interval write rule (2026-10-22).** An interval snapshots the field on its first tick and writes on its last; a field write in between is lost. Every command that changes the field goes through `stepNow` or `abandon()`, or is queued until the interval ends.
   - `step` uses `PollutionManager.stepNow` (the same code path as the scheduler).
   - `set` and `clear` first call `dimension.scheduler().abandon()`, then write `dimension.field()`, then `dimension.setDirty()`, all in the same server tick. This is the pattern 007's GameTests already use. Once 010 merges, `abandon()` also re-queues emissions drained into the dropped interval; you get that for free and need not change anything.
2. **`isPreserved()` means refuse writes.** `set`, `clear` and `step` refuse when the world record or the executor's dimension is preserved and say why. Never call `field()`/`record()` on a preserved record (they throw by design).
3. **Paused states must be visible (007 review).** Use `PollutionManager.StepResult` as the vocabulary:
   - `PAUSED_WORLD_RECORD` → `paused: unreadable world record`
   - `PAUSED_DIMENSION_RECORD` → `paused: unreadable pollution field in <dimension id>`
   - `get`, `rates` and `pressure` print the relevant paused line instead of numbers. `pressure` is the status command: it also lists every paused dimension of the server (iterate `server.getAllLevels()`, check `PollutionManager.dimension(level).isPreserved()`).
   - `step` maps its `StepResult` to the same text.
4. **Builds on 008's `RuntimeOverrides`.** `tune` uses `PollutionCraftMod.config().overrides()` (`set`, `clear`, `clearAll`, `list`, `get`) and keys from `ConfigRegistry.KEYS`. No second key list; a key added later (010 adds `maxEmittersPerChunk`) becomes tunable with no change here. `tune` never writes the config file.

## Decisions I am making for this task (Heimdall)

- **Registration:** a game-bus `@EventBusSubscriber(modid = PollutionCraftMod.MOD_ID)` class listening to `RegisterCommandsEvent`, in your own package. `PollutionCraftMod` is not touched.
- **Chunk argument:** optional `<chunkX> <chunkZ>` integers (chunk coordinates, not block coordinates). Default: the executor's chunk (`BlockPos.containing(source.getPosition())`, `>> 4`). Dimension: always the executor's level.
- **Bounds:** `set` amount finite, ≥ 0, ≤ 1e9 PU. `radius` 0–8 (a square of at most 17×17 = 289 chunks). `step n` 1–1000, default 1. All limits are named constants in one class.
- **`set` semantics:** sets `absorbable` of every chunk in the square to the amount; `persistent` is untouched. `clear` removes the chunk entries (both pools). Neither loads or generates chunks; they only touch the field. `clear` does not discard pending emissions (after 010 there may be some) and its reply says so.
- **Gate bypass:** `set`, `clear` and `step` run regardless of the "≥ 1 player online" gate and every reply says so (e.g. `(ran without the player-online gate)`).
- **`rates` per neighbour:** `ChunkIntervalStats` (accepted in 005) stores totals only. Show `generated` (= `emittedLastInterval`), `spreadIn` total, `spreadOut` total and per neighbour (`spreadOut / 4` to each of −x, +x, −z, +z; the split is exactly equal by design), `absorbed`, `decayed`. Per-neighbour *inflow* is **deferred**: it needs a change to `ChunkIntervalStats`, which is not yours. Note it in your report; do not work around it.
- **Effective rates in `rates`:** every `ConfigRegistry.KEYS` entry with its effective value from `ServerConfig`; a value with an active override is marked `(overridden)`. Same marker anywhere else an overridden value is shown.
- **`tune` replies** say the value applies from the next interval (the scheduler reads settings once per interval start).
- **Pending emissions are deferred:** 010 adds a read accessor for a chunk's pending (buffered) emission. Do **not** reference it or stub it in code. After 010 merges, a small follow-up adds a `pending` line to `get`. Leave no placeholder in the output.
- **Testability:** mod's unit tests run without Minecraft on the classpath (there is a stand-in `Level` in `mod/src/test`). So keep bounds, validation, `tune` handling and text formatting in plain classes with no Minecraft or Brigadier types (they may use `ServerConfig`, `RuntimeOverrides`, `ConfigRegistry`, `ChunkPollution`, `ChunkIntervalStats`, `WorldRecord`), unit-tested. The Brigadier tree and world access are thin and covered by GameTests.

## Files owned (exclusive)

- New package `mod/src/main/java/io/github/pollutioncraft/mod/command/**` (registration, handlers, the plain helper classes).
- New tests `mod/src/test/java/io/github/pollutioncraft/mod/command/**`.
- New GameTest `mod/src/gametest/java/io/github/pollutioncraft/gametest/DebugCommandGameTests.java`.

**Read-only to you (owned by 010 during this run, or already accepted):** `PollutionManager`, `PollutionApiImpl`, `DimensionPollution`, `WorldPollution`, `PollutionCraftMod`, everything in `mod/config/**`, everything in `core/**` and `api/**`, the other GameTest files, Gradle files. Use only their public methods. If you need a method that does not exist, stop and ask Heimdall; do not add it.

## Public members you may rely on (frozen for this run)

`PollutionManager.world(MinecraftServer)`, `PollutionManager.dimension(ServerLevel)`, `PollutionManager.stepNow(ServerLevel, int)` → `StepResult { RAN, PAUSED_WORLD_RECORD, PAUSED_DIMENSION_RECORD }`; `DimensionPollution.isPreserved()`, `field()`, `scheduler()`, `setDirty()`; `IntervalScheduler.abandon()`; `WorldPollution.isPreserved()`, `record()`; `WorldRecord.activeTicks()`, `grossEmitted()`, `pressure()` (reads 0 until task 016; show it as is); `ChunkPollution` public fields and `local()`; `PollutionField` `get`/`getOrCreate`/`remove`; `ChunkKey.pack/x/z`; `PollutionCraftMod.config()`, `ServerConfig` accessors and `overrides()`; `ConfigRegistry.KEYS`/`find`.

## What to build

| Command (all need permission level 2) | Effect |
| --- | --- |
| `/pollution get [<chunkX> <chunkZ>]` | `absorbable`, `persistent`, `dominantSource` (block pos and rate, or `none`), `emittedLastInterval`; or the paused line |
| `/pollution rates [<chunkX> <chunkZ>]` | Last interval for the chunk (as above) plus the effective rates in force, overrides marked |
| `/pollution pressure` | `activeTicks`, `grossEmitted`, `pressure`, and every paused record |
| `/pollution set <amount> [<chunkX> <chunkZ> [radius]]` | Sets `absorbable`, bounded as above |
| `/pollution clear [<chunkX> <chunkZ> [radius]]` | Removes both pools |
| `/pollution step [n]` | Runs n intervals now via `stepNow` |
| `/pollution tune <key> <value>` | In-memory override via `RuntimeOverrides.set`; its refusal reason is the reply |
| `/pollution tune` / `/pollution tune reset [key]` | Lists active overrides / clears one or all |

- `<key>` has suggestions from `ConfigRegistry.KEYS`. An unknown key is refused with `RuntimeOverrides`' message.
- A chunk with no stored entry reads as zeros, not an error.
- Errors use `sendFailure`; success uses `sendSuccess` (broadcast to ops off for read commands, on for mutating ones).

## Tests to write first

Write each, run it, see it fail for the right reason, then implement. In your report, paste the failing output line for each.

1. **Unit:** bounds for each command: amount NaN, negative, infinite and > 1e9 refused; 0 and 1e9 accepted; radius −1 and 9 refused; `n` 0 and 1001 refused, 1 and 1000 accepted.
2. **Unit:** `tune` refuses what config validation refuses: out of bounds, a non-whole value for an INTEGER key, unknown key, and `spreadFraction = 0.25` (`4 × spreadFraction ≥ 1`). A refused `tune` leaves overrides unchanged.
3. **Unit:** override precedence and reset: effective value = override over file value; `tune reset <key>` restores the file value; `tune reset` clears all; listing is sorted; `(overridden)` appears exactly on overridden values.
4. **Unit:** paused text for each `StepResult` value; `get` formatting for a chunk with no dominant source shows `none`. (If `mod:test` cannot compile against `PollutionManager.StepResult` because Minecraft is not on that classpath, test the paused text in a GameTest instead and say so in the report. Do not add Minecraft to `mod:test`.)
5. **GameTest:** `set` a far-away chunk above `spreadThreshold`, `step 1`, then `get` on a neighbour shows `absorbable > 0` (spread happened); `clear` with radius 1 empties all nine chunks. Run the commands through `server.getCommands().performPrefixedCommand(...)` with a capturing `CommandSource`, and assert on both the field and the reply text.
6. **GameTest:** `set` mid-interval: tick half an interval, run `set`, tick to the interval end; the value set is not overwritten by the abandoned interval's apply.
7. **GameTest:** a source with permission level 0 cannot run any subcommand (each fails; the field and overrides are unchanged).
8. **GameTest:** `tune spreadFraction 0.1` then the server config file on disk is byte-identical to before; `tune reset` restores the effective value.

## Commands to run before handing back

- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache` — exit 0; report the "All N required tests passed" line and the unit test count.
- `./gradlew runServer` by hand: run each command once as an op in the console, paste the replies.

## Report (`docs/tasks/009-sindri-report.md`)

Status, files added, red→green evidence per test, fresh command output, the console transcript, deferred items (per-neighbour inflow, pending line), concerns. Do not commit.

## Out of scope

The F3 overlay (030), a player-facing display, persisting tuned values, per-neighbour inflow stats, showing pending emissions (follow-up after 010), any change to production files outside `mod/command/**`.

If you need anything outside your files, or hit a design gap, ask Heimdall; do not decide it.
