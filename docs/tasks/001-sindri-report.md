# 001-B report (Sindri)

**Status: DONE_WITH_CONCERNS.** Nothing committed. Nothing outside my owned paths changed in the real tree.

## Files
- `mod/src/gametest/java/io/github/pollutioncraft/gametest/PollutionCraftGameTests.java`
- `mod/src/gametest/resources/data/pollutioncraft/structure/empty.nbt`
- `mod/src/test/java/io/github/pollutioncraft/architecture/{ArchitectureRules,ArchitectureRulesSelfTest,ProductionArchitectureTest}.java`
- `mod/src/test/java/io/github/pollutioncraft/architecture/fixtures/**` (violation / compliant sets per rule, plus `apipurity.violationlibrary` and `adapterimports.crosscompat`)
- `mod/src/test/java/net/minecraft/world/level/Level.java` (stand-in, see Concerns 1)
- `.github/workflows/ci.yml`

## Red -> green
**GameTest** (`@GameTestHolder("pollutioncraft")`, `@PrefixGameTestTemplate(false)`, template `pollutioncraft:empty`):
1. No structure yet: startup crash `IllegalStateException: Missing test structure: pollutioncraft:empty`, `BUILD FAILED`, exit 1.
2. Template added, wrong assertion (DIRT): `placedblockispresent failed ... Expected Dirt, got Stone at ... (relative: 0,1,0)`, `1 required tests failed :(`, exit 1.
3. Fixed (STONE): `All 1 required tests passed :)`, exit 0. Mod list has `pollutioncraft` and no `gtceu`.
- Template: a hand-written minimal 3x3x3 `empty.nbt` (one air block). Vanilla's `minecraft:empty` is not available in 1.21.1, so it lives in our namespace.
- Startup crashes after Brokkr's fix (his `required tests passed` log check): with the gametest source removed, `No test functions were given!` -> `runGameTestServer exited normally but never reported 'required tests passed'`, exit 1 (run in a scratch copy). Before his fix, the same run and a `session.lock` "already locked" startup failure both gave exit 0 (seen in this session). I could not reproduce the lock case against the fixed build (two overlapping runs serialised); it is covered by the same log check in principle but unproven.

**Architecture rules.** The first run was a compile failure (`ArchitectureRules` missing). Each rule then fails on its violation fixture and passes on its compliant one (10 self-tests, naming the offender and not the innocent class). Real-code violations ran in a **scratch copy**, as classes placed in `mod/src/main` under the package that is checked (core and api cannot compile the violation themselves, which is a second backstop for rule 1/2). Each ended `./gradlew build` with exit 1:
1. `Rule 'core.* must not reference Minecraft or loader classes' was violated (1 times): Field <io.github.pollutioncraft.core.Scratch.level> has type <net.minecraft.world.level.Level>`
2. `Rule 'api.* must reference only the JDK, Minecraft, NeoForge and api.*' ...: Field <...api.Scratch.info> has type <...core.CoreInfo>`
3. `Rule 'only mod.CompatBootstrap may reference compat.*' ...: Field <...mod.Scratch.a> has type <...compat.gtceu.Adapter>`
4. `Rule 'compat.* may reference only api.* and its own compat.<modid> package' ...: Field <...compat.gtceu.Scratch.info> has type <...core.CoreInfo>`

## Final runs (fresh)
- `./gradlew clean build runGameTestServer`: BUILD SUCCESSFUL; `All 1 required tests passed :)`.
- `./gradlew test --rerun-tasks`: exit 0; 14 architecture tests passed (10 self-tests + 4 production) among 15 PASSED lines.
- `:mod:jar`: classes are only `mod/PollutionCraftMod`, `core/CoreInfo`, `api/package-info`; no gametest, architecture or `net/minecraft` entries.
- `git status`: my changes are only the owned paths above (`.github/`, `mod/src/test`, `mod/src/gametest`).

## Decisions / ambiguities for Heimdall
- **Rule 2 is a whitelist** ("references only"): JDK, Minecraft, NeoForge, `api.*`, plus libraries those expose (guava, gson, fastutil, joml, slf4j, log4j, jetbrains annotations, netty; constant `EXPOSED_LIBRARIES`). I picked this reading and the list; please confirm or give the list. The alternative is forbidding only our own other packages.
- Rule 4 "own package" = `<base>.compat.<modid>` including subpackages; a class directly in `compat` may use only `api`. A sibling `compat.other` is a violation (tested).
- Rules 3 and 4 use `allowEmptyShould(true)` because there are no compat classes until task 012.
- Production import excludes tests via `DoNotIncludeTests` plus an explicit `/architecture/fixtures/` filter.
- CI: SHAs resolved with `git ls-remote` (peeled for annotated tags): checkout v7.0.1 `3d3c42e5`, setup-java v6.1.0 `b843ebf9`, setup-gradle (gradle/actions) v6.4.0 `3f5f9ada`, upload-artifact v7.0.2 `cf430e03`. Failure artifact `failure-diagnostics` has test reports/results and `mod/run/gametest/{logs,crash-reports}`. Only YAML-parse checked.

## Concerns
1. **Build change wanted:** Minecraft is not on `mod:test`'s compile classpath. Adding `addModdingDependenciesTo sourceSets.test` fails resolution (slf4j `2.0.9` strictly from Minecraft vs `2.0.19` from ArchUnit). I avoided a build change with a stand-in `net.minecraft.world.level.Level` in the test source set (rules only look at package names). If Brokkr later puts real Minecraft on the test classpath, delete the stand-in (duplicate class). Alternative: Brokkr adds a slf4j constraint.
2. **CI not run:** no `gh`, `act` or docker here, and no push. Heimdall needs to run the red and green CI proof.
3. **Shared run dir:** Brokkr's runs and mine collided on `mod/run/gametest/world/session.lock` once; his `doFirst` deletes `latest.log`, which another live run could be writing. Not a CI issue.
4. The GTM maven repo was reachable anonymously in these runs, but the CI environment is untested.
