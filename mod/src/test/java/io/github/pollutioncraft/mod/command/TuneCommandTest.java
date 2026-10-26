package io.github.pollutioncraft.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pollutioncraft.mod.config.ConfigRegistry;
import io.github.pollutioncraft.mod.config.ServerConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TuneCommandTest {
    private ServerConfig config;
    private TuneCommand tune;

    @BeforeEach
    void setUp() {
        config = new ServerConfig(message -> {});
        tune = new TuneCommand(config);
    }

    @Test
    void refusesOutOfBoundsWholeNumberUnknownKeyAndCrossFieldViolation() {
        assertFalse(tune.set("spreadFraction", 2.0).ok());
        assertFalse(tune.set("spreadIntervalTicks", 20.5).ok());
        assertFalse(tune.set("noSuchKey", 1).ok());
        assertFalse(tune.set("spreadFraction", 0.25).ok());
        assertTrue(tune.set("spreadFraction", 0.25).message().contains("4 x spreadFraction"));
    }

    @Test
    void refusedTuneLeavesOverridesUnchanged() {
        assertTrue(tune.set("epsilon", 0.5).ok());
        Map<String, Double> before = config.overrides().list();

        tune.set("spreadFraction", 0.25);
        tune.set("noSuchKey", 1);
        tune.set("spreadIntervalTicks", 2.5);

        assertEquals(before, config.overrides().list());
        assertEquals(0.02, config.spreadFraction());
    }

    @Test
    void acceptedTuneSaysItAppliesFromTheNextIntervalAndChangesTheEffectiveValue() {
        TuneCommand.Reply reply = tune.set("spreadFraction", 0.1);

        assertTrue(reply.ok());
        assertTrue(reply.message().contains("next interval"));
        assertEquals(0.1, config.spreadFraction());
    }

    @Test
    void overrideBeatsFileValueAndResetRestoresIt() {
        config.reload(Map.of("spreadFraction", 0.05));
        tune.set("spreadFraction", 0.1);
        assertEquals(0.1, config.spreadFraction());

        TuneCommand.Reply reply = tune.reset("spreadFraction");

        assertTrue(reply.ok());
        assertEquals(0.05, config.spreadFraction());
    }

    @Test
    void resetOfAKeyWithoutOverrideSaysSoAndOfAnUnknownKeyFails() {
        assertTrue(tune.reset("epsilon").ok());
        assertTrue(tune.reset("epsilon").message().contains("no override"));
        assertFalse(tune.reset("noSuchKey").ok());
    }

    @Test
    void resetAllClearsEverything() {
        tune.set("epsilon", 0.5);
        tune.set("spreadThreshold", 3);

        TuneCommand.Reply reply = tune.resetAll();

        assertTrue(reply.ok());
        assertTrue(config.overrides().list().isEmpty());
        assertTrue(reply.message().contains("2"));
    }

    @Test
    void listIsSortedByKeyAndEmptyListSaysNone() {
        assertEquals(List.of("No runtime overrides active"), tune.list());
        tune.set("spreadThreshold", 3);
        tune.set("epsilon", 0.5);

        List<String> lines = tune.list();

        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("epsilon"));
        assertTrue(lines.get(1).contains("spreadThreshold"));
    }

    @Test
    void overriddenMarkerAppearsExactlyOnOverriddenValues() {
        tune.set("spreadFraction", 0.1);

        List<String> lines = RatesText.effectiveRates(config);

        assertEquals(ConfigRegistry.KEYS.size(), lines.size());
        assertTrue(lines.contains("spreadFraction: 0.1 (overridden)"), lines.toString());
        for (String line : lines) {
            assertEquals(line.contains("spreadFraction"), line.contains("(overridden)"), line);
        }
        assertFalse(RatesText.effectiveRates(new ServerConfig(m -> {})).stream().anyMatch(l -> l.contains("(overridden)")));
    }

    @Test
    void ratesLinesShowTheEffectiveValueOfEveryKeyAndTheFileValueAfterReset() {
        config.reload(Map.of("epsilon", 0.5));
        tune.set("spreadFraction", 0.1);

        List<String> lines = RatesText.effectiveRates(config);
        for (var key : ConfigRegistry.KEYS) {
            String shown = PollutionText.number(config.value(key));
            assertTrue(lines.stream().anyMatch(l -> l.startsWith(key.name() + ": " + shown)), key.name() + " in " + lines);
        }
        assertTrue(lines.contains("epsilon: 0.5"), lines.toString());
        assertTrue(lines.contains("spreadFraction: 0.1 (overridden)"), lines.toString());

        tune.reset("spreadFraction");
        assertTrue(RatesText.effectiveRates(config).contains("spreadFraction: 0.02"));
    }
}
