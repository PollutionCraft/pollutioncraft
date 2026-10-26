package io.github.pollutioncraft.mod.config;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Server-wide, in-memory overrides of config values. Never written to disk; gone on restart.
 * An override passes the same checks as a file value, with cross-field rules judged against the values
 * that would be in effect afterwards.
 */
public final class RuntimeOverrides {
    private final Map<String, Double> overrides = new ConcurrentHashMap<>();
    private final Supplier<ConfigValues> fileValues;

    RuntimeOverrides(Supplier<ConfigValues> fileValues) {
        this.fileValues = fileValues;
    }

    /** Sets an override; returns the reason it was refused (nothing changed), or empty on success. */
    public synchronized Optional<String> set(String keyName, double value) {
        Optional<ConfigKey> key = ConfigRegistry.find(keyName);
        if (key.isEmpty()) {
            return Optional.of("Unknown config key '" + keyName + "'");
        }
        Optional<String> problem = key.get().problemWith(value);
        if (problem.isPresent()) {
            return Optional.of("Config key '" + keyName + "' " + problem.get());
        }
        ConfigValues file = fileValues.get();
        var broken = ConfigRegistry.violation(name -> name.equals(keyName) ? value : effective(name, file));
        if (broken.isPresent()) {
            return Optional.of("Refused: " + broken.get().description() + " (setting '" + keyName + "' to " + value + ")");
        }
        overrides.put(keyName, value);
        return Optional.empty();
    }

    /** True when an override existed. */
    public synchronized boolean clear(String keyName) {
        return overrides.remove(keyName) != null;
    }

    public synchronized void clearAll() {
        overrides.clear();
    }

    public OptionalDouble get(String keyName) {
        Double value = overrides.get(keyName);
        return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    /** Active overrides, sorted by key name. */
    public Map<String, Double> list() {
        return new TreeMap<>(overrides);
    }

    int size() {
        return overrides.size();
    }

    double effective(String keyName, ConfigValues file) {
        Double override = overrides.get(keyName);
        return override != null ? override : file.get(keyName);
    }
}
