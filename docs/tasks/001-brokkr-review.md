# 001-A review (Heimdall) — CHANGES REQUESTED

Verified myself: `clean build --configuration-cache` passes (CC reused); `test --rerun-tasks` runs the smoke test and it passes; jar holds the merged core/api/mod classes plus META-INF/LICENSE and COPYING, with no GTM and no nested jars; licence texts match gnu.org; a wrong hash fails the build; deploy fails clearly with no path and replaces the old jar while keeping other.jar; runServer reaches "Done" with gtceu, configuration and modularui loaded; runGameTestServer loads without GTM.

Required:
1. `gradle/libs.versions.toml:8` — `8.0.0-SNAPSHOT` is a dynamic version: it resolves to whichever build is newest and is cached for 24h, which breaks design §6 ("no dynamic versions"). Pin the unique snapshot `8.0.0-20261017.095456-124`. GTM's transitive snapshots (ModularUI `3.3.1-SNAPSHOT`, Registrate) are dynamic too: pin each to its unique timestamped version with a constraint, or resolve GTM non-transitively if its jar-in-jar covers runtime (show runServer still loads). Report which. The hash itself stays unconfirmed until the pack's hash arrives; that is not on you.
2. `mod/build.gradle:107-109` — the brief promised `archunit-junit5` on mod's test classpath for 001-B, and it is missing. Add it via the catalog with a pinned version.
3. `mod/build.gradle:223-224` — `it.delete()` ignores its result. If an old jar is locked (Prism running), two versions end up in mods/. Fail with a clear message when a delete fails.
4. `runGameTestServer` crashed (`IllegalArgumentException: No test functions were given!`) and Gradle still reported BUILD SUCCESSFUL with exit 0, so CI would pass on a startup crash. Your report said it "fails"; please be precise. Make a server that crashes on startup or never runs its tests exit non-zero, using a mechanism in `mod/build.gradle`. Show red with the current empty source set. If MDG offers no clean way, report back with the options instead of hacking it.

Nit: `mod/build.gradle:171`, use `apiMain.allSource` to match line 170.
