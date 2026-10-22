# 001-A report (Brokkr)

**Status: DONE_WITH_CONCERNS.** Everything in the brief is built and verified, except that the GTM hash is *observed*, not confirmed (see Concerns).

## Files
`settings.gradle`, `build.gradle`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/**` (Gradle 9.6.0, distribution SHA-256 pinned), `gradlew(.bat)`, `core/build.gradle`, `core/src/{main,test}` (`CoreInfo`, `ScaffoldSmokeTest`), `api/build.gradle`, `api/src/main/.../api/package-info.java`, `mod/build.gradle`, `mod/src/main/java/.../mod/PollutionCraftMod.java`, `mod/src/main/resources/META-INF/neoforge.mods.toml`, `.gitignore`, `.gitattributes`, `LICENSE` (LGPL-3.0, gnu.org), `COPYING` (GPL-3.0, gnu.org), `README.md`. The GTM hash lives in `gradle.properties` (`gtceu_sha256`), checked by the `verifyGtmHash` task in `mod/build.gradle`; no `verification-metadata.xml`.

## Red/green evidence
- Smoke test: first run failed at `compileTestJava`: `cannot find symbol: variable CoreInfo`. After adding `CoreInfo` it passed (`ScaffoldSmokeTest > coreIsWiredIntoTheUnitTestLayer() PASSED`). With a wrong expected value it failed: `expected: <wrong> but was: <pollutioncraft-core>`.
- GTM hash: `-Pgtceu_sha256=deadbeef` -> `GTM jar gtceu-1.21.1-8.0.0-SNAPSHOT.jar has SHA-256 057f8e97…1011, expected deadbeef … Refusing to build against an unpinned GTM.` BUILD FAILED. Real hash -> passes.
- `deployToDevInstance`: no path -> `No dev instance configured. Set Gradle property pollutioncraft.devInstance … or env var POLLUTIONCRAFT_DEV_INSTANCE … See README.md, section "Dev instance".`; instance without `mods/` -> `Dev instance has no mods directory: …`; with fake `pollutioncraft-0.0.0.jar` and `other.jar` in `mods/` -> old jar gone, `other.jar` kept, `pollutioncraft-1.21.1-0.1.0-SNAPSHOT.jar` present.

## Final verification (fresh runs)
- `./gradlew clean build --configuration-cache`: BUILD SUCCESSFUL; entry stored; second run "Configuration cache entry reused", no problems (also with `deployToDevInstance`).
- `:mod:jar` contents: `core/CoreInfo.class`, `api/package-info.class` (forced with `-Xpkginfo:always`), `mod/PollutionCraftMod.class`, `META-INF/neoforge.mods.toml`, `META-INF/LICENSE`, `META-INF/COPYING`. No GTM classes, no nested jars. Sources jar has the sources of all three plus the licences.
- `runServer`: "Done (0.726s)!" with `gtceu 8.0.0-SNAPSHOT+36a6b38`, `pollutioncraft`, `configuration`, `modularui` in the mod list. (`runClient` not launched: headless box.)
- `runGameTestServer`: no GTM in the mod list; fails only with `IllegalArgumentException: No test functions were given!`.

## Findings
- **Maven-style ranges:** NeoForge accepts them for *required* dependencies. `neoforge` is required with `[21.1.264,21.2)` and `minecraft` with `[1.21.1,1.22)`; the mod loaded in dev server with both. So `[0.1,1)`-style ranges are fine.
- GTM's transitive deps need extra repositories (copied from GTM's own build), all exclusive-content filtered: Registrate (`mvn.devos.one/snapshots`), Configuration (`repo.repsy.io/mvn/toma/public`), ModularUI (`maven.gtceu.com`).

## Decisions / deviations
- Java sources use `io.github.pollutioncraft` and SPDX `LGPL-3.0-only` as instructed (both still "proposed").
- No `localRuntime` configuration exists in MDG 2.0.148, so GTM is a `gtmRuntime` configuration appended to `sourceSets.main.runtimeClasspath` (client/server only). `additionalRuntimeClasspathConfiguration` did not make GTM load. The `gametest` source set needed `addModdingDependenciesTo` to get Minecraft.
- `core`/`api` reach `mod` as class-directory outputs (`implementation coreMain.output`), not project jars.
- Parchment pinned to `2024.11.17` (latest for 1.21.1). MDG 2.0.148, JUnit 6.1.3, foojay resolver 1.0.0 (Java 21 toolchain auto-provisioned; only JDK 17/25 were installed).
- README mentions the architecture checks and `CompatBootstrap` as Sindri's work to come.

## Concerns for the reviewer
1. **GTM hash is not confirmed.** The pin `…+20261017-095459-36a6b38` is not a Maven coordinate. `maven.gtceu.com` serves snapshot `20261017.095456-124`, whose `neoforge.mods.toml` reports `8.0.0-SNAPSHOT+36a6b38` (same git hash; 3 s timestamp difference). Its SHA-256 is `057f8e9750b750bf020340a3d8ecdde0fe183c7a3a1ac1afae29777e031a1011` (matches the maven `.sha256`). I put that in `gradle.properties`. **Need:** Heimdall to compare with `mods/gtceu.pw.toml` and confirm or supply the correct hash. If it differs, the pack jar needs re-hosting (URL + SHA-256).
2. **Snapshot drift:** the dependency is `8.0.0-SNAPSHOT`, so if GTM publishes build 125 the build fails on the hash (loud, but breaks until updated), and the hash only pins the GTM jar itself. Its transitive deps (ModularUI `3.3.1-SNAPSHOT`, EvalEx, Registrate, Configuration) are not hash-pinned. Worth a decision on whether the pack pin needs to cover them.
3. `mod/run/` holds run output (gitignored). I wrote `mod/run/server/eula.txt` locally; it is not committed.
4. I used `/tmp/claude-1000/dev1` and `dev0` for the deploy checks (outside the repo); a hook blocked my `rm -rf` of them, so they remain.

## Round 2 (review changes) — all four items fixed
1. **GTM pin:** version is now the unique snapshot `8.0.0-20261017.095456-124` (catalog), fetched by an exclusive ivy-pattern repo to the exact timestamped file (Gradle's maven layout cannot request a unique snapshot directly). GTM is resolved **non-transitively**: its jar-in-jar (checked: `META-INF/jarjar/` holds ModularUI 3.3.1-SNAPSHOT, Configuration, Registrate) covers runtime. `runServer` still reaches "Done" with `gtceu`, `configuration`, `modularui` loaded. The Devos/Repsy repos and the ModularUI filter were removed. Registrate `MC1.21-1.3.0+67` is a fixed version, not a floating snapshot. Hash still the unconfirmed observed one. The local cache file keeps the older name `gtceu-1.21.1-8.0.0-SNAPSHOT.jar`; the fetched URL is the timestamped one (`--info`: "Cached resource …8.0.0-20261017.095456-124.jar").
2. **ArchUnit:** `archunit-junit5` 1.5.1 in the catalog and on `mod`'s `testImplementation`; verified in `testCompileClasspath`.
3. **Delete failures:** `deployToDevInstance` collects files whose `delete()` fails and throws `Could not delete old jar(s): … refusing to leave two versions in mods/.` Shown with a read-only `mods/`.
4. **GameTest exit code:** I earlier wrote "fails" imprecisely. Reproduced the red in a scratch copy with an empty gametest source set (Sindri's test is now in the tree): `BUILD SUCCESSFUL`, exit 0 despite `No test functions were given!`. Fix in `mod/build.gradle`: delete the run's `latest.log` before, and after the run require `required tests passed` in it, else throw. Now exit 1 with `runGameTestServer exited normally but never reported 'required tests passed'`. Green: scratch copy with a passing test -> `All 1 required tests passed :)`, exit 0. With the real tree (Sindri's deliberately failing test) the run exits 1 from vanilla itself.
5. Nit: `apiMain.allSource` used.

`./gradlew clean build --configuration-cache` passes after all changes. Scratch copy lives in the session scratchpad; the `/tmp/claude-1000/dev*` dirs are still around.

## Follow-up (001-F: GTM removed, allowlist check added)

New build-logic file: `gradle/no-target-mods.gradle` (applied to every subproject from root `build.gradle`). Removed from `mod/build.gradle`, `gradle.properties`, `gradle/libs.versions.toml`: `compileOnly` GTM, `gtmRuntime`, the ivy repo, `gtmJarOnly`, `VerifyGtmHash`/`verifyGtmHash`, `gtceu_sha256`, catalog entries and their comments. `grep -ri gtceu` over the four files: nothing; `:mod:dependencies`: no `com.gregtechceu`. README updated as specified (README still mentions `gtceu` as the optional runtime mod).

**Check design.** Task `verifyNoTargetModsOnClasspath` (group verification) per subproject, wired into `check`. Covers compile classpath of every source set plus runtime classpath of every non-main source set (`configureEach`, so later source sets are covered). Walks `incoming.resolutionResult` (all transitives) and checks each module's exact group against the allowlist; `resolvedArtifacts` entries that are neither module nor project components must sit under our `build/classes|resources` or MDG's `build/moddev` directory. Lazy providers as inputs, configuration cache reused, up to date when classpaths are unchanged. Note: MDG's Minecraft/NeoForge artifact resolves as a normal module (`net.neoforged:neoforge`), so the file branch currently never matches MDG; it exists for the brief's rule.

**Red/green evidence**
1. GTM still on `compileOnly`, check added: `check` failed: `com.gregtechceu.gtceu:gtceu-1.21.1:8.0.0-20261017.095456-124 (group 'com.gregtechceu.gtceu' not on the allowlist) in :mod compileClasspath` (the only offender once the allowlist was filled). After removal: passes for core, api, mod.
2. (a) `org.assertj:assertj-core` as `testImplementation` in core: failed naming `assertj-core` and transitive `net.bytebuddy:byte-buddy` in `:core testCompileClasspath` and `testRuntimeClasspath`. I did **not** use commons-lang3 as the brief suggested: `org.apache.commons` is legitimately allowlisted (Minecraft ships it), so it would pass. (b) GTM jar as `files(...)` on `gametestCompileOnly`: failed `file …gtceu-1.21.1-8.0.0-SNAPSHOT.jar (not our output, not MDG's artifact) in :mod gametestCompileClasspath`. Both reverted.

**Fresh results:** `clean build --configuration-cache` passes (check runs for core, api, mod; second run reuses the cache); `test --rerun-tasks`: 15 passed; `runGameTestServer`: `All 1 required tests passed :)`, exit 0, mod list has no `gtceu`; jar contents unchanged (no GTM, no nested jars). **GTM drop-in:** GTM jar copied to `mod/run/server/mods/`, `runServer` reached Done with `GregTech 8.0.0-SNAPSHOT+36a6b38 (gtceu)` plus ModularUI and Configuration in the mod list; jar removed afterwards.

**Bug found on the way:** a closure capturing the `Configuration` broke under the configuration cache ("Cannot cast 'file collection' to Configuration"); fixed by capturing only the name. Also `dependsOn(cfg)` is needed so MDG's generated artifacts exist after `clean`.

**Allowlist for Heimdall's review** (exact groups; class: internal entries are project components/output dirs and need no group):

| Group | Class | Reason |
| --- | --- | --- |
| `com.mojang` | modding | the Minecraft libraries (authlib, brigadier, DFU, logging, ...) and the Minecraft artifacts |
| `net.neoforged` | modding | NeoForge, its bus, coremods, DevLaunch, JarJar and NeoForm artifacts |
| `net.neoforged.accesstransformers` | modding | NeoForge access transformers (at-modlauncher) |
| `net.neoforged.fancymodloader` | modding | FML loader and early display |
| `cpw.mods` | modding | modlauncher / securejarhandler / bootstraplauncher, shipped by NeoForge |
| `net.fabricmc` | modding | sponge-mixin, the Mixin implementation NeoForge ships |
| `io.github.llamalad7` | modding | MixinExtras, shipped by NeoForge |
| `net.minecraftforge` | modding | srgutils, a NeoForm/NeoForge toolchain library |
| `net.minecrell` | modding | terminalconsoleappender, Minecraft server console |
| `org.ow2.asm` | modding | ASM, used by FML/modlauncher and Mixin |
| `org.lwjgl` | modding | LWJGL, the Minecraft native bindings |
| `io.netty` | modding | Netty, Minecraft networking |
| `it.unimi.dsi` | modding | fastutil, Minecraft dependency |
| `org.joml` | modding | JOML, Minecraft dependency |
| `ca.weblite` | modding | java-objc-bridge, Minecraft dependency |
| `com.github.oshi` | modding | oshi-core, Minecraft dependency |
| `net.java.dev.jna` | modding | JNA, Minecraft dependency |
| `com.ibm.icu` | modding | icu4j, Minecraft dependency |
| `com.machinezoo.noexception` | modding | noexception, Minecraft dependency |
| `org.jcraft` | modding | jorbis, Minecraft dependency |
| `org.lz4` | modding | lz4-java, Minecraft dependency |
| `org.apache.httpcomponents` | modding | httpclient/httpcore, Minecraft dependency |
| `org.apache.commons` | modding | commons-lang3/-compress, Minecraft dependency |
| `commons-codec` | modding | Minecraft dependency |
| `commons-io` | modding | Minecraft dependency |
| `commons-logging` | modding | Minecraft dependency |
| `org.apache.logging.log4j` | modding | Log4j, Minecraft/NeoForge logging |
| `com.google.guava` | modding | Guava, Minecraft dependency |
| `com.google.code.gson` | modding | Gson, Minecraft dependency |
| `com.google.code.findbugs` | modding | jsr305 annotations via Guava/Minecraft |
| `com.google.errorprone` | modding | annotations via Guava |
| `com.google.j2objc` | modding | annotations via Guava |
| `org.checkerframework` | modding | annotations via Guava |
| `org.slf4j` | modding | SLF4J, Minecraft logging facade |
| `org.jline` | modding | JLine, Minecraft/NeoForge console |
| `net.sf.jopt-simple` | modding | jopt-simple, Minecraft launch arguments |
| `net.jodah` | modding | typetools, NeoForge event bus |
| `org.antlr` | modding | antlr4-runtime, NeoForge/NeoForm dependency |
| `org.openjdk.nashorn` | modding | Nashorn, FML coremods |
| `com.electronwill.night-config` | modding | NightConfig (core, toml), FML config/metadata parsing |
| `org.apache.maven` | modding | maven-artifact, FML version ranges |
| `org.codehaus.plexus` | modding | plexus-utils via maven-artifact |
| `org.jetbrains` | generic | JetBrains annotations, Minecraft and general Java |
| `org.junit` | generic | JUnit BOM (test library) |
| `org.junit.jupiter` | generic | JUnit Jupiter (test library) |
| `org.junit.platform` | generic | JUnit Platform (test library) |
| `org.opentest4j` | generic | JUnit assertion types (test library) |
| `org.apiguardian` | generic | API annotations used by JUnit |
| `org.jspecify` | generic | nullness annotations used by JUnit |
| `com.tngtech.archunit` | generic | ArchUnit (architecture test library) |

Questions: most `modding` rows are transitive Minecraft/NeoForge libraries I classified by provenance (they resolve through `net.neoforged:neoforge`/`minecraft-dependencies`); `org.jetbrains` and `com.google.*` annotation groups are shared with generic use. Exact-group matching means a group allowed for `mod` is also allowed in `core`'s classpath (core has no Minecraft, so only generic ones ever resolve there); per-subproject allowlists would be stricter if you want them.
