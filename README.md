# Pollution Craft (core mod)

NeoForge 1.21.1 mod that holds Pollution Craft's pollution simulation, its public API and the alpha-only compat adapters. The design lives in the docs repo (`pollution-craft-docs`); `docs/design.md` here is the excerpt engineers build against.

GregTech Modern (GTM, mod id `gtceu`) is an **optional runtime** integration: the mod loads and passes its tests without it, and the build never depends on it.

## Layout

| Subproject | Contents | Packages |
| --- | --- | --- |
| `core` | Plain-Java formulas (internal) | `io.github.pollutioncraft.core.*` |
| `api` | Public, semver-covered contract | `io.github.pollutioncraft.api.*` |
| `mod` | Loader entry point, platform side, alpha-only compat adapters | `io.github.pollutioncraft.mod.*`, `io.github.pollutioncraft.compat.<modid>.*` |

The three subprojects exist so the compiler enforces the boundaries: `core` has no Minecraft or NeoForge on its classpath, so it cannot reference them. At packaging time they are merged into one jar; nothing is jar-in-jar.

### One artifact

The repo produces exactly one artifact, `pollutioncraft-1.21.1-<version>.jar` (plus its `-sources` jar), containing the `core`, `api` and `mod` classes and `META-INF/LICENSE` and `META-INF/COPYING`. `core:jar` and `api:jar` are never published or used. GTM is never bundled.

## Commands

| Purpose | Command |
| --- | --- |
| Compile, unit tests, architecture checks | `./gradlew build` |
| Unit tests only | `./gradlew test` |
| Dev client / dedicated server | `./gradlew runClient` / `./gradlew runServer` |
| Headless GameTests (no GTM) | `./gradlew runGameTestServer` |
| Build and install into your dev instance | `./gradlew deployToDevInstance` |

Java 21 is required; Gradle provisions the toolchain automatically. Dedicated-server runs need `mod/run/server/eula.txt` containing `eula=true`.

### Architecture checks

Run by `./gradlew build` (1-4 are ArchUnit tests in `mod/src/test`):

1. **Core purity:** `core` references no Minecraft or loader classes.
2. **API purity:** `api` references only Minecraft, NeoForge, the JDK and itself.
3. **Compat isolation:** nothing in `mod` references `compat.*` except `CompatBootstrap`.
4. **Adapter imports:** `compat.*` classes reference, of our packages, only `api.*` and their own package.
5. **Dependency allowlist** (Gradle task `verifyNoTargetModsOnClasspath`, not ArchUnit): compile and test classpaths hold only internal, Minecraft-modding and generic Java dependencies; no target mod (ADR 0015). The allowlist of groups is in `gradle/no-target-mods.gradle`; adding a group is a reviewed change.

## Running with target mods

No build of ours depends on a target mod such as GTM (ADR 0015): `./gradlew build` and `runGameTestServer` need no GTM and no `maven.gtceu.com`. The pack is the only place GTM is pinned (task 025). To run with GTM, either:

- drop the GTM jar (the pack's pinned version) into `mod/run/client/mods/` or `mod/run/server/mods/` and use `runClient` / `runServer`; FML loads mods from the run's `mods/` folder (a dropped-in GTM loads together with its bundled ModularUI and Configuration); or
- use `./gradlew deployToDevInstance` and start the Prism pack instance, which already has GTM.

## Dev instance

`deployToDevInstance` builds the jar, deletes old `pollutioncraft-*.jar` files from `<instance>/mods/`, and copies the new one in. Set the instance directory in `~/.gradle/gradle.properties`:

```properties
pollutioncraft.devInstance=/path/to/prism/instances/pollutioncraft-dev
```

or export `POLLUTIONCRAFT_DEV_INSTANCE`. Never commit the path. Use a Prism instance separate from testers' instances, and never share its worlds with testers.

## Licence

Pollution Craft is licensed under the GNU Lesser General Public License v3.0 only (`LGPL-3.0-only`); see `LICENSE`. `COPYING` holds the GNU GPL v3.0 text that the LGPL builds on. Both ship in the jar under `META-INF/`.
