# 001-A (Brokkr): Gradle skeleton, single jar, metadata, GTM pin, dev loop

Part of task 001 (core mod scaffold, test harness, CI). Read `docs/design.md` first; it is all the design you need. Engineers do not commit; Heimdall reviews and commits.

**Ordering:** this slice lands **first**. Sindri's slice (001-B: architecture checks, GameTest, CI) builds on your skeleton and starts once Heimdall approves this one.

**Blocker:** do not create Java sources until the `<base>` package prefix is filled into `docs/design.md` §2 (pending with Mimir). Gradle wiring that does not need it can start now.

## Goal

An empty but buildable core mod: three subprojects bundled into one jar `pollutioncraft-1.21.1`, unit-test layer running, GTM available at its pinned build, dev runs and `deployToDevInstance` working. Enables every later task to start test-first. No acceptance criteria directly (enables M1–M4 testing).

## Design references

`docs/design.md` §1 (platform), §2 (layout), §4 (GTM optional), §6 (lead conventions), §7 (dev loop), §8 (commands).

## Files owned (exclusive)

- `settings.gradle`, `build.gradle` (root), `gradle.properties`
- `gradle/libs.versions.toml`, `gradle/wrapper/**`, `gradlew`, `gradlew.bat`
- `gradle/verification-metadata.xml` or another file you add for the GTM hash check (name it in your report)
- `core/build.gradle`, `core/src/**`
- `api/build.gradle`, `api/src/**`
- `mod/build.gradle`, `mod/src/main/**`
- `.gitignore`, `.gitattributes`
- `LICENSE`, `COPYING`, `README.md`

Not yours (Sindri, 001-B): `mod/src/test/**`, `mod/src/gametest/**`, `.github/**`. Do not create files there; your build must work with them empty or absent.

## What to build

1. **Gradle multi-project** (Groovy DSL, Gradle wrapper pinned, versions in the catalog): subprojects `core`, `api`, `mod`. Java 21 toolchain everywhere.
   - `core`: plain `java-library`, **no** Minecraft/NeoForge on any classpath. JUnit 5 (Jupiter) on `testImplementation`.
   - `api`: compiles against Minecraft + NeoForge 21.1.264 (MDG). Contains only a `package-info.java` for `<base>.api` stating it is the public, semver-covered contract. Depends on nothing of ours.
   - `mod`: MDG, NeoForge 21.1.264, Parchment for 1.21.1 (pick the latest Parchment release for 1.21.1 and pin it). Depends on `core` and `api`.
2. **One jar.** `mod:jar` produces `pollutioncraft-1.21.1-<version>.jar` containing `core`, `api` and `mod` classes merged (no jar-in-jar of our subprojects), plus `LICENSE` and `COPYING` under `META-INF/`. A merged sources jar the same way. `core:jar`/`api:jar` are never used as published outputs. Version `0.1.0-SNAPSHOT` in `gradle.properties`.
   - In dev runs, all three subprojects must load as the single mod `pollutioncraft` (MDG `mods { pollutioncraft { sourceSet … } }`).
3. **Mod entry point** `<base>.mod.PollutionCraftMod`, `@Mod("pollutioncraft")`, `MOD_ID` constant, logs one line on construction. Nothing else.
4. **Metadata** `mod/src/main/resources/META-INF/neoforge.mods.toml` (expanded from Gradle properties): mod id, version, licence (design §6), `neoforge` and `minecraft` dependencies with ranges, and **`gtceu` as `type="optional"`** with `versionRange="*"` (the real version gate is task 012). Also check and report whether NeoForge metadata accepts Maven-style ranges like `[0.1,1)` for a required dependency (ADR 0011 §3 asked task 001 to verify).
5. **GTM dependency** at `8.0.0-SNAPSHOT+20261017-095459-36a6b38`:
   - First check whether `https://maven.gtceu.com` serves that exact build (coordinates pattern `com.gregtechceu.gtceu:gtceu-1.21.1:<version>`). If yes, use it with an exclusive-content repository filter. If not, use the jar re-hosted by pack task 025 (URL from Heimdall). If neither exists yet, **stop on this item and report**; finish the rest.
   - The build must fail if the jar's SHA-256 differs from the recorded hash (hash from Heimdall; it is the one in the pack's `mods/gtceu.pw.toml`). Show it failing once with a wrong hash.
   - On `mod`'s compile classpath. Present in `runClient`/`runServer`. **Absent** from `runGameTestServer` (design §6). Not bundled into our jar.
6. **Runs:** `runClient`, `runServer`, `runGameTestServer`. Create an empty `gametest` source set in `mod` (compiles against `mod` main), added to the `pollutioncraft` mod for runs only, excluded from `jar`. `runGameTestServer` sets `neoforge.enabledGameTestNamespaces=pollutioncraft`. Give the GameTest run its own game directory so it never sees GTM or client run files. (With no GameTests yet, the run is expected to fail with vanilla's "no test batches" error; Sindri adds the first test.)
7. **`deployToDevInstance`** (design §7): depends on `mod:jar`; reads `pollutioncraft.devInstance` property, else `POLLUTIONCRAFT_DEV_INSTANCE`; fails with a message naming both and the README section if neither is set, or if `<instance>/mods` does not exist; deletes `pollutioncraft-*.jar` in `<instance>/mods/`, then copies the new jar. Configuration-cache friendly (no `project` access at execution time).
8. **Licences:** `LICENSE` = verbatim LGPL-3.0 text, `COPYING` = verbatim GPL-3.0 text (from gnu.org).
9. **README.md:** what the repo is; layout table and why subprojects (one paragraph: three subprojects merged into one jar; `core` has no MC on its classpath); the one-artifact rule; build/test/GameTest commands; the four architecture checks (list them as in design §3; Sindri implements them); dev-instance setup (Prism instance path in `~/.gradle/gradle.properties` or env var, separate from testers' instances, worlds never shared); licence notice; GTM optional.

## Tests to write first

1. `core/src/test/java/<base>/core/ScaffoldSmokeTest.java` (JUnit 5): write it so it fails first (e.g. assert against a not-yet-existing trivial `core` class or a wrong value), run `./gradlew :core:test`, see it fail, then make it pass. Keep the passing test as the unit-layer smoke test, with a tiny real `core` class it exercises (e.g. `<base>.core.CoreInfo` with a version-independent constant). No Minecraft types.
2. GTM hash check: wrong hash → build fails; correct hash → passes.
3. `deployToDevInstance` by hand: (a) no path → clear failure; (b) temp dir with `mods/` holding a fake `pollutioncraft-0.0.0.jar` → old jar gone, new jar present.

Report how you saw each fail (paste the relevant output lines).

## Commands to run before handing back

- `./gradlew build` — passes
- `./gradlew test` — passes, smoke test reported
- `./gradlew :mod:jar` then `unzip -l mod/build/libs/pollutioncraft-1.21.1-*.jar` — shows `core`, `api`, `mod` classes, `META-INF/neoforge.mods.toml`, `META-INF/LICENSE`, `META-INF/COPYING`; no GTM classes, no nested jars of ours
- `./gradlew runServer` — dedicated server reaches "Done" with `pollutioncraft` and `gtceu` loaded (then `stop`)
- `./gradlew runGameTestServer` — launches without GTM and fails only with the "no tests" error
- `./gradlew deployToDevInstance` — the manual checks above
- `./gradlew build --configuration-cache` — no configuration-cache problems from your tasks

## Shared interfaces

Provides the layout, build and runs that 001-B and tasks 003–026 consume. Defines no domain interfaces (task 005).

## Out of scope

Architecture checks, GameTests, CI (001-B). Pollution logic, `CompatBootstrap`, any `compat` package, the GTM adapter (tasks 003/012). Publishing, `maven-publish`, API binary-compat check (task 026). Mixins.

If you need anything outside your files, or hit a design gap, ask Heimdall; do not decide it.
