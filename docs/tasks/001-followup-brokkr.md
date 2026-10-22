# 001-F (Brokkr): remove the GTM build dependency, add the no-target-mods check

Follow-up to accepted task 001, implementing ADR 0015 ("Compat without build-time dependencies on target mods"), section "What this means for task 001's setup". Read `docs/design.md` first: Heimdall has updated §1, §4, §6 and §9 for ADR 0015, and §10 holds the new rule. Engineers do not commit; Heimdall reviews and commits.

**Ordering:** a single slice. Nothing else runs in parallel on these files. Sindri has no work in this follow-up.

## Goal

No build of ours compiles against, resolves or downloads a target mod (ADR 0015, Decision). `./gradlew build` and `runGameTestServer` need no GTM and no `maven.gtceu.com`. A new build check fails if any target mod lands on any compile classpath. The only GTM pin left is the pack's (task 025).

Satisfies no acceptance criteria directly. It closes task 001's conditions 1 (GTM hash) and 3 (transitive deps); both dissolve under ADR 0015.

## Files owned (exclusive)

- `build.gradle` (root), `mod/build.gradle`, `gradle.properties`, `gradle/libs.versions.toml`
- Any new build-logic file you add for the check, e.g. `gradle/no-target-mods.gradle`. Name it in your report.
- `README.md`

Not yours: `docs/design.md` (Heimdall), `mod/src/test/**`, `mod/src/gametest/**`, `.github/**`, `mod/src/main/**` (including `neoforge.mods.toml`, which stays unchanged).

## Remove

1. `compileOnly` GTM and the `gtmRuntime` configuration, including `sourceSets.main.runtimeClasspath += configurations.gtmRuntime`.
2. The exclusive `GTCEu` ivy repository.
3. The `gtmJarOnly` configuration, the `VerifyGtmHash` class, the `verifyGtmHash` task and its `compileJava`/`check` wiring.
4. `gtceu_sha256` in `gradle.properties`; `gtceu` from the catalog's `[versions]` and `[libraries]`.
5. Every comment that refers to the removed items.

Keep: the `gtceu` optional entry in `neoforge.mods.toml` (runtime metadata), `runGameTestServer` and its "required tests passed" log check, `deployToDevInstance`, everything else.

## Add: no-target-mods build check (ADR 0015, "Decisions recorded" 1 and adapter rule addition 1)

The accepted ADR fixes the shape of the check: **every compile and test classpath may contain only the three allowed classes, enforced by an explicit allowlist of groups. Adding a group is a reviewed change.** The three classes:
- **Internal:** our own subprojects (today the class directories of `core`/`api`/`mod`).
- **Minecraft modding requirements:** Minecraft, NeoForge and FML, ModDevGradle, Parchment, and libraries NeoForge itself ships (Mixin and MixinExtras included).
- **Generic Java utilities:** general-purpose libraries and test libraries (JUnit, ArchUnit, …).

Build a Gradle task `verifyNoTargetModsOnClasspath` (group `verification`), wired into `check` so `./gradlew build` runs it, for every subproject (`core`, `api`, `mod`):

- **Classpaths:** every source set's compile classpath, plus the runtime classpaths of the test source sets (`test`, `gametest`). Iterate the source sets; don't hard-code names, so source sets added later are covered automatically.
- **Module dependencies:** walk the resolution result (`incoming.resolutionResult`, all components, transitives included). Every external module's **group** must match the allowlist (exact groups; prefix patterns only where a family really shares a prefix, and say why in a comment).
- **Non-module entries** (file dependencies, MDG's generated artifacts): allowed only for our own project outputs and the Minecraft/NeoForge artifact that MDG supplies. Identify that artifact from MDG's configuration or artifact location, not by name matching. Anything else fails.
- **Allowlist:** one named list in your build-logic file. Each entry carries its class (internal / modding / generic) and a one-line reason. **Build the initial list from what actually resolves today, after removing GTM.** Put the list with its classification in your report; Heimdall reviews every entry. A group you can't place in one of the three classes is a question for Heimdall, not an entry.
- **Failure message:** names each offending group:module:version (or file), its configuration and subproject, and quotes the rule ("only internal, Minecraft-modding and generic Java dependencies on compile/test classpaths; ADR 0015").
- Configuration-cache compatible: classpath resolution results go in as lazy inputs (providers), no `project` access at execution time. Up-to-date when the classpaths haven't changed.

For context: Heimdall checked the current compile classpaths with GTM still present. The only jars carrying mod metadata are the GTM jar and MDG's `neoforge-21.1.264.jar`. After the removal, no target mod should remain.

## Dev runs with GTM (ADR 0015 table)

The build does nothing for GTM. In the README, document the two ways to run with GTM: drop the jar (taken from the pack's pinned version) into `mod/run/client/mods/` or `mod/run/server/mods/`, or use `deployToDevInstance` (the Prism pack instance already has GTM). **Verify** that an MDG run loads a mod jar from its game directory's `mods/` folder: place the GTM jar from your Gradle cache in `mod/run/server/mods/`, run `./gradlew runServer`, and quote the mod-list line with `gtceu`. Then remove the jar. If FML doesn't load from there, stop and report; don't add a workaround.

## README changes

- The commands table: drop "(with GTM)" from the client/server row; "Headless GameTests (no GTM)" stays.
- Replace the "GregTech Modern pin" section with "Running with target mods": no build dependency on any target mod (ADR 0015); the pack is the only GTM pin; the two dev-run options above.
- In the architecture checks section, add the new build check as a fifth item: "Dependency allowlist: compile and test classpaths hold only internal, Minecraft-modding and generic Java dependencies; no target mod (ADR 0015)". It is a Gradle task, not ArchUnit.

## Tests first (show each red, then green)

1. **Check bites:** implement the check before removing GTM. Run `./gradlew check` with GTM still on `compileOnly` and show it fail, naming `com.gregtechceu.gtceu:gtceu-1.21.1` and `mod`'s `compileClasspath` (GTM's group is not on the allowlist). Then do the removal and show it pass.
2. **Second and third red:** temporarily add (a) an unlisted generic module (e.g. `org.apache.commons:commons-lang3`) as `testImplementation` in `core`: the check names it in `testCompileClasspath`/`testRuntimeClasspath`; (b) the GTM jar as a `files(...)` dependency on `gametestCompileOnly` in `mod`: the check rejects the non-module entry. Remove both afterwards.
3. **No GTM left in the build:** show that `./gradlew :mod:dependencies` lists no `com.gregtechceu` entry, and that `grep -ri gtceu build.gradle mod/build.gradle gradle.properties gradle/libs.versions.toml` returns nothing.

## Commands to run before handing back

- `./gradlew clean build --configuration-cache`: passes; `verifyNoTargetModsOnClasspath` runs for `core`, `api` and `mod`
- `./gradlew test`: passes (15 tests)
- `./gradlew runGameTestServer`: exit 0, "All 1 required tests passed", no `gtceu` in the mod list
- `./gradlew runServer` with GTM in `mod/run/server/mods/`: `gtceu` loads (then remove the jar)
- `./gradlew :mod:jar` + `unzip -l`: unchanged from before (no GTM, no nested jars)
- `git status`: only your owned files changed

## Out of scope (task 012 or later)

The first Mixin config and config plugin; the Mixin, reflection and `…compat.common` ArchUnit rules; checking `remap = false` and refmap needs; `[[mixins]]` in `neoforge.mods.toml`; the with-targets GameTest CI job and its download from the pack's pin; any CI change. If you find anything else that needs changing outside your files, ask Heimdall.
