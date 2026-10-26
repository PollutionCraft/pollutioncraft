package io.github.pollutioncraft.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeOverridesTest {
    private final List<String> log = new ArrayList<>();
    private ServerConfig config;

    @BeforeEach
    void freshConfig() {
        config = new ServerConfig(log::add);
    }

    @Test
    void accessorsReturnConfigValuesWithoutOverrides() {
        config.reload(Map.of("spreadFraction", 0.1));

        assertEquals(0.1, config.spreadFraction());
        assertEquals(20, config.spreadIntervalTicks());
    }

    @Test
    void overrideWinsOverFileValue() {
        config.reload(Map.of("spreadFraction", 0.1));

        assertTrue(config.overrides().set("spreadFraction", 0.2).isEmpty());

        assertEquals(0.2, config.spreadFraction());
    }

    @Test
    void clearOneRestoresFileValueAndLeavesOtherOverrides() {
        config.reload(Map.of("spreadFraction", 0.1));
        config.overrides().set("spreadFraction", 0.2);
        config.overrides().set("epsilon", 5);

        assertTrue(config.overrides().clear("spreadFraction"));

        assertEquals(0.1, config.spreadFraction());
        assertEquals(5.0, config.epsilon());
        assertFalse(config.overrides().clear("spreadFraction"), "clearing an absent override reports false");
    }

    @Test
    void clearAllRestoresEverything() {
        config.overrides().set("spreadFraction", 0.2);
        config.overrides().set("epsilon", 5);

        config.overrides().clearAll();

        assertEquals(ConfigRegistry.SPREAD_FRACTION.defaultValue(), config.spreadFraction());
        assertTrue(config.overrides().list().isEmpty());
    }

    @Test
    void listIsSortedByKeyName() {
        config.overrides().set("spreadFraction", 0.2);
        config.overrides().set("epsilon", 5);

        assertEquals(List.of("epsilon", "spreadFraction"), List.copyOf(config.overrides().list().keySet()));
    }

    @Test
    void reloadKeepsOverridesAndSaysSo() {
        config.overrides().set("spreadFraction", 0.2);

        config.reload(Map.of("spreadFraction", 0.05, "epsilon", 2.0));

        assertEquals(0.2, config.spreadFraction(), "override survives");
        assertEquals(2.0, config.epsilon(), "file change is applied");
        assertTrue(log.stream().anyMatch(line -> line.contains("1 runtime override")), log::toString);
    }

    @Test
    void reloadWithoutOverridesDoesNotMentionThem() {
        config.reload(Map.of());

        assertTrue(log.isEmpty(), log::toString);
    }

    @Test
    void overrideRunsTheSameBoundsChecksAndChangesNothingOnFailure() {
        config.overrides().set("epsilon", 5);

        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 1e30}) {
            assertTrue(config.overrides().set("epsilon", bad).isPresent(), "accepted " + bad);
        }
        assertTrue(config.overrides().set("spreadIntervalTicks", 2.5).isPresent());

        assertEquals(5.0, config.epsilon());
        assertEquals(1, config.overrides().list().size());
    }

    @Test
    void overrideOfUnknownKeyIsRejectedNamingTheKey() {
        String problem = config.overrides().set("nope", 1).orElseThrow();

        assertTrue(problem.contains("nope"), problem);
    }

    @Test
    void spreadFractionOverrideViolatingCrossFieldRuleIsRejected() {
        config.overrides().set("spreadFraction", 0.1);

        String problem = config.overrides().set("spreadFraction", 0.25).orElseThrow();

        assertTrue(problem.contains("spreadFraction"), problem);
        assertEquals(0.1, config.spreadFraction());
    }

    @Test
    void crossFieldRuleIsCheckedAgainstEffectiveValuesNotTheFile() {
        // File holds a legal 0.1; the override is what the rule must see.
        config.reload(Map.of("spreadFraction", 0.1));

        assertTrue(config.overrides().set("spreadFraction", 0.3).isPresent());
        assertEquals(0.1, config.spreadFraction());
    }

    @Test
    void diffusionConfigIsBuiltFromEffectiveValues() {
        config.overrides().set("spreadFraction", 0.1);
        config.overrides().set("epsilon", 3);

        var diffusion = config.diffusionConfig();

        assertEquals(0.1, diffusion.spreadFraction());
        assertEquals(3.0, diffusion.epsilon());
        assertEquals(config.spreadThreshold(), diffusion.spreadThreshold());
        assertEquals(config.persistentDecay(), diffusion.persistentDecay());
    }
}
