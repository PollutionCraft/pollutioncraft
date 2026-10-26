package io.github.pollutioncraft.mod.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IntervalAmountsTest {
    private final AtomicReference<Double> multiplier = new AtomicReference<>(1.0);
    private final IntervalAmounts amounts = new IntervalAmounts(multiplier::get);

    @Test
    void theAmountIsRateTimesMultiplier() {
        multiplier.set(2.5);
        amounts.startInterval();

        assertEquals(2.0 * 2.5, amounts.amountFor(2.0), 0.0);
    }

    @Test
    void aMultiplierOfZeroEmitsNothing() {
        multiplier.set(0.0);
        amounts.startInterval();

        assertEquals(0.0, amounts.amountFor(7.0), 0.0);
    }

    @Test
    void aChangedMultiplierAppliesFromTheNextIntervalNotMidInterval() {
        multiplier.set(1.0);
        amounts.startInterval();

        multiplier.set(3.0);
        assertEquals(1.0, amounts.amountFor(1.0), 0.0, "mid-interval: still the old multiplier");

        amounts.startInterval();
        assertEquals(3.0, amounts.amountFor(1.0), 0.0, "next interval: the new one");
    }

    @Test
    void anInvalidMultiplierEmitsNothingInsteadOfPoisoningTheBuffer() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1.0}) {
            multiplier.set(bad);
            amounts.startInterval();
            assertEquals(0.0, amounts.amountFor(1.0), 0.0, "multiplier " + bad);
        }
    }
}
