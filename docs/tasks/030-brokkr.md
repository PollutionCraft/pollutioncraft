# 030 (Brokkr): Dev pollution overlay (F3)

Read `docs/design.md` first. This brief carries the rest, including user decisions that are binding. Engineers do not commit; Heimdall reviews and commits.

**Worktree:** `~/workspace/minecraft/pollutioncraft-030`, branch `feature/030-dev-pollution-overlay` (from `origin/main` with 005–011 and 009b merged).

**This task has a design gate (step 0).** It is the mod's first network payload and its first client-only code. Registration, the client/server class split, and how GameTests get server players are all verified in the NeoForge 21.1.264 sources before any production code.

## Goal

Developers and testers see live pollution numbers for their current chunk in the F3 debug screen. This is a developer tool, not the player-facing display. Plan task 030; architecture 3.5 "Dev overlay".

## Design references

- Plan task 030 (`plan/tasks/030-dev-pollution-overlay.md`); architecture 3.5 (dev overlay, commands), 4 (state: server authoritative, client gets read-only data, no client→server packets; security: mutating commands need level 2).
- Code on `main`:
  - `PollutionManager` (`tickDimension` commit path, `stepNow`);
  - `mod/command/**` (`PollutionCommands`, `PollutionText`, `RatesText`);
  - `ServerConfig` and `RuntimeOverrides`;
  - `WorldRecord.pressure()`.

## Binding decisions (user, 2026-10-22; do not reinterpret)

1. The mod is **required on both client and server**. A client without the mod cannot join.
2. **The server is authoritative.** The client gets read-only data, and there are **no client→server packets**.
3. **`/pollution overlay on|off`:** per player, permission level 2, **default off**, in memory only.
4. **The payload is built only for a player who has level 2 and the overlay on.** For anyone else it is never built, not merely left unsent.
5. **The payload carries a protocol version.** Missing or stale data shows `no data`, never old numbers.

## Standing rules

- **Red first, quoted:** for every new test, run it before the implementation exists and paste the failing line. A compile error counts as red only when the symbol under test does not exist yet. An "absence" test (no payload when off) cannot go red against a stub, so show its red with a mutant, as in 011.
- **No flaky GameTests.** Two consecutive `runGameTestServer` runs must pass.
- **Interval write rule:** the sender only **reads** the field, after the interval has been applied. It never writes.

## Step 0: findings and design note (send to Heimdall before any production code)

Write `docs/tasks/030-brokkr-step0.md`, from the NeoForge 21.1.264 sources, with file and line references:

1. **Payload registration:**
   - how a `CustomPacketPayload` with a `StreamCodec` is registered (`RegisterPayloadHandlersEvent`, `PayloadRegistrar.playToClient` or equivalent);
   - how the client handler is attached so that **a dedicated server never loads a client class**. A handler lambda that calls into a client-only class, plus why that is safe on the server, or the 21.1 API that registers client handlers separately;
   - which thread the handler runs on.
2. **Required on both sides:** what makes a client without the mod unable to join. Non-optional payload channel negotiation, the `neoforge.mods.toml` settings, or both. Say what you will set.
3. **Debug-screen hook:** the 21.1.264 client event for F3 text (for example `CustomizeGuiOverlayEvent.DebugText`), which list you append to, and how to register it client-only (`@EventBusSubscriber(value = Dist.CLIENT)` or equivalent).
4. **Proof that the server loads no client class (automatable):**
   - **Static:** a new ArchUnit test. Nothing outside `…mod.client..` references `…mod.client..`, except the registration point you name in step 0 if one is needed.
   - **Runtime:** add `-Xlog:class+load` (to a file) to the `gameTestServer` run, and a `doLast` check that fails if any `io.github.pollutioncraft.mod.client.` class was loaded. Confirm from a trial run that the log really lists classes loaded by FML's module class loader, with a deliberate red: temporarily touch a client class from server code and see the check fail.
   - **What cannot be automated now:** a real dedicated server with our jar. The pack smoke (task 002) does not include our mod until task 022, and `runServer` needs the EULA, which the user accepts. The GameTest server *is* a dedicated-dist server, so the runtime check above is the automated proof.
   - The real-client F3 check is the user's, by hand.
5. **Server players in GameTests without opening the simulation gate.** Adding a mock player to the player list makes `getPlayerCount() ≥ 1` for the whole server while the test runs. The real `ServerTickEvent` would then advance every dimension's scheduler under every other concurrent GameTest, which is exactly the flakiness we removed in 010. Propose how tests get a level-2 and a level-0 `ServerPlayer` without that. For example, construct `ServerPlayer`s not added to the player list, and select recipients through a seam that takes the candidate players. Say how permission level is set and cleaned up, and never leave entries in `ops.json`.
6. **Payload shape and staleness** (my decisions below), plus anything in the sources that argues against them.

I reply with OK or changes. While you wait, you may write the plain-Java unit tests (`OverlayText` and recipient selection), but no production code.

## Decisions I am making for this task (Heimdall)

- **Format on the server; reuse 009's text.**
  - The payload carries the already-formatted lines, built from the existing public `PollutionText` and `RatesText` methods, unchanged, plus a small header.
  - The client only displays them. That gives one formatter and no duplication, and the client needs no knowledge of `ChunkPollution` or the config.
  - Overlay-specific layout (which lines, in what order, the section title `Pollution`) lives in a new plain class `OverlayText` in your package. It composes the existing methods and does not copy them.
  - If you need a change to `PollutionText` or `RatesText`, ask me; they are not yours.
- **Lines** (plan task 030):
  - the chunk's `absorbable` and `persistent`, `dominantSource` and rate, `emittedLastInterval` (from `PollutionText.get`);
  - generated, spread in and out, absorbed, decayed (from `PollutionText.rates`);
  - `pressure`;
  - `aggressive`;
  - one line per **active** override (from `RatesText.line`, which already appends `(overridden)`).
- **`pressure` reads 0 until task 016 implements `GlobalPressure`.** Show the value as is, labelled `pressure: 0 (until task 016)`. Do not hide the line.
- **`aggressive`:** `PollutionApi.isAggressive` **throws** until task 016. Never call it. Show `aggressive: n/a (until task 016)`. Task 016 replaces both labels.
- **Payload** (`…mod.overlay.OverlayPayload`):
  - `protocolVersion` (int, starts at 1);
  - the dimension id and chunk x/z the lines describe;
  - `intervalTicks` (the interval length in force);
  - the list of lines, bounded: at most 32 lines of at most 256 chars each, enforced in the codec, and decode rejects anything over the bounds.
  - Decode rejects an unknown `protocolVersion`: the client logs once and shows `no data`.
- **Staleness, on the client:** show `no data` when there is no payload yet, when the version was rejected, when the player is in a different chunk or dimension than the payload describes, or when the last payload is older than `3 × intervalTicks` client ticks. Never show old numbers.
- **Toggle state (server, in memory):**
  - a set of player UUIDs, kept in a new class in your package;
  - `/pollution overlay on|off` adds or removes the executing player;
  - cleared for a player on logout (`PlayerLoggedOutEvent`), so re-joining starts off, and cleared on server stop;
  - a non-player source (console) gets a failure reply.
- **Commit hook (who sends, when):**
  - **Tick path:** in `PollutionManager.tickDimension`, right after `scheduler().tick(...)` returns `true` (the interval was applied), call `OverlaySender.afterInterval(level)`.
  - **Step path:** in `PollutionManager.stepNow`, after a `RAN` result, call it once for that level.
  - Each call costs the subscriber-set check only, when nobody is subscribed: return before touching players or building anything (binding decision 4).
  - The recipients are the players in that level whose UUID is in the set **and** who have `hasPermissions(2)` **now**, re-checked every interval, so a de-opped player stops receiving at once.
  - Paused records never commit, so subscribers get nothing and their clients show `no data` after the stale window. That is correct.
- **Cost:** see test 7. Target: the sender costs at most **0.01 ms/tick on average with 8 subscribed level-2 players**, and is a single set-emptiness check with none.

## Files owned (exclusive)

- New package `mod/src/main/java/io/github/pollutioncraft/mod/overlay/**`: payload and codec, registration, the subscriber set, the sender, `OverlayText`.
- New package `mod/src/main/java/io/github/pollutioncraft/mod/client/**`: the client-only payload handler, state, and the debug-text listener. Nothing outside this package may reference it, except as step 0 allows.
- `mod/src/main/java/io/github/pollutioncraft/mod/PollutionManager.java`: the two sender calls only.
- **In Sindri's `mod/command/` package, exactly two things:**
  - a new file `mod/command/OverlayCommand.java` (the `overlay on|off` subtree and its handler);
  - one added line in `PollutionCommands.register`: `.then(OverlayCommand.node())` or equivalent;
  - `RatesText.line(ServerConfig, ConfigKey)` made `public`. It is package-private today, and `OverlayText` needs it for the override lines. A visibility change only, no behaviour change.

  Nothing else in `mod/command/**` changes.
- `mod/build.gradle`: the `-Xlog:class+load` argument and the class-load check on the `gameTestServer` run only.
- `mod/src/main/resources/META-INF/neoforge.mods.toml`: only what step 0 shows is needed for "required on both sides".
- `README.md`: one short section. The mod is required on both client and server, and how to use the overlay. The pack repo's README is task 022, not yours.
- Tests:
  - new `mod/src/test/java/io/github/pollutioncraft/mod/overlay/**`;
  - new `mod/src/test/java/io/github/pollutioncraft/architecture/ClientIsolationTest.java` (do not edit `ArchitectureRules.java`);
  - new `mod/src/gametest/java/io/github/pollutioncraft/gametest/OverlayGameTests.java`.
- `docs/tasks/030-brokkr-step0.md`, `docs/tasks/030-brokkr-report.md`.

**Not yours:** everything else in `mod/command/**`, `config/**`, `emitter/**`, `PollutionCraftMod.java` (use `@EventBusSubscriber`; if mod-bus payload registration truly needs the constructor, say so in step 0), `DimensionPollution`, `WorldPollution`, `PollutionApiImpl`, `core/**`, `api/**`, the other GameTest files.

## Tests to write first (each with its quoted red)

1. **Unit, `OverlayText`:**
   - the section lines for a chunk with values, for an absent chunk (zeros), and with one active override (exactly that line marked `(overridden)`);
   - `pressure: 0 (until task 016)` and `aggressive: n/a (until task 016)` present;
   - the `no data` text.
   - Plain Java only: `mod:test` has no Minecraft on its classpath.
2. **Unit, recipient selection** (plain function over player id, permission level, subscribed):
   - level 2 and on → included;
   - level 2 and off, or level 0 and on → excluded;
   - an empty subscriber set → returns before evaluating any player, shown with a counting stub.
3. **GameTest, payload codec:**
   - encode then decode into a buffer gives an equal payload;
   - an unknown `protocolVersion` is rejected;
   - more than the line or length bounds is rejected.
   - This needs Minecraft's buffer types, hence a GameTest rather than a unit test.
4. **GameTest, sender:**
   - overlay off → no payload **built** (count builds through the seam, not only sends);
   - on → exactly one payload per committed interval for a level-2 player, describing that player's chunk;
   - a level-0 player with the overlay on gets none;
   - `stepNow(level, 3)` sends exactly one.
   - Drive intervals the way the existing tests do (`abandon()` first, `PollutionManager.tick(server, 1)`), using the players from step 0 point 5, never the player list.
5. **GameTest, `/pollution overlay`:** `on` and `off` toggle the set for the executing player; level 0 is refused; the console gets a failure; logout clears it (call the handler with a constructed event if a real logout can't be triggered, and say so).
6. **Client isolation:** `ClientIsolationTest` (ArchUnit) plus the `gameTestServer` class-load check, each shown red once with a deliberate violation.
7. **Measurement (reported):** sender cost per interval with 0, 1 and 8 subscribed level-2 players, as ms/tick averaged over the interval.

**Manual (the user's, recorded in the report):** in a dev client (`runClient`) as an op, `/pollution overlay on` makes F3 show the `Pollution` section. It changes after `/pollution set 20` and `/pollution step`. It shows `no data` after `/pollution overlay off`, and after walking into another chunk until the next interval.

## Commands to run before handing back

- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache`: exit 0; report "All N required tests passed", the unit counts, and the class-load check's result line.
- `./gradlew runGameTestServer` a second time: also passes.

## Report (`docs/tasks/030-brokkr-report.md`)

Include:
- status, files changed;
- the step-0 decisions as approved;
- a red line for every test;
- fresh command output;
- the measurements;
- what is automated versus manual;
- concerns.

Do not commit.

## Out of scope

A player-facing HUD or map, per-neighbour data, client→server packets, persisting the toggle, implementing `pressure` or `aggressive` (task 016), the pack README (task 022).

If you need anything outside your files, or hit a design gap, ask Heimdall; do not decide it.
