# 030 step 0: findings and design (Brokkr)

Status: proposal for Heimdall, no production code written. Sources: NeoForge 21.1.264 (sources jar plus the patched Minecraft sources) and FML loader 4.0.45 (class files, `javap`). Two things were also tried for real in a **scratch copy** of this worktree (not in this tree): a throw-away payload plus client class with `-Xlog:class+load`, run through `runGameTestServer`. Results are in sections 1 and 4.

The brief named `030-brokkr-step0.md`; this file is `030-brokkr-design.md`, the name used in the request.

## 1. Payload registration and the client/server class split

**Registration.**
- `RegisterPayloadHandlersEvent` is a mod-bus event (`implements IModBusEvent`). `event.registrar(String version)` returns a `PayloadRegistrar`; `playToClient(Type<T>, StreamCodec<? super RegistryFriendlyByteBuf, T>, IPayloadHandler<T>)` registers a client-bound play payload (`PayloadRegistrar.java:44`).
- **No change to `PollutionCraftMod` is needed.** `@EventBusSubscriber(modid = ...)` without `bus =` received this mod-bus event: the trial subscriber ran (`TRIAL registering payload` printed at startup). In FML 4.0.45 the `bus` attribute is `@Deprecated` with default `GAME`, and the bus is evidently chosen from the event type.
- Payloads are always serialised, even in singleplayer (Javadoc of `RegisterPayloadHandlersEvent`), so the client path is the same in dev and on a server.

**Handler thread.** `PayloadRegistrar` defaults to `HandlerThread.MAIN` and wraps handlers in `MainThreadPayloadHandler` (`PayloadRegistrar.java:119-130`). The client handler therefore runs on the client main (render) thread, the same thread that fires the F3 event, so the client state needs no locking.

**Keeping the dedicated server from loading a client class.** Measured, not assumed (class-load log of the dedicated-dist GameTest server):
- **A method reference `playToClient(TYPE, CODEC, ClientHandler::handle)` DOES load the client class on the server.** Trial 1: the log contains `io.github.pollutioncraft.mod.client.TrialClient source: union:/…/classes/java/main/…`. (`net.minecraft.client.Minecraft`, touched only in the handler body, was not loaded, but our own client class was, which the runtime check would flag.)
- **A lambda `(payload, ctx) -> ClientHandler.handle(payload, ctx)` written in a server-safe class does NOT.** Trial 2: zero lines containing `pollutioncraft.mod.client` and zero containing `net.minecraft.client.` in the whole log. The `invokestatic` inside the lambda is resolved only when the lambda first runs, which never happens on a server.
- **Design:** `overlay/OverlayRegistration` (server-safe, subscribes to the mod-bus event) registers `OverlayPayload` with such a lambda calling `client.ClientOverlay.accept(payload)`. That class is the one allowed reference from outside `mod.client`. Client classes: `ClientOverlay` (state and handler) and `ClientOverlayHud` (the F3 listener). Nothing else references them.

## 2. Required on both sides

- Our payload is registered non-optional (the default; `PayloadRegistrar.optional()` is the opt-out, `PayloadRegistrar.java:151-158`). Javadoc at `NetworkRegistry.java:147`: "Any connection with missing non-optional payloads is denied."
- **NeoForge client without the mod:** negotiation fails for the missing channel (`NetworkComponentNegotiator.java:82-94`, reasons `…failure.missing.client.server` / `…missing.server.client`) and the join is refused with a message naming the mod.
- **Vanilla client:** `NetworkRegistry.initializeOtherConnection(ServerConfigurationPacketListener)` (line ~398) negotiates against an empty client set; a non-optional payload makes it fail and the server disconnects the client (`neoforge.network.negotiation.failure.vanilla.client.not_supported`).
- **The reverse** (client has the mod, server does not): `initializeOtherConnection(ClientConfigurationPacketListener)` (line ~540) refuses a vanilla server when the client has a non-optional payload.
- **Version:** I register with `event.registrar("1")`. On NeoForge-to-NeoForge connections the join fails if payload version strings differ (`NetworkComponentNegotiator.java:175`). This is a second, coarser gate next to the payload's own `protocolVersion`.
- **`neoforge.mods.toml`: no change needed.** The existing `side = "BOTH"` entries concern the neoforge and minecraft dependencies and stay. The network negotiation is what enforces "required on both sides"; `displayTest` already defaults to `MATCH_VERSION`.
- **Not automatable:** a real join from a client without the mod. Manual check for the user.

## 3. The F3 hook
- `CustomizeGuiOverlayEvent.DebugText` (`neoforge/client/event/CustomizeGuiOverlayEvent.java:121`), posted on `NeoForge.EVENT_BUS` from `DebugScreenOverlay.render` (line 141) after the vanilla text is collected and before it is drawn. `getLeft()` is the modifiable left-hand list.
- I append a blank separator, `Pollution`, then the lines to **`getLeft()`**. The event fires only while the debug overlay is drawn.
- Client-only registration: `@EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)` on `client.ClientOverlayHud`. The annotation's `value` is `Dist[]` (default both); with `CLIENT` FML does not load the class on a dedicated server, and no code references it.
- Staleness uses `minecraft.level.getGameTime()` against the game time stored on arrival, so no client tick event is needed; `/tick freeze` freezes both.

## 4. Proof that the server loads no client class

**Static: `ClientIsolationTest` (ArchUnit; `ArchitectureRules.java` untouched).**
1. Nothing outside `io.github.pollutioncraft.mod.client..` depends on `..mod.client..`, except `…mod.overlay.OverlayRegistration`.
2. Nothing outside `..mod.client..` depends on `net.minecraft.client..` or `net.neoforged.neoforge.client..`.
3. A fixture-based self-test shows the rules can fail, as the existing architecture tests do.

**Runtime (the GameTest server is a dedicated-dist server).**
- `neoForge.runs.gameTestServer`: `jvmArgument '-Xlog:class+load:file=classload.log'`. Verified: the moddev DSL accepts it and the file lands in `run/gametest/`.
- The log lists classes loaded by FML's class loader: it has `io.github.pollutioncraft.mod.PollutionCraftMod source: union:/…/classes/java/main/…` and, in trial 1, the client class (about 23,000 lines, 4 MB).
- `runGameTestServer`: `doFirst` deletes the old log; `doLast` fails if the log (a) lacks `io.github.pollutioncraft.mod.PollutionCraftMod` (a positive control, so a missing or empty log cannot pass), or (b) contains `io.github.pollutioncraft.mod.client.` or a `net.minecraft.client.` class. Baseline: zero of the second kind.
- **Deliberate red:** trial 1 already is one (method reference: the client class is in the log). I will repeat it with the real check and quote the failure before relying on it.

**Not automatable now:** a real dedicated server with our jar (EULA; pack smoke includes our mod only from task 022) and the real-client F3 check (the user's, by hand).

## 5. Server players in GameTests without opening the gate

Facts from the sources:
- `getPlayerCount()` counts the `PlayerList`; a `ServerPlayer` that is never added to it does not count. `ServerLevel.players()` is a separate list that only `addNewPlayer` fills.
- `Entity.hasPermissions(int)` is `getPermissionLevel() >= level` (`Entity.java:3275`); `getPermissionLevel()` is `protected int`, and `ServerPlayer` implements it with `server.getProfilePermissions(profile)` (`ServerPlayer.java:1709`), which reads the ops list (`ops.json`). So `getPermissionLevel()` is the override point; going through the ops list would write `ops.json`.
- NeoForge's `FakePlayer` is a `ServerPlayer` with a no-op network handler and is in neither list.

Proposal:
- **Tests use a small `FakePlayer` subclass** (GameTest source set) that overrides `getPermissionLevel()` to return a field (0 or 2), constructed directly with a random-UUID `GameProfile` (not via `FakePlayerFactory`, which caches). It is never added to the player list or the level, so the gate and concurrent tests are unaffected and `ops.json` is never touched. `moveTo` puts it in a chosen chunk. Nothing to clean up beyond dropping the reference.
- **`OverlaySender` has two seams:** `players: Function<ServerLevel, Collection<ServerPlayer>>` (default `ServerLevel::players`) and `sink: BiConsumer<ServerPlayer, OverlayPayload>` (default `PacketDistributor.sendToPlayer`). Payload construction goes through one method, so a test can count builds, separately from sends ("never built", not only "not sent").
- Recipient selection (brief test 2) is a plain function over `(id, permissionLevel, subscribed)`, tested without Minecraft. The real adapter (`hasPermissions(2)`, `chunkPosition()`) is exercised in the GameTests with the subclass.
- For the manager-hook test, `OverlaySender.global()` offers `install(players, sink)` and `reset()`; tests restore in `finally`.
- **Command tests:** `player.createCommandSourceStack()` takes its permission level from `getPermissionLevel()`, so `performPrefixedCommand(source, "pollution overlay on")` hits the real permission check. Console: `server.createCommandSourceStack()` (no entity). Logout: post `new PlayerEvent.PlayerLoggedOutEvent(player)` on `NeoForge.EVENT_BUS` (a real logout cannot be triggered without a connection); the report will say so.

## 6. Payload shape and staleness: agreed, with three points from the sources
1. **A decode that throws disconnects the client.** `PacketDecoder` lets the exception out and `Connection.exceptionCaught` closes the connection with "Internal Exception" (`Connection.java:144-184`); `SkipPacketException` only helps when encoding. So "decode rejects an unknown `protocolVersion`, the client logs once and shows `no data`" cannot be done by throwing. **Proposal:** the codec reads `protocolVersion` first; for an unknown version it skips the rest of the buffer and returns an `OverlayPayload` marked `unsupported`, which the client logs once and shows as `no data`. Bound violations (over 32 lines or 256 characters) are a corrupt stream, so decode throws and the connection closes, which is standard Minecraft behaviour; the server never produces one because `OverlayText` truncates before building.
2. Bounded codec: `ByteBufCodecs.stringUtf8(256)` and `ByteBufCodecs.list(32)` exist in 1.21.1 (`ByteBufCodecs.java:230, 388`).
3. **Latency:** after `/pollution overlay on` the first lines appear after the next interval (at most `intervalTicks`); `no data` shows until then, because the sender is hooked to commits only. An immediate extra payload from the command handler (still only for a level-2 player with the overlay on) would fix that; I will not add it unless asked.

Other details as in the brief:
- Fields: `protocolVersion` (varint, 1), dimension id, chunk x and z, `intervalTicks`, lines. Typical size well under 1 KB per subscriber per interval; the cap is 32 x 256 characters.
- `intervalTicks` is read from `ServerConfig.spreadIntervalTicks()` at send time; an override changed mid-interval lags one interval, and the client's `3 x intervalTicks` window absorbs it.
- Client shows `no data` when there is no payload, the version was unsupported, the dimension or chunk differs, or `gameTime - receivedAt > 3 x intervalTicks`.
- The sender only reads (`field().get`, `pendingEmission`, config, `WorldRecord`) and returns before touching players when the subscriber set is empty.
- `pressure: 0 (until task 016)` and `aggressive: n/a (until task 016)`; `PollutionApi.isAggressive` is never called.

## 7. Decisions I need
1. Section 6.1: an unknown version returns an `unsupported` value instead of throwing. I believe this is required for binding decision 5 to work as intended.
2. The `OverlaySender.install/reset` test hook (section 5), or another way you prefer to drive the manager hook in a GameTest.
3. Immediate payload on `overlay on` (section 6.3): yes or no (default no).
