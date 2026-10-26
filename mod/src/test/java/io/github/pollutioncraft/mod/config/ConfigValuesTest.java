package io.github.pollutioncraft.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigValuesTest {
    static Stream<Arguments> badValuePerKey() {
        List<Arguments> cases = new ArrayList<>();
        for (ConfigKey key : ConfigRegistry.KEYS) {
            List<Object> bad = new ArrayList<>(List.of(
                    Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, key.min() - 1, key.max() + 1, "oops"));
            if (key.type() == ConfigKey.Type.INTEGER) {
                bad.add(key.defaultValue() + 0.5);
            }
            bad.forEach(value -> cases.add(Arguments.of(key.name(), value)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("badValuePerKey")
    void invalidValueFallsBackToDefaultWithOneLogLine(String keyName, Object bad) {
        ConfigKey key = ConfigRegistry.find(keyName).orElseThrow();
        List<String> log = new ArrayList<>();

        ConfigValues values = ConfigValues.load(Map.of(keyName, bad), log::add);

        assertEquals(key.defaultValue(), values.get(keyName));
        assertEquals(1, log.size(), log::toString);
        assertTrue(log.get(0).contains(keyName), log.get(0));
    }

    @ParameterizedTest
    @MethodSource("validBoundaryValues")
    void boundsAreInclusiveAndAccepted(String keyName, double value) {
        List<String> log = new ArrayList<>();
        Map<String, Object> raw = new HashMap<>();
        raw.put(keyName, value);

        ConfigValues values = ConfigValues.load(raw, log::add);

        assertEquals(value, values.get(keyName));
        assertTrue(log.isEmpty(), log::toString);
    }

    static Stream<Arguments> validBoundaryValues() {
        // spreadFraction's upper bound (1.0) is legal for the key but refused by the cross-field rule, tested separately.
        return ConfigRegistry.KEYS.stream().flatMap(key -> {
            boolean skipMax = key == ConfigRegistry.SPREAD_FRACTION;
            return skipMax
                    ? Stream.of(Arguments.of(key.name(), key.min()))
                    : Stream.of(Arguments.of(key.name(), key.min()), Arguments.of(key.name(), key.max()));
        });
    }

    @org.junit.jupiter.api.Test
    void missingKeysUseDefaultsSilently() {
        List<String> log = new ArrayList<>();

        ConfigValues values = ConfigValues.load(Map.of(), log::add);

        for (ConfigKey key : ConfigRegistry.KEYS) {
            assertEquals(key.defaultValue(), values.get(key.name()));
        }
        assertTrue(log.isEmpty());
    }

    @org.junit.jupiter.api.Test
    void integerKeyAcceptsWholeNumbersGivenAsAnyNumberType() {
        ConfigValues values = ConfigValues.load(Map.of("spreadIntervalTicks", 40L), message -> {});

        assertEquals(40.0, values.get("spreadIntervalTicks"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = {0.25, 0.3, 0.99})
    void spreadFractionAtOrAboveQuarterIsRejectedFromFile(double fraction) {
        List<String> log = new ArrayList<>();

        ConfigValues values = ConfigValues.load(Map.of("spreadFraction", fraction), log::add);

        assertEquals(ConfigRegistry.SPREAD_FRACTION.defaultValue(), values.get("spreadFraction"));
        assertEquals(1, log.size(), log::toString);
        assertTrue(log.get(0).contains("spreadFraction"), log.get(0));
    }

    @org.junit.jupiter.api.Test
    void spreadFractionJustBelowQuarterIsAccepted() {
        ConfigValues values = ConfigValues.load(Map.of("spreadFraction", 0.2499), message -> {});

        assertEquals(0.2499, values.get("spreadFraction"));
    }

    @org.junit.jupiter.api.Test
    void oneBadKeyDoesNotAffectTheOthers() {
        ConfigValues values = ConfigValues.load(
                Map.of("spreadFraction", Double.NaN, "epsilon", 0.5, "spreadIntervalTicks", 100), message -> {});

        assertEquals(0.5, values.get("epsilon"));
        assertEquals(100.0, values.get("spreadIntervalTicks"));
    }

    @org.junit.jupiter.api.Test
    void defaultsSatisfyEveryCrossFieldRule() {
        ConfigValues defaults = ConfigValues.defaults();

        assertTrue(ConfigRegistry.violation(defaults::get).isEmpty());
    }
}
