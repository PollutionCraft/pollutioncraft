# 001-B (Sindri): architecture checks, first GameTest, CI

Part of task 001 (core mod scaffold, test harness, CI). Read `docs/design.md` first. Engineers do not commit; Heimdall reviews and commits.

**Ordering:** starts **after** Brokkr's slice 001-A (Gradle skeleton, single jar, runs) is approved by Heimdall. You work on top of that tree. You may read the brief and prepare now, but every red/green step must be run against the real build.

## Goal

Both test layers proven and the module contract enforced: the four architecture checks run in `./gradlew build`, a trivial GameTest runs headless via `./gradlew runGameTestServer` with a non-zero exit on failure, and CI runs `./gradlew build runGameTestServer` on every push and pull request.

## Design references

`docs/design.md` §2 (packages), §3 (the four checks: implement exactly these), §4 (adapter rule), §6 (lead conventions: ArchUnit location, `CompatBootstrap`, GameTest source set, GTM absent from GameTest run, CI rules), §8 (commands).

## Files owned (exclusive)

- `mod/src/test/**` (architecture checks and their fixtures)
- `mod/src/gametest/**` (GameTest code and resources)
- `.github/**` (CI workflow)

Not yours (Brokkr, 001-A): all Gradle files, `gradle/libs.versions.toml`, `core/**`, `api/**`, `mod/src/main/**`, `README.md`. If you need a dependency, plugin, run setting or README change, ask Heimdall; do not edit those files. 001-A already provides: JUnit 5 and ArchUnit (`archunit-junit5`) on `mod`'s test classpath — if missing, report it rather than adding it.

## What to build

1. **Architecture checks** in `mod/src/test/java/<base>/architecture/`:
   - One `ArchRule` per check in design §3, built from a root-package parameter so the same rule can run against production and against fixtures.
   - **Production test:** imports `<base>` classes from the classpath (core, api and mod outputs) **excluding test classes**, and asserts all four rules. With no `compat` packages yet, rules 3 and 4 must not fail for "no classes matched" (use `allowEmptyShould(true)` or equivalent, deliberately, and comment why).
   - **Rule self-tests (permanent):** for each rule, a fixture set under a separate test-only root (e.g. `<base>.architecture.fixtures.<rule>`) with sub-packages mimicking `core`/`api`/`mod`/`compat`, containing one deliberate violation; assert the rule **fails** on it with a message naming the offending class. Also one compliant fixture per rule that passes. Rule 3's fixture includes a `mod.CompatBootstrap` referencing `compat` (allowed) and another `mod` class referencing `compat` (violation). Rule 4's fixture includes a `compat.gtceu` class importing `api` (allowed) and one importing `core` (violation).
   - Fixtures must never reach the jar or the production check.
2. **First GameTest** in `mod/src/gametest/java/<base>/gametest/`: a `@GameTestHolder("pollutioncraft")` (or `RegisterGameTestsEvent`) test that places a block (e.g. stone) and asserts it is present, with its structure template in `mod/src/gametest/resources/` (an empty template, or the vanilla empty structure if NeoForge allows it — report which). Registration lives only in the `gametest` source set.
3. **CI** `.github/workflows/ci.yml`: on `push` and `pull_request`; `ubuntu-latest`; Temurin 21; `gradle/actions/setup-gradle` (caching on); runs `./gradlew build runGameTestServer`; `permissions: contents: read`; every action pinned to a full commit SHA with the version in a comment; on failure, upload test reports and the GameTest run's logs as artifacts. No secrets needed (GTM download must be anonymous; if it is not, report).

## Tests to write first (show each red, then green)

1. Each of the four rules: the violation fixture makes the rule fail (captured in the self-test), and once, temporarily, a real violation in production code (e.g. a scratch class in `core` importing `net.minecraft.world.level.Level`) makes `./gradlew build` fail. Remove the scratch class afterwards; it must not be in your hand-back. Paste the failing output lines for all four.
2. GameTest: first make it fail on purpose (assert the wrong block), run `./gradlew runGameTestServer`, show the non-zero exit code (`echo $?` or equivalent) and the failure line; then fix it and show exit 0 with "All 1 required tests passed" (or the equivalent line).
3. CI: show one red run (or `act` locally if you have no push access; say which) and one green run. Pushing is Heimdall's call, so if you cannot push, give Heimdall the workflow and the local evidence and he will run it.

## Commands to run before handing back

- `./gradlew build` — passes; report the number of architecture tests run
- `./gradlew test` — passes
- `./gradlew runGameTestServer` — exit 0, one required test passed, GTM not loaded (quote the mod list line)
- `./gradlew :mod:jar` then `unzip -l` — no `gametest` or `architecture` classes in the jar
- `git status` — only files under your owned paths changed/added

## Shared interfaces

Consumes 001-A's build and runs. Provides the architecture rules (task 012 and later compat inherit rule 4) and the GameTest harness pattern that every later GameTest follows. Defines no domain interfaces.

## Out of scope

Any Gradle or build change, README (Brokkr). `CompatBootstrap` itself and any real `compat` code (task 012). Pollution logic. Publishing, API binary-compat check, release workflows (task 026). GTM-present GameTest runs (task 012).

If a rule in design §3 is ambiguous when you encode it, ask Heimdall; do not pick an interpretation silently.
