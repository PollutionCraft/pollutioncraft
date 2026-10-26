package io.github.pollutioncraft.mod.config;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * The single list of config keys. The config file spec, load validation, runtime overrides and (task 009)
 * {@code /pollution tune} with its completion all read {@link #KEYS}; a new key is one declaration here
 * plus its accessor on {@link ServerConfig}.
 */
public final class ConfigRegistry {
    public static final ConfigKey SPREAD_INTERVAL_TICKS = new ConfigKey(
            "spreadIntervalTicks", ConfigKey.Type.INTEGER, 20, 1, 72_000,
            "Ticks between pollution simulation steps. 20 ticks = 1 second (default 20 = 1 second; 1200 = 60 seconds).");
    public static final ConfigKey SPREAD_FRACTION = new ConfigKey(
            "spreadFraction", ConfigKey.Type.DECIMAL, 0.02, 0, 1,
            "Fraction of a chunk's pollution sent to each of its 4 neighbours per interval. 4 x this must stay below 1.");
    public static final ConfigKey SPREAD_THRESHOLD = new ConfigKey(
            "spreadThreshold", ConfigKey.Type.DECIMAL, 15, 0, 1e9,
            "A chunk spreads only when its pollution (PU) is at least this much. Placeholder, tuned in playtests.");
    public static final ConfigKey PERSISTENT_DECAY = new ConfigKey(
            "persistentDecay", ConfigKey.Type.DECIMAL, 0, 0, 1,
            "Fraction of persistent pollution removed per interval. Persistent pollution is always 0 in the alpha.");
    public static final ConfigKey EPSILON = new ConfigKey(
            "epsilon", ConfigKey.Type.DECIMAL, 0.001, 0, 1e6,
            "Chunks whose pollution (PU) ends an interval below this are dropped.");
    public static final ConfigKey EMISSION_MULTIPLIER = new ConfigKey(
            "emissionMultiplier", ConfigKey.Type.DECIMAL, 1.0, 0, 1000,
            "Scales every emitter's rate. 1.0 = as defined, 0 = no emission.");
    public static final ConfigKey DEFAULT_CAPACITY = new ConfigKey(
            "defaultCapacity", ConfigKey.Type.DECIMAL, 100, 0, 1e9,
            "Absorption capacity (PU per interval) of every chunk until the absorption model arrives. Placeholder.");

    public static final List<ConfigKey> KEYS = List.of(
            SPREAD_INTERVAL_TICKS, SPREAD_FRACTION, SPREAD_THRESHOLD, PERSISTENT_DECAY, EPSILON,
            EMISSION_MULTIPLIER, DEFAULT_CAPACITY);

    /** Rules that span keys; checked against effective values by load and by overrides. */
    public static final List<CrossFieldRule> CROSS_FIELD_RULES = List.of(new CrossFieldRule(
            List.of(SPREAD_FRACTION),
            "4 x spreadFraction must be below 1",
            value -> 4 * value.applyAsDouble("spreadFraction") < 1));

    private ConfigRegistry() {}

    public static Optional<ConfigKey> find(String name) {
        return KEYS.stream().filter(key -> key.name().equals(name)).findFirst();
    }

    /** First broken cross-field rule for these values, if any. */
    public static Optional<CrossFieldRule> violation(ToDoubleFunction<String> values) {
        return CROSS_FIELD_RULES.stream().filter(rule -> !rule.holds().test(values)).findFirst();
    }

    /**
     * @param keys the keys involved; on a violation at load they all fall back to their defaults
     * @param holds true when the rule is satisfied by the given values (looked up by key name)
     */
    public record CrossFieldRule(
            List<ConfigKey> keys, String description, Predicate<ToDoubleFunction<String>> holds) {}
}
