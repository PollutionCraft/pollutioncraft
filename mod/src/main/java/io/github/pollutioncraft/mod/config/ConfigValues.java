package io.github.pollutioncraft.mod.config;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** An immutable, fully validated value for every registered key. */
public final class ConfigValues {
    private final Map<String, Double> values;

    private ConfigValues(Map<String, Double> values) {
        this.values = Map.copyOf(values);
    }

    public static ConfigValues defaults() {
        Map<String, Double> values = new HashMap<>();
        ConfigRegistry.KEYS.forEach(key -> values.put(key.name(), key.defaultValue()));
        return new ConfigValues(values);
    }

    /**
     * Validates raw file contents. Absent keys take their default silently; an invalid key keeps its default
     * and costs exactly one log line naming it, as does a broken cross-field rule.
     */
    public static ConfigValues load(Map<String, ?> raw, Consumer<String> log) {
        Map<String, Double> values = new HashMap<>();
        for (ConfigKey key : ConfigRegistry.KEYS) {
            Object candidate = raw.get(key.name());
            if (candidate == null) {
                values.put(key.name(), key.defaultValue());
                continue;
            }
            var problem = key.problemWith(candidate);
            if (problem.isPresent()) {
                log.accept("Config key '" + key.name() + "' " + problem.get() + "; keeping default " + key.defaultValue());
                values.put(key.name(), key.defaultValue());
            } else {
                values.put(key.name(), ((Number) candidate).doubleValue());
            }
        }
        var broken = ConfigRegistry.violation(values::get);
        while (broken.isPresent()) {
            var rule = broken.get();
            rule.keys().forEach(key -> values.put(key.name(), key.defaultValue()));
            log.accept("Config rule violated (" + rule.description() + "); keeping defaults for "
                    + rule.keys().stream().map(ConfigKey::name).toList());
            broken = ConfigRegistry.violation(values::get);
        }
        return new ConfigValues(values);
    }

    public double get(String keyName) {
        return values.get(keyName);
    }
}
