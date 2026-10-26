package io.github.pollutioncraft.mod.config;

import java.util.Optional;

/**
 * One config key, declared once in {@link ConfigRegistry}: its type, default, inclusive bounds and comment.
 * Values are carried as {@code double} everywhere; an {@link Type#INTEGER} key additionally has to be whole.
 */
public record ConfigKey(String name, Type type, double defaultValue, double min, double max, String comment) {
    public enum Type { INTEGER, DECIMAL }

    public ConfigKey {
        if (!(min <= defaultValue && defaultValue <= max) || !Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("key " + name + ": default " + defaultValue + " must lie in finite bounds [" + min + ", " + max + "]");
        }
    }

    /** Why {@code raw} is not an acceptable value for this key, or empty when it is. */
    public Optional<String> problemWith(Object raw) {
        if (!(raw instanceof Number number)) {
            return Optional.of("expected a number but got " + raw);
        }
        double value = number.doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Optional.of("must be finite but was " + value);
        }
        if (value < min || value > max) {
            return Optional.of("must be between " + min + " and " + max + " but was " + value);
        }
        if (type == Type.INTEGER && value != Math.rint(value)) {
            return Optional.of("must be a whole number but was " + value);
        }
        return Optional.empty();
    }
}
