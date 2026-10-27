# 030 report (Brokkr): dev pollution overlay (F3)

**Status: DONE_WITH_CONCERNS.** Nothing committed; no `eula.txt` written. Design as approved in `030-brokkr-design.md` and `030-brokkr-design-review.md`.

## Fresh results
- `./gradlew clean build runGameTestServer --rerun-tasks --no-build-cache --no-daemon -q`: exit 0, **"All 67 required tests passed :)"** (20:36:12). Class-load check line: `Class-load check passed: 23745 classes loaded, none from mod.client or net.minecraft.client (log 3978 KB)`.
- Two further plain runs of `./gradlew runGameTestServer --no-daemon -q`, no cleaning: **"All 67 required tests passed :)"** at 20:37:27 and 20:38:53, each with the class-load line.
- **Configuration cache:** `./gradlew runGameTestServer --configuration-cache` passed twice ("Configuration cache entry reused" on the second, 67 tests and the class-load line present).
- Unit tests: core 58, mod 175, 0 failed (mod includes the new `OverlayTextTest`, `OverlayDisplayTest`, `OverlayRecipientsTest`, the 5 `ClientIsolationTest` tests and all architecture checks). New GameTests: 15 in `OverlayGameTests` (14 behaviour tests plus the measurement).
- Class-load log size: about 3.9 MB (23,700 lines), `mod/run/gametest/classload.log`, git-ignored through `run/`. It is deleted at the start of each run.

## What was built
- `mod/overlay/`: `OverlayPayload` (codec), `OverlayRegistration`, `OverlaySender`, `OverlaySubscribers`, `OverlayRecipients`, `OverlayText`, `OverlayDisplay`, `OverlayEvents`.
- `mod/client/`: `ClientOverlay` (state and handler), `ClientOverlayHud` (F3 listener, `Dist.CLIENT`).
- `mod/command/`: new `OverlayCommand.java`; one added line in `PollutionCommands.register`; `RatesText.line` made `public` (nothing else).
- `PollutionManager`: two calls, `OverlaySender.afterInterval(level)` after a committed tick and once after a `RAN` step.
- `mod/build.gradle`: `-Xlog:class+load:file=classload.log` on `gameTestServer` and the `doLast` check. `README.md`: one section. `neoforge.mods.toml`: unchanged (the non-optional payload enforces both-sides).
- Tests: unit (plain Java), `ClientIsolationTest` (+ fixtures under `architecture/fixtures/clientisolation`, + a test-only `net/minecraft/client/Minecraft` stand-in like the existing `Level` one), `OverlayGameTests`.

## Step-0 decisions as approved, and how they landed
- Lambda in `OverlayRegistration` calling `ClientOverlay.accept`, with the "must stay a lambda" comment (amendment D); network channel version fixed at `"1"` as `CHANNEL_VERSION`, documented as separate from `OverlayPayload.VERSION`.
- Payload layout: version, body length, body (amendment A). Unknown version skips exactly its body and returns an `unsupported` marker; over-bound lines throw. A hand-built version-2 payload with a trailing marker byte is a GameTest.
- `OverlaySender.install/reset`: test-only Javadoc, `install` while installed throws (tested), every test installs and resets in `finally` in one synchronous block.
- `/pollution overlay on` sends one payload at once to the executing player (same permission and toggle checks); `off` sends nothing.
- GameTest players: `FakePlayer` subclass with a permission-level field, never in the player list or level; `getPlayerCount()` stays 0 and no op entry is written.
- Logout is tested by posting `PlayerEvent.PlayerLoggedOutEvent` by hand (a real logout needs a connection).

## Red evidence
**Plain unit tests** (symbols did not exist yet):
```
OverlayDisplayTest.java:12: error: package OverlayDisplay does not exist
OverlayRecipientsTest.java:15: error: cannot find symbol
OverlayTextTest.java:28: error: cannot find symbol
```
**GameTests**, against stubs with the final signatures and no behaviour (`OverlayPayload` codec threw "stub", `OverlaySender` did nothing, `overlay` had no children, no logout handler):
```
stepnowofthreesendsexactlyone failed at 4911749, -60, -13068138! one payload for the whole step, got 0
alevelzeroplayerisrefusedandtheconsolegetsafailure failed at 4911773, -60, -13068120! the console gets a failure reply, got -1
installingtwicethrowsandresetallowsagain failed at 4911765, -60, -13068111! a second install must throw
aleveltwoplayerwiththeoverlayongetsonepayloadpercommittedinterval failed at 4911773, -60, -13068102! one built and sent at the commit
overlayonandofftoggletheexecutingplayerandonsendsonceatonce failed at 4911789, -60, -13068129! on succeeds
ahandbuiltversiononewithintheboundsdecodes failed at 4911797, -60, -13068111! stub
adeoppedplayerstopsreceivingatonce failed at 4911741, -60, -13068084! only the interval before the de-op, got 0
aversiononepayloadsurvivesencodeanddecode failed at 4911789, -60, -13068084! stub
loggingoutclearsthetoggle failed at 4911741, -60, -13068075! precondition: subscribed
anunknownversiondecodestounsupportedandstopsexactlyatitsend failed at 4911765, -60, -13068075! stub
```
**Absence tests** (they pass against a stub because a stub sends nothing), shown red with mutants, after implementing. Mutant 1: the sender ignores who is subscribed and their level. Mutant 2: the payload line bound raised to 64.
```
toomanylinesortoolongalinethrows failed at 4336364, -60, -92999! decode must reject 33 lines of 5 characters
adeoppedplayerstopsreceivingatonce failed at 4336316, -60, -92981! only the interval before the de-op, got 2
withtheoverlayoffnothingisbuiltorsent failed at 4336348, -60, -92981! no payload was built
alevelzeroplayerwiththeoverlayongetsnothingbuilt failed at 4336348, -60, -92972! level 0 receives nothing and nothing is built
```
Both mutants reverted.
**Static client isolation** (`ClientIsolationTest`): the rules passed on the clean tree; with a deliberate `OverlayEvents.rogue()` calling `ClientOverlay.accept`, the production test failed:
```
Rule 'only mod.overlay.OverlayRegistration may reference mod.client.*' was violated (1 times):
Method <io.github.pollutioncraft.mod.overlay.OverlayEvents.rogue()> calls method <io.github.pollutioncraft.mod.client.Clie...
```
The fixture self-tests (violation names `Rogue`/`TouchesMinecraft` and not the registration; a compliant layout passes) are in the same class.
**Runtime class-load check**, with `ClientOverlay::acceptHandler` (a method reference) in `OverlayRegistration` (ArchUnit allows that line, only the runtime check sees it):
```
* What went wrong:
Execution failed for task ':mod:runGameTestServer' (registered in build file 'mod/build.gradle').
> the dedicated-dist GameTest server loaded client classes: [io.github.pollutioncraft.mod.client.ClientOverlay]. Check that no code outside io.github.pollutioncraft.mod.client touches them, and that OverlayRegistration still uses a lambda.

```
(the GameTests themselves passed 66/66 in that run). Reverted to the lambda. The positive control was also shown red: with the expected name changed to a class that never loads, the run failed with `class-load log … does not list io.github.pollutioncraft.mod.PollutionCraftMod; it cannot prove anything`.

## Measurements (`measureTheSenderCost`; sink replaced by a counter, so network I/O is excluded)
```
PERF 030 sender per interval: 0 subscribers 0.025 us, 1 subscriber 1.2 us, 8 subscribers 7.4 us (= 0.00037 ms/tick at 20 ticks per interval); encode of a 13-line payload 2.0 us each (x8 = 16.4 us)
```
- With 8 subscribed level-2 players: 7.4 us per interval = **0.00037 ms/tick**, plus about 16 us to encode 8 payloads (also per interval); target 0.01 ms/tick. With nobody subscribed the call is a single emptiness check (0.025 us).
- Payload size: typical 13 lines is a few hundred bytes; the cap is 32 x 256 characters.

## Automated versus manual
Automated: codec round trip, unknown-version skip, bounds; sender build and send counts; permission re-check each interval; `stepNow` sends once; command toggling, refusal and console failure; logout clearing; client display rules (plain unit tests); ArchUnit isolation; the dedicated-dist class-load check.
Manual (the user's):
1. In `runClient` as an op: `/pollution overlay on` makes F3 show the `Pollution` section immediately; it changes after `/pollution set 20` and `/pollution step`; `no data` after `/pollution overlay off` and after walking into another chunk until the next interval. The client display class (`ClientOverlay`/`ClientOverlayHud`) has no automated test, because the GameTest server must not load client classes.
2. A real join from a client without the mod (vanilla or NeoForge) is refused, and a client with the mod is refused by a server without it. Read from the sources only.
3. A real dedicated server with this jar (needs the EULA).

## Concerns
- The pack repo README (task 022) must say the mod is required on both sides; this repo's README now does.
- `ClientOverlayHud` appends to the left F3 column after a blank line; it is untested by automation (see manual check 1).
- The 011 rescan measurement printed in the same run showed a max of 115.5 us for one rescan tick (mean 66.9 us) on this machine, above the 0.1 ms figure Heimdall set; that is task 011's code and constant, not changed here.
- `OverlaySender.install/reset` are public so the GameTest source set can reach them; guarded by the throw-on-double-install and test-only Javadoc.
- The 67-test GameTest run takes about 35 s now (14 s at 011); I did not investigate which tests grew.
