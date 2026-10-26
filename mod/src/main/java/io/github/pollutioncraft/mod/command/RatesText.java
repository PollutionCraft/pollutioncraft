package io.github.pollutioncraft.mod.command;

import io.github.pollutioncraft.mod.config.ConfigKey;
import io.github.pollutioncraft.mod.config.ConfigRegistry;
import io.github.pollutioncraft.mod.config.ServerConfig;
import java.lang.reflect.Method;
import java.util.List;

/** The effective config values in force, one line per registry key, overridden ones marked. */
public final class RatesText {
    static final String OVERRIDDEN = "(overridden)";

    private RatesText() {}

    public static List<String> effectiveRates(ServerConfig config) {
        return ConfigRegistry.KEYS.stream().map(key -> line(config, key)).toList();
    }

    /** One value with its marker, shared with {@code tune} listings. */
    static String line(ServerConfig config, ConfigKey key) {
        String line = key.name() + ": " + PollutionText.number(effective(config, key));
        return config.overrides().get(key.name()).isPresent() ? line + " " + OVERRIDDEN : line;
    }

    /**
     * {@link ServerConfig} documents (and a test checks) that each registry key has a public no-argument
     * accessor of the same name; this reads it so the key list stays in {@link ConfigRegistry} only.
     */
    private static double effective(ServerConfig config, ConfigKey key) {
        try {
            Method accessor = ServerConfig.class.getMethod(key.name());
            return ((Number) accessor.invoke(config)).doubleValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ServerConfig has no accessor for config key " + key.name(), e);
        }
    }
}
