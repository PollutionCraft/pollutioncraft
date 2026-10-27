# 030 step 0 review (Heimdall): APPROVED with amendments; start implementing

The design note is `030-brokkr-design.md`; that name is accepted in place of `030-brokkr-step0.md`.

## Approved as proposed
- **Registration:** a plain `@EventBusSubscriber(modid = …)` receiving the mod-bus `RegisterPayloadHandlersEvent`, and no change to `PollutionCraftMod`. The trial shows the subscriber ran.
- **Client/server class split.**
  - Your trial matches how the JVM works. A method reference compiles to an `invokedynamic` whose `MethodHandle` constant is resolved when the call site links, which loads `ClientOverlay` on the server. A lambda's body is a synthetic method in the declaring class, and its `invokestatic` resolves only when it runs, which never happens on a dedicated server.
  - `OverlayRegistration` is the single allowed reference into `…mod.client`.
  - `ClientOverlayHud` is registered with `value = Dist.CLIENT` and has no references.
  - Approved.
- **Required on both sides:** a non-optional payload plus negotiation, and `neoforge.mods.toml` unchanged. The real-join checks are the user's, by hand.
- **F3:** `CustomizeGuiOverlayEvent.DebugText`, appending to `getLeft()`. The handler and the HUD run on the client main thread, so no locking is needed. Staleness uses client game time.
- **Isolation proof:**
  - `ClientIsolationTest` with its three rules and a fixture self-test;
  - `-Xlog:class+load` with a `doLast` check that requires the positive control `PollutionCraftMod` and finds zero `…mod.client.` or `net.minecraft.client.` classes.
  - Quote the red from the real check, not only from the trial.
- **GameTest players:** a `FakePlayer` subclass overriding `getPermissionLevel()`, never added to the player list or the level. `getPlayerCount()` stays 0, so concurrent tests and the simulation gate are unaffected, and `ops.json` is never written. Logout is tested by posting `PlayerLoggedOutEvent`, which the report must say.
- **Two seams on `OverlaySender`** (players source and send sink), with payload building counted separately from sending.

## Your three questions
1. **Unknown `protocolVersion` decodes to an `unsupported` marker that shows `no data`: approved.** A throwing decode would disconnect the client, which defeats binding decision 5. **Amendment A:** make the skip exact.
   - Lay the payload out as `protocolVersion` (varint), then the body's **byte length** (varint), then the body.
   - An unknown version skips exactly that many bytes, then returns `unsupported`.
   - That way skipping never depends on how the surrounding packet is framed, and a future v2 with a different body is always skippable by a v1 client.
   - Bound violations, over 32 lines or 256 characters, still throw: that is a corrupt stream, and closing is standard.
   - Codec GameTests:
     - a v1 round trip;
     - a hand-built v2 (unknown version, arbitrary body) decodes to `unsupported` and leaves the buffer's reader index exactly at the end of that payload, with a trailing marker byte after it still readable;
     - an over-bound payload throws.
   - **Registrar version:** keep `event.registrar("1")` fixed and separate from `protocolVersion`. Change it only if the channel itself changes incompatibly; document that next to the constant. Overlay format changes go through `protocolVersion`, so a dev-tool change never refuses a join.
2. **`OverlaySender.install/reset` as a test hook: approved, with guards (amendment B).**
   - Javadoc marks them test-only.
   - `install` while already installed throws `IllegalStateException`, so two tests can never overlap silently.
   - Every test that installs does install, the intervals, and `reset()` in `finally`, all in **one synchronous block**, with no delayed GameTest steps between them. Another test's tick can then never see the installed seams.
   - This matches the existing `EmitterEngine.global()` pattern.
3. **Immediate payload on `/pollution overlay on`: yes (amendment C). My decision; it does not need the user.**
   - The interval length is configurable up to 72,000 ticks (1 hour). Waiting for the next commit could leave a developer staring at `no data` for that long after switching on.
   - The extra send uses the same builder and the same checks: level 2, and the overlay now on. It goes only to the executing player, once.
   - It does not contradict the user's decisions: the payload is still built only for a level-2 player with the overlay on.
   - `off` sends nothing; the client goes to `no data` through staleness.
   - GameTest: `on` produces exactly one build and send for a level-2 fake player; `off` and a level-0 player produce none.

## Further amendments
- **D. Protect the lambda.** At the registration line, add a comment: it must stay a lambda, not a method reference, because a method reference loads the client class on a dedicated server, and the class-load check in `mod/build.gradle` enforces it. IDEs offer to convert lambdas to method references; the comment and the check together stop that.
- **E. Configuration cache.** The `doLast` check reads the log through a `File` captured at configuration time (as the world-deletion fix does), with no `project` access at execution. Show `./gradlew runGameTestServer --configuration-cache` passing once.
- **F. Report the class-load log size** and add it to `.gitignore` if it isn't already covered by `run/`.

## Unchanged from the brief
- File ownership, including in `mod/command/` exactly: `OverlayCommand.java`, one registration line, and `RatesText.line` made public.
- `pressure` and `aggressive` labelled until task 016.
- The test list (plus the codec and immediate-send cases above).
- Red-first with quoted lines.
- Two consecutive passing runs.
- The cost measurement at 0, 1 and 8 subscribers.
