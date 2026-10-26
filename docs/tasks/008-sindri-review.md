# 008 review (Heimdall) — ACCEPTED (not committed yet)

What I verified myself: `clean build runGameTestServer --rerun-tasks --no-build-cache` exits 0 with 108 PASSED lines and "All 2 required tests passed".

Read: `ConfigKey`, `ConfigRegistry`, `ConfigValues`, `RuntimeOverrides`, `ServerConfig`, `ServerConfigSpec` and the `PollutionCraftMod` wiring.
- There is one registry, and the spec, validation and overrides all read it.
- The reflection test ties accessors to keys.
- A bad value costs exactly one log line and keeps the default.
- Cross-field checks use effective values.
- Overrides stay in memory and survive a reload.
- `core` stays config-free.

## User decisions applied
- Defaults are the user's placeholders: spreadThreshold 15, epsilon 0.001, persistentDecay 0, emissionMultiplier 1.0, defaultCapacity 100, plus ADR 0003's 20 ticks and 0.02. Your bounds are accepted as placeholders too.
- Concern 2: the static `PollutionCraftMod.config()` stays (it's read once at the scheduler boundary).
- Concern 7: your GameTest drives `DiffusionStep` directly. The test through the scheduler comes with 007's adoption of these accessors.

## Notes (no change now)
- `ServerConfig.reload` re-checks cross-field rules on the new file values only, not on file plus overrides. With today's single-key rule, the effective value is the override itself, so nothing breaks. Whoever adds the first multi-key rule must also re-check effective values on reload, and say what happens to an override that has become invalid.
