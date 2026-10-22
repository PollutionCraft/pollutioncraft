# 001-F review (Heimdall) — APPROVED

What I verified myself:
- `clean build runGameTestServer --configuration-cache --rerun-tasks --no-build-cache` exits 0. All 15 tests pass, the check runs for core, api and mod, and the GameTest run reports "All 1 required tests passed" with no gtceu in the mod list.
- The jar is unchanged: 3 classes, mods.toml, LICENSE and COPYING.
- `:mod:dependencies` has no `com.gregtechceu` entry.
- I reproduced all three red cases in a scratch copy:
  - GTM module on `compileOnly`: rejected in `:mod compileClasspath`.
  - assertj-core in core's tests: rejected, along with its byte-buddy transitive, in `:core` test compile and runtime classpaths.
  - GTM jar as a `files()` dependency on gametest: rejected as a non-module entry.
- The allowlist matches exactly the groups that resolve today: no unused entries and nothing missing.

Allowlist: accepted as is. Matching exact groups shared across subprojects is fine. ADR 0015 asks for one allowlist of groups, and core and api can only ever resolve the generic entries plus what their own build declares.

Known gap, recorded for task 012 rather than fixed now. The check covers compile and test classpaths only, as the ADR's enforcement clause and the brief specify. A custom resolvable configuration added to `sourceSets.main.runtimeClasspath` (the old `gtmRuntime` pattern) is not caught; I probed it and the build passed. Code review catches it for now. Task 012 should decide whether to extend the check to every resolvable configuration we declare.
