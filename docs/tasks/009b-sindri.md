# 009b (Sindri): `pending` line and `ServerConfig.value`

Read `docs/design.md` first. This is the follow-up Odin accepted with task 009 (plan task 009, "Follow-ups" 1 and 2). Engineers do not commit; Heimdall reviews and commits.

**Worktree:** `~/workspace/minecraft/pollutioncraft-009b`, branch `feature/009-followup-pending-and-value` (from `origin/main` with 009 and 010 merged).

**Runs in parallel with 011 (Brokkr: block emitter engine).** File ownership is disjoint; see "Files owned".

## Goal

1. `/pollution get` shows what is buffered for the chunk and not yet applied: a `pending: <PU>` line from `DimensionPollution.pendingEmission(long)` (task 010).
2. Remove the reflection in `RatesText`: add a public `ServerConfig.value(ConfigKey)` that returns the effective value (override over file value) of any registry key, and use it.

## Design references

- Plan task 009 (acceptance note and follow-ups), plan task 010 (`pendingEmission` is the buffered, not yet drained total: a running sum, 0 for a paused dimension).
- Code on `main`: `mod/command/**` (your 009 code), `ServerConfig`, `RuntimeOverrides`, `ConfigRegistry`, `DimensionPollution.pendingEmission`.

## Binding conditions

1. **Red first, quoted.** Same rule as 011. For every new or changed test, run it before the implementation exists, see it fail for the right reason, and paste the failing line in your report. A compile error counts as red only when the symbol under test does not exist yet (for example `value(ConfigKey)` before you add it). A test without its red line is grounds for rejection.
2. **No second key list.** `value(ConfigKey)` works for every key in `ConfigRegistry.KEYS`, including keys added later, with no change to it.
3. **Existing `ServerConfig` accessors keep their names, types and meaning.** 011 calls `emissionMultiplier()` while you work.

## What to build

1. **`ServerConfig.value(ConfigKey key)`** (public, returns `double`):
   - The effective value: the override if one is active, else the validated file value.
   - It must be the same source the named accessors read, with no second path. If it fits naturally, the named accessors may delegate to it, but that is optional; do not churn them.
   - A key that is not in the registry is refused with `IllegalArgumentException`.
2. **`RatesText`:** read values through `config.value(key)`. Delete the reflection, including the `java.lang.reflect` import and the `ReflectiveOperationException` handling.
3. **`get` shows the pending amount:**
   - Add a `pending: <PU>` line after `emittedLastInterval`, using `PollutionText.number`.
   - The handler reads `dimension.pendingEmission(ChunkKey.pack(x, z))` only when the dimension is not paused. A paused dimension is already refused with its paused line; keep that.
   - A world-paused `get` keeps its notice first, as now.
   - `PollutionText.get` takes the pending amount as a parameter, so it stays free of Minecraft types.

## Files owned (exclusive)

- `mod/src/main/java/io/github/pollutioncraft/mod/config/ServerConfig.java`: **for this follow-up only**, to add `value(ConfigKey)`, plus optional delegation by the existing accessors.
- `mod/src/main/java/io/github/pollutioncraft/mod/command/**` (`PollutionText`, `RatesText`, `PollutionCommandHandlers` as needed).
- Tests: `mod/src/test/java/io/github/pollutioncraft/mod/command/**`, `mod/src/test/java/io/github/pollutioncraft/mod/config/ServerConfigTest.java` (new; or extend `ConfigRegistryTest.java` if the test fits better there; say which), `mod/src/gametest/java/io/github/pollutioncraft/gametest/DebugCommandGameTests.java`.
- `docs/tasks/009b-sindri-report.md`.

**Not yours** (011 or already accepted): `PollutionManager.java`, `PollutionCraftMod.java`, `DimensionPollution.java`, `ConfigRegistry.java`, `ConfigKey.java`, `ConfigValues.java`, `RuntimeOverrides.java`, `ServerConfigSpec.java`, anything under `mod/emitter/**` or `data/**`, `EmitterGameTests.java`, the other GameTest files, `core/**`, `api/**`, Gradle files.

## Tests to write first (each with its quoted red)

1. **Unit, `value(ConfigKey)`:** for **every** key in `ConfigRegistry.KEYS`, `value(key)` equals its named accessor with no override, and again after a file reload with non-default values. Reflection over accessor names is fine **in the test**; it already enforces the convention. With an override active, `value(key)` returns the override, and after `clear` it returns the file value. A key not in the registry throws `IllegalArgumentException`.
2. **Unit, `RatesText`:** the effective-rates lines show the override value with `(overridden)`, and the file value after reset (keep the existing `TuneCommandTest` assertions green). Show the red by making `effectiveRates` read through `value` before `value` exists (compile red), or by a failing assertion. Quote it either way.
3. **Unit, `PollutionText.get`:** a `pending: 2.5` line appears in the right place; `pending: 0` for zero.
4. **GameTest:**
   - Call `PollutionManager.api().emit(level, pos, 3.0)` into a far chunk, then run `/pollution get <chunk>`: the reply shows `pending: 3`.
   - Tick one interval (`PollutionManager.tick(server, 1)` × `spreadIntervalTicks`, after `abandon()` and clearing the square, as the existing tests do): `get` shows `pending: 0` and `absorbable: 3`.
   - Clean up the chunk and abandon at the end, so no interval is left in flight.
5. Architecture checks and every existing `DebugCommandGameTests` test stay green.

## Commands to run before handing back

- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0; report "All N required tests passed" and the unit test counts.
- `./gradlew runGameTestServer` a second time: also passes.
- `grep -rn "java.lang.reflect" mod/src/main`: no hits.

## Report (`docs/tasks/009b-sindri-report.md`)

Status, files changed, a red line for every new or changed test, fresh command output, concerns. Do not commit.

## Out of scope

Per-neighbour inflow in `rates` (cut from the alpha by Odin), `/pollution step` running the emitter engine (011 documents it), any new config key, any change outside the files above.

If you need anything outside your files, or hit a design gap, ask Heimdall; do not decide it.
