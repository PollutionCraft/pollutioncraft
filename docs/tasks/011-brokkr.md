# 011 (Brokkr): Block emitter engine and furnace mapping

Read `docs/design.md` first. This brief carries the rest, including conditions that are binding. Engineers do not commit; Heimdall reviews and commits.

**Worktree:** `~/workspace/minecraft/pollutioncraft-011`, branch `feature/011-block-emitter-engine` (from `origin/main` with 009 and 010 merged).

**Runs in parallel with 009b (Sindri: `pending` line and `ServerConfig.value`).** File ownership is disjoint; see "Files owned".

**Goal of this phase (user):** the mod working with one vanilla machine, the furnace. A lit furnace pollutes its chunk at 1 PU per interval; an unlit one does not.

## Goal

Make blocks generate pollution through data, with no per-block code. The mod ships the engine and the mapping format. The only shipped mapping is the vanilla furnace, as data in the `minecraft` compat area (ADR 0012 §2). Without that data file the mod loads and emits nothing. Acceptance: M1 for the furnace (plan task 011 behaviour and tests 1–5).

## Design references

- Architecture 3.3 "Emission" (emitter sources, rung 1 data mapping), 3.5 (emitter API, tags), 4 (adapter rule, errors), 5 (budget: field simulation ≤ 0.5 ms/tick).
- ADR 0005, ADR 0012 §2 (mappings ship as data in compat; the in-core `…compat.minecraft` area during the alpha), ADR 0015 (rung 1: data mapping; no Mixin, no reflection).
- Plan task 011 (`plan/tasks/011-block-emitter-engine-and-furnace-mapping.md`), including the conditions carried over from 010 and 009.
- Glossary: **1 PU = one lit furnace's output for one interval** (1 s at the default 20 ticks).
- Code on `main`: `PollutionApi.emit`, `PollutionManager`, `DimensionPollution.pendingEmission(long)`, `ServerConfig.emissionMultiplier()`, `SimulationGate`.

## Binding conditions (Odin and Heimdall, from 010 and 009; do not reinterpret)

1. **Red first, quoted.** For every new test, run it before the implementation exists, see it fail for the right reason, and paste the failing line in your report. A compile error counts as red only when the symbol under test does not exist yet. A test without its red line is grounds for rejection.
2. **`emit` cost:**
   - Measure the engine's per-interval cost, including the `PollutionApi.emit` calls, with 100 active emitters. It must fit inside the 0.5 ms/tick field budget together with the measured diffusion cost (about 0.12 ms/tick).
   - `PollutionManager.dimension(level)` and `world(server)` rebuild a data-file `Path` on every call. If that shows in your measurement, cache the **path** per dimension key in `PollutionManager`.
   - Never cache the `SavedData` instances: tests and storage replace them (`DimensionDataStorage.set`).
   - Report the numbers either way.
3. **Verify first that `minecraft:furnace` has the `lit` block-state property** (NeoForge 21.1.264 sources: `AbstractFurnaceBlock.LIT`). Record the finding and its source in your report before writing the mapping.
4. **First slice is the furnace only:** rate **1 PU per interval**. Other vanilla blocks are task 029.
5. **The engine applies `emissionMultiplier`, not the API.** Emit `rate × emissionMultiplier`, reading the multiplier once per interval. `/pollution tune emissionMultiplier …` then scales every mapped emitter from the next interval, with no change to 009's code.
6. **Discovery must not scan whole chunks each interval.** See step 0.
7. **Invalid mapping entries are skipped and logged, never fatal.**
8. **M1 GameTest:** one lit and one unlit furnace.
9. **Interval write rule and `isPreserved()`** still hold. The engine only calls `emit`, never the field, and emits nothing for a paused world or dimension (`emit` already refuses; do not emit around it).

## Step 0: findings and discovery proposal (send to Heimdall before any implementation)

The discovery strategy is this task's main design decision. Before writing engine code, send me a short note (`docs/tasks/011-brokkr-step0.md`) with:
- the `lit` finding (condition 3);
- your discovery strategy, with the events or hooks used and how each case is caught:
  - initial discovery when a chunk loads (including worldgen);
  - a mapped block placed by a player, by `/setblock`, or by a GameTest `helper.setBlock`;
  - a mapped block removed by any means (break, explosion, command);
  - the active property changing (`lit` true/false);
  - a datapack `/reload` that changes the mapping;
  - chunk unload;
- its cost: per chunk load, per tick, per interval, with the bound for each;
- its discovery latency, if any;
- any case it does not catch, and why that is acceptable.

**A starting point to verify, not a decision:**
- Scan a chunk once when it loads. Use `LevelChunkSection.maybeHas(predicate)` to skip sections whose palette holds no mapped state.
- Track changes through NeoForge's `BlockEvent.NeighborNotifyEvent`, if the 21.1.264 sources show it fires for `setBlock` with the neighbour-update flag. That covers furnace `lit` toggles, placements and removals.
- Re-validate tracked positions once per interval, which is cheap at O(tracked) and catches anything missed for removal or state.
- Use a bounded round-robin rescan of loaded chunks only if a placement path is not covered.

Check this against the sources and say what holds.

Constraints:
- No Mixin, no reflection, no access transformers.
- No chunk loading or generation; only loaded chunks are considered.
- Emit only for positions in chunks where block entities tick (`level.shouldTickBlocksAt(...)` or equivalent). A furnace in a loaded but non-ticking chunk does not burn, so it must not emit.

I reply with OK or changes, usually the same day. Write tests 1–2 below while you wait.

## Decisions I am making for this task (Heimdall)

- **Where the furnace data lives:** a datapack JSON at `mod/src/main/resources/data/pollutioncraft/pollution_emitters/minecraft/furnace.json`.
  - The folder after `pollution_emitters/` is the target mod id. That folder is the data equivalent of `…compat.minecraft`, and it moves unchanged to the compat mod after the split.
  - There are no Java classes in `…compat.minecraft` for this task, so the adapter-import check (plan test 5) passes trivially; say so in the report.
  - I'm recording this convention with Mimir.
- **Mapping format** (one entry per file; JSON field names snake_case, like vanilla data):

  ```json
  { "block": "minecraft:furnace", "rate": 1.0, "active_when": { "property": "lit", "value": "true" } }
  ```

  - Exactly one of `block` (block id) or `tag` (block tag id, without `#`).
  - `rate` is PU per interval, finite and ≥ 0.
  - `active_when` is required, with the property's serialized value as a string.
- **Invalid entries** are skipped with one log line each, naming the file and the reason. Invalid means:
  - missing or duplicate target, or both `block` and `tag`;
  - `rate` missing, not a number, NaN, infinite or negative;
  - unknown block or tag;
  - a property the block doesn't have;
  - a value the property can't take.

  An unknown tag counts as an empty tag: logged, emits nothing.
- **Conflicts:** if more than one entry matches the same block state, the entry with the lowest resource id wins, with one warning per conflict per reload. This is deterministic and needs no merge rules.
- **Overrides:** a datapack overrides the rate by replacing the same resource path, which is vanilla's data override. Pack-wide scaling is `emissionMultiplier`. No new config key in this task; if you think you need one, ask.
- **Engine hook:**
  - `PollutionManager.tickDimension` calls the engine for that level on the tick an interval **starts** (`!scheduler().inFlight()`), before `scheduler().tick(...)`. The engine's emissions are then drained into that same interval: exactly one emission per active emitter per interval.
  - It runs only on ticks the `SimulationGate` lets through. No player online means no emission, so nothing piles up while the world is frozen.
  - It doesn't run for a paused world or dimension, because `tick`/`tickDimension` already return early.
  - `/pollution step` does not run the engine: it drains only what is already buffered. Document this in `stepNow`'s Javadoc; it is out of scope here.
- **Plain-Java core of the format:** validating a raw entry (`Map<String, ?>` produced from JSON by the Minecraft-side loader) lives in a plain class with no Minecraft or Gson types, unit-tested in `mod:test`, as 008's `ConfigValues.load` does. Minecraft is not on `mod:test`'s classpath. Registry resolution (unknown block, property or value; tag expansion) needs real registries and is tested by GameTests.
- **Test-only mapping:** no test-only block is registered. The engine tests use a test-only mapping in the `gametest` source set's resources, which is never in the jar: `mod/src/gametest/resources/data/pollutioncraft/pollution_emitters/test/redstone_lamp.json` (`minecraft:redstone_lamp`, `lit=true`, rate 2). A block **without** a block entity proves discovery is not tied to block entities. The furnace GameTest uses the real shipped mapping.

## Files owned (exclusive)

- New package `mod/src/main/java/io/github/pollutioncraft/mod/emitter/**`: mapping validation, loader (reload listener registered with `@EventBusSubscriber` on `AddReloadListenerEvent`), tracker, engine.
- `mod/src/main/java/io/github/pollutioncraft/mod/PollutionManager.java`: the engine hook in `tickDimension`, the `stepNow` Javadoc line, and the optional path cache. Nothing else.
- `mod/src/main/resources/data/pollutioncraft/pollution_emitters/**` (the furnace mapping), plus a short format note in `mod/src/main/resources/data/pollutioncraft/pollution_emitters/README.md`.
- `mod/src/gametest/resources/data/pollutioncraft/pollution_emitters/**` (test-only mapping).
- Tests: new `mod/src/test/java/io/github/pollutioncraft/mod/emitter/**`; new `mod/src/gametest/java/io/github/pollutioncraft/gametest/EmitterGameTests.java`.
- `docs/tasks/011-brokkr-step0.md`, `docs/tasks/011-brokkr-report.md`.

**Not yours** (009b or already accepted): `mod/command/**` and its tests, `DebugCommandGameTests.java`, `config/**` (including `ServerConfig` and `ConfigRegistry`; you only call `emissionMultiplier()`), `PollutionCraftMod.java`, `PollutionApiImpl.java`, `DimensionPollution.java`, `WorldPollution.java`, everything in `core/**` and `api/**`, the other GameTest files, Gradle files. If you need a change there, ask first.

## Tests to write first (each with its quoted red)

1. **Unit, mapping validation:** valid block entry; valid tag entry; missing target, both targets; missing, non-numeric, negative, NaN and infinite `rate`; missing `active_when`, missing property or value. Each invalid case is skipped and produces exactly one log line naming the file.
2. **Unit, engine arithmetic:** the emitted amount is `rate × multiplier`; multiplier 0 emits nothing; a change to the multiplier applies from the next interval, not mid-interval.
3. **GameTest, registry resolution:** unknown block, unknown property, unknown value, unknown tag are each skipped and logged; a tag expands to its blocks.
4. **GameTest, engine with the test mapping:**
   - a lit redstone lamp (powered) emits 2 PU per interval: its chunk gets 2 PU after one interval, as shown by `getLocal`, and `pendingEmission` before the drain;
   - an unlit lamp emits nothing;
   - removing the lamp stops emission from the next interval;
   - a lamp placed by `helper.setBlock` after the chunk loaded is discovered within your stated latency.
5. **GameTest, unloaded and non-ticking:** a tracked position whose chunk is unloaded, or not block-ticking, emits nothing. How to set this up is part of your step-0 note.
6. **GameTest, M1 furnace (real shipped mapping):** a furnace with fuel and something to smelt (so it really burns), next to an unlit furnace in another chunk. After one interval the lit furnace's chunk rose by exactly 1 PU × `emissionMultiplier`, the unlit chunk by 0, and `grossEmitted` by the same 1 PU. Repeat with `tune emissionMultiplier 2` set through `RuntimeOverrides`, cleared in `finally`.
7. **GameTest, mod without the furnace data:** the engine built with an empty rule set (no mapping files) emits nothing for a lit furnace, and the mod runs normally.
8. **No player online:** with the gate closed, the engine emits nothing (the buffer for the furnace chunk stays 0).
9. **Measurement (not a pass/fail gate, but reported):** 100 active emitters. Engine cost per interval (discovery upkeep plus 100 `emit` calls), divided by 20 to get ms/tick, next to the diffusion cost. Also report the cost per chunk-load scan.
10. Architecture checks stay green.

## Commands to run before handing back

- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0; report "All N required tests passed" and the unit test counts.
- `./gradlew runGameTestServer` a second time: also passes, proving there is no state carried between runs.
- `./gradlew runServer` is not required (it needs the EULA; the user does the manual check at acceptance).

## Report (`docs/tasks/011-brokkr-report.md`)

Include:
- status, files changed;
- the `lit` finding;
- the discovery strategy as approved, plus anything that changed;
- a red line for every test;
- fresh command output;
- the measurements;
- whether the path cache was needed;
- concerns.

Do not commit.

## Out of scope

Other vanilla blocks (029), the GTM adapter (012), absorption, Mixins or reflection, `/pollution step` running the engine, a new config key, the PU upper bound (Mimir follow-up), `ServerConfig.value` and the `pending` line (009b).

If you need anything outside your files, or hit a design gap, ask Heimdall; do not decide it.
