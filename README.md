# Pollution Craft (core mod)

NeoForge 1.21.1 mod that holds Pollution Craft's pollution simulation, its public API and the alpha-only compat adapters. The design lives in the docs repo (`pollution-craft-docs`); `docs/design.md` here is the excerpt engineers build against.

GregTech Modern (GTM, mod id `gtceu`) is an **optional** dependency: the mod loads and passes its tests without it.

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
| Dev client / dedicated server (with GTM) | `./gradlew runClient` / `./gradlew runServer` |
| Headless GameTests (no GTM) | `./gradlew runGameTestServer` |
| Build and install into your dev instance | `./gradlew deployToDevInstance` |

Java 21 is required; Gradle provisions the toolchain automatically. Dedicated-server runs need `mod/run/server/eula.txt` containing `eula=true`.

### Architecture checks

Run by `./gradlew build` (ArchUnit, in `mod/src/test`):

1. **Core purity:** `core` references no Minecraft or loader classes.
2. **API purity:** `api` references only Minecraft, NeoForge, the JDK and itself.
3. **Compat isolation:** nothing in `mod` references `compat.*` except `CompatBootstrap`.
4. **Adapter imports:** `compat.*` classes reference, of our packages, only `api.*` and their own package.

## GregTech Modern pin

GTM `8.0.0-SNAPSHOT+36a6b38` is pinned to the exact snapshot build `8.0.0-20261017.095456-124` on `maven.gtceu.com`, resolved non-transitively (its jar-in-jar carries ModularUI, Configuration and Registrate). The build fails unless the resolved jar's SHA-256 equals `gtceu_sha256` in `gradle.properties` (task `verifyGtmHash`). It is on the compile classpath and in `runClient`/`runServer`, and absent from `runGameTestServer`.

## Dev instance

`deployToDevInstance` builds the jar, deletes old `pollutioncraft-*.jar` files from `<instance>/mods/`, and copies the new one in. Set the instance directory in `~/.gradle/gradle.properties`:

```properties
pollutioncraft.devInstance=/path/to/prism/instances/pollutioncraft-dev
```

or export `POLLUTIONCRAFT_DEV_INSTANCE`. Never commit the path. Use a Prism instance separate from testers' instances, and never share its worlds with testers.

## Licence

Pollution Craft is licensed under the GNU Lesser General Public License v3.0 only (`LGPL-3.0-only`); see `LICENSE`. `COPYING` holds the GNU GPL v3.0 text that the LGPL builds on. Both ship in the jar under `META-INF/`.
