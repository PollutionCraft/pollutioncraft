package io.github.pollutioncraft.mod.config;

import io.github.pollutioncraft.core.DiffusionConfig;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Effective server config: the validated file values, with {@link RuntimeOverrides} on top.
 * Accessors are read on the server thread, once per interval. Every public no-argument {@code int}/{@code double}
 * method is the accessor of the registry key of the same name (checked by a test).
 */
public final class ServerConfig {
    private final Consumer<String> log;
    private final RuntimeOverrides overrides = new RuntimeOverrides(this::fileValues);
    private volatile ConfigValues fileValues = ConfigValues.defaults();

    public ServerConfig(Consumer<String> log) {
        this.log = log;
    }

    /** Applies freshly read file contents. Active overrides are kept, and that is logged. */
    public void reload(Map<String, ?> raw) {
        fileValues = ConfigValues.load(raw, log);
        int kept = overrides.size();
        if (kept > 0) {
            log.accept("Config reloaded; keeping " + kept + " runtime override(s)");
        }
    }

    public RuntimeOverrides overrides() {
        return overrides;
    }

    private ConfigValues fileValues() {
        return fileValues;
    }

    private double effective(ConfigKey key) {
        return overrides.effective(key.name(), fileValues);
    }

    /** The effective value (override over file value) of a registry key, whichever key it is. */
    public double value(ConfigKey key) {
        if (!ConfigRegistry.KEYS.contains(key)) {
            throw new IllegalArgumentException("not a registry config key: " + key.name());
        }
        return effective(key);
    }

    public int spreadIntervalTicks() {
        return (int) effective(ConfigRegistry.SPREAD_INTERVAL_TICKS);
    }

    public double spreadFraction() {
        return effective(ConfigRegistry.SPREAD_FRACTION);
    }

    public double spreadThreshold() {
        return effective(ConfigRegistry.SPREAD_THRESHOLD);
    }

    public double persistentDecay() {
        return effective(ConfigRegistry.PERSISTENT_DECAY);
    }

    public double epsilon() {
        return effective(ConfigRegistry.EPSILON);
    }

    public double emissionMultiplier() {
        return effective(ConfigRegistry.EMISSION_MULTIPLIER);
    }

    public double defaultCapacity() {
        return effective(ConfigRegistry.DEFAULT_CAPACITY);
    }

    public int maxEmittersPerChunk() {
        return (int) effective(ConfigRegistry.MAX_EMITTERS_PER_CHUNK);
    }

    /** The diffusion parameters for the next interval, from the effective values. */
    public DiffusionConfig diffusionConfig() {
        return new DiffusionConfig(spreadFraction(), spreadThreshold(), persistentDecay(), epsilon());
    }
}
