# Pollution Craft core mod: design excerpts for engineers

Source of truth is the docs repo (`pollution-craft-docs`: `design/architecture.md` §3.1, §3.5, §4, §10 and ADRs 0001, 0005, 0009, 0010, 0011, 0012). This file copies what engineers need. If this file and the docs repo disagree, stop and ask Heimdall.

Status: excerpts as of 2026-10-22. Items marked **PENDING** are open questions with the product architect (Mimir); do not decide them yourself.

## 1. Platform (ADR 0001, accepted)

| Item | Value |
| --- | --- |
| Minecraft | 1.21.1 |
| Loader | NeoForge **21.1.264** (exact pin; bump only on purpose) |
| Java | 21 (Gradle toolchain) |
| Gradle tooling | ModDevGradle (MDG), not ForgeGradle |
| Mappings | Official Mojang names + Parchment for 1.21.1 |
| GTM | GregTech CEu Modern, mod id `gtceu`. A **runtime-only** optional target: never a build dependency of ours (ADR 0015). The only GTM pin (exact build and hash) is the pack's (task 025). |
| Artifact | `pollutioncraft-1.21.1` (one artifact for the whole repo) |
| Mod id | `pollutioncraft` (provisional) |
| Mixins | none in task 001 |

## 2. Repo layout (ADR 0010, ADR 0011 §1)

This repo is the core-mod repo. Three Gradle subprojects, bundled into **one** jar:

| Subproject | Contents | Packages | May reference |
| --- | --- | --- | --- |
| `core` | Plain-Java formulas (internal) | `<base>.core.*` | JDK only |
| `api` | Public contract: interfaces, value types, tag-id constants, events | `<base>.api.*` | Minecraft, NeoForge, JDK. Nothing of ours outside `<base>.api.*` |
| `mod` | Loader entry point, platform side, and alpha-only `…compat.*` adapters | `<base>.mod.*`, `<base>.compat.<modid>.*` | everything above |

- `<base>` = **`io.github.pollutioncraft`** (ADR 0013, proposed; user confirmation pending, build against it now). Maven group is the same (used from task 026). Mod id `pollutioncraft` stays independent of the package.
- Only `pollutioncraft-1.21.1` (plus its standard `-sources` attachment) is produced. No `pollutioncraft-api` artifact, no classifier jars, no jar-in-jar of our own subprojects: `core` and `api` classes are merged into the mod jar.
- Semver covers only `<base>.api.*`. Versions are `0.x` during the alpha.
- Publishing (`maven-publish`, GitHub Packages, releases) is task 026, not now; the layout must allow adding it without restructuring.
- Compat and gap-filler mods live in other repos later. The gap-filler never depends on this mod (ADR 0012).

## 3. Build checks (run by `./gradlew build`)

1. **Core purity:** `<base>.core.*` references no Minecraft (`net.minecraft..`, `com.mojang..`) or loader (`net.neoforged..`) classes.
2. **API purity:** `<base>.api.*` references only Minecraft, NeoForge, the JDK (and libraries those expose in their public signatures), and `<base>.api.*`. Never `<base>.core.*`, `<base>.mod.*`, `<base>.compat.*`, or any other package of ours.
3. **Compat isolation:** nothing in `<base>.mod.*` references `<base>.compat.*`, except the single entry point `<base>.mod.CompatBootstrap` (section 6).
4. **Adapter imports:** classes in `<base>.compat.*` reference, of our packages, only `<base>.api.*` (plus their own `<base>.compat.<modid>` package). Target mods, Minecraft and the loader are allowed. Any other `<base>` package fails the build.

## 4. Adapter rule (ADR 0005, architecture §4)

- Each integration lives in its own package `<base>.compat.<modid>`.
- It loads only after a `ModList` presence check; no class in it is referenced before that check.
- It imports only `<base>.api.*` from our mod.
- Core starts and passes all its tests with no integration target installed.
- Version-sensitive targets carry a version gate (GTM: the 8.0.0 snapshot line; task 012).
- An adapter that throws is disabled for the session and logged; it never crashes the server.
- GTM is declared **optional** in mod metadata: core must load without GTM.
- **No target mod is a build dependency** (ADR 0015, accepted): see §10.

## 5. Conventions (architecture §4)

- Tag and config keys: `snake_case`. Tag namespace `pollutioncraft`.
- Units: pollution in PU (`double`); config intervals in ticks.
- Errors: invalid config rejected at load (default kept, key logged); corrupt save entries skipped and logged; invalid `emit` amounts dropped and logged once per source.
- State: server-authoritative; no client-to-server packets in the alpha.
- Testing: `core` has JUnit 5 tests; platform behaviour has GameTests; compat adapters get GameTests that run only when the target mod is present.
- No secrets or developer-local paths committed (dev instance path, tokens live in `~/.gradle/gradle.properties` or env vars).

## 6. Engineering-lead conventions for task 001 (Heimdall)

- **Gradle:** Groovy DSL (matches the NeoForge MDK). All plugin and library versions pinned in `gradle/libs.versions.toml`; no dynamic versions (`+`, `latest.*`).
- **Subprojects, not source sets**, for `core`/`api`/`mod`. `core` has no Minecraft on its classpath, so the compiler backs up check 1.
- **Compat entry point:** `<base>.mod.CompatBootstrap` is the only `mod` class allowed to reference `<base>.compat.*` (check 3). It is not created in task 001 (no adapters yet); task 012 creates it.
- **Architecture checks:** ArchUnit (JUnit 5) tests in `mod/src/test/java/<base>/architecture/`, run by `mod:test` (so by `build`).
- **GameTests:** live in a `gametest` source set of `mod` (`mod/src/gametest/`), added to the mod for runs only, never packaged into the jar. Namespace `pollutioncraft`.
- **GTM in runs:** never on any classpath. `runGameTestServer` runs without GTM. For dev runs with GTM, put the pack's GTM jar in `mod/run/<side>/mods/` or use `deployToDevInstance` (ADR 0015). The with-targets GameTest job is task 012.
- **Licences:** `LICENSE` = LGPL-3.0 text, `COPYING` = GPL-3.0 text; both also copied into the jar's `META-INF/`. SPDX id in metadata (and any `SPDX-License-Identifier` headers): **`LGPL-3.0-only`** (ADR 0014, refines ADR 0009; user confirmation pending).
- **CI:** GitHub Actions pinned to full commit SHAs; `permissions: contents: read`.

## 7. Dev loop (task 001; architecture §3.7)

| Purpose | Command |
| --- | --- |
| Dev client / server | `./gradlew runClient` / `./gradlew runServer` |
| Headless GameTests (same as CI) | `./gradlew runGameTestServer` (non-zero exit on failure) |
| Test in the real pack | `./gradlew deployToDevInstance` |

`deployToDevInstance` builds the jar, deletes old `pollutioncraft-*.jar` from `<instance>/mods/`, copies the new jar there. The instance path comes from Gradle property `pollutioncraft.devInstance` (in `~/.gradle/gradle.properties`) or env var `POLLUTIONCRAFT_DEV_INSTANCE`; never committed. With no path it fails with a clear message. The dev Prism instance is separate from testers' builds; its worlds are never shared with testers. No pinning or hashing in the inner loop.

## 8. Commands (all must pass locally and in CI)

- `./gradlew build` — compile, unit tests, architecture checks
- `./gradlew test` — unit tests only
- `./gradlew runGameTestServer` — headless GameTests
- `./gradlew deployToDevInstance` — verified by hand, not in CI
- CI: `./gradlew build runGameTestServer` on every push and pull request

## 9. Decisions recorded during task 001 review (Heimdall)

- **Rule 2 is a whitelist.** `<base>.api.*` may depend only on the JDK (`java..`, `javax..`), Minecraft (`net.minecraft..`, `com.mojang..`), NeoForge (`net.neoforged..`), `<base>.api..`, and these libraries that Minecraft/NeoForge expose: Guava, Gson, fastutil, JOML, SLF4J, Log4j, JetBrains annotations, Netty (`EXPOSED_LIBRARIES` in `ArchitectureRules`). Adding a library is an API decision and goes through Heimdall.
- **Rule 4 "own package"** = `<base>.compat.<modid>` and its subpackages; sibling adapters may not reference each other.
- **Test-only stand-in `net.minecraft.world.level.Level`** in `mod/src/test` (Minecraft is not on `mod:test`'s classpath because of an SLF4J strict-version clash with ArchUnit). Whoever first puts Minecraft on `mod:test`'s classpath deletes the stand-in in the same change.
- ~~GTM pin bump~~: superseded by ADR 0015; the build has no GTM pin.

## 10. ADR 0015 (accepted 2026-10-22): no build-time dependency on target mods

- **No build of ours compiles against, resolves or downloads a target mod** (GTM, Mekanism, AE2, the gap-filler as seen from compat, …). This applies in every repo.
- **Allowed build dependencies, the only ones:** (1) internal: our own subprojects and artifacts; (2) Minecraft modding requirements: Minecraft, NeoForge/FML, ModDevGradle, Parchment, and libraries NeoForge ships (Mixin, MixinExtras); (3) generic Java utilities, including test libraries.
- **Enforcement:** the Gradle task `verifyNoTargetModsOnClasspath`, run by `build`, checks every compile and test classpath against an explicit allowlist of groups. Adding a group is a reviewed change (Heimdall).
- Integrations reach target mods only at runtime, via the integration ladder: data mapping, then observe-only string-targeted Mixin, then other NeoForge-typed signals, then reflection as a last resort. The Mixin, reflection and `…compat.common` rules arrive with task 012.
- CI may download the pack's pinned target jars as test input for the with-targets GameTest job (task 012). `./gradlew build` never needs them.

