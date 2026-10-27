package io.github.pollutioncraft.mod.command;

import io.github.pollutioncraft.mod.config.ConfigKey;
import io.github.pollutioncraft.mod.config.ConfigRegistry;
import io.github.pollutioncraft.mod.config.ServerConfig;
import java.util.List;

/** The effective config values in force, one line per registry key, overridden ones marked. */
public final class RatesText {
    static final String OVERRIDDEN = "(overridden)";

    private RatesText() {}

    public static List<String> effectiveRates(ServerConfig config) {
        return ConfigRegistry.KEYS.stream().map(key -> line(config, key)).toList();
    }

    /** One value with its marker, shared with {@code tune} listings. */
    public static String line(ServerConfig config, ConfigKey key) {
        String line = key.name() + ": " + PollutionText.number(config.value(key));
        return config.overrides().get(key.name()).isPresent() ? line + " " + OVERRIDDEN : line;
    }
}
