package io.github.pollutioncraft.mod.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommandLimitsTest {
    @Test
    void amountRefusesNaNNegativeInfiniteAndAboveMax() {
        assertTrue(CommandLimits.problemWithAmount(Double.NaN).isPresent());
        assertTrue(CommandLimits.problemWithAmount(-0.001).isPresent());
        assertTrue(CommandLimits.problemWithAmount(Double.POSITIVE_INFINITY).isPresent());
        assertTrue(CommandLimits.problemWithAmount(Double.NEGATIVE_INFINITY).isPresent());
        assertTrue(CommandLimits.problemWithAmount(Math.nextUp(1e9)).isPresent());
    }

    @Test
    void amountAcceptsZeroAndMax() {
        assertTrue(CommandLimits.problemWithAmount(0).isEmpty());
        assertTrue(CommandLimits.problemWithAmount(1e9).isEmpty());
        assertTrue(CommandLimits.problemWithAmount(15.5).isEmpty());
    }

    @Test
    void radiusIsZeroToEight() {
        assertTrue(CommandLimits.problemWithRadius(-1).isPresent());
        assertTrue(CommandLimits.problemWithRadius(9).isPresent());
        assertTrue(CommandLimits.problemWithRadius(0).isEmpty());
        assertTrue(CommandLimits.problemWithRadius(8).isEmpty());
    }

    @Test
    void stepCountIsOneToThousand() {
        assertTrue(CommandLimits.problemWithSteps(0).isPresent());
        assertTrue(CommandLimits.problemWithSteps(1001).isPresent());
        assertTrue(CommandLimits.problemWithSteps(1).isEmpty());
        assertTrue(CommandLimits.problemWithSteps(1000).isEmpty());
    }

    @Test
    void refusalNamesTheLimit() {
        assertTrue(CommandLimits.problemWithRadius(9).orElseThrow().contains("8"));
        assertTrue(CommandLimits.problemWithSteps(0).orElseThrow().contains("1000"));
    }
}
