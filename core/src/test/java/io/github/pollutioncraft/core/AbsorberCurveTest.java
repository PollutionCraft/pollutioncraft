package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AbsorberCurveTest {
    private static final AbsorberCurve LEAVES = new AbsorberCurve(2.0, 64);

    @Test
    void noAbsorbersGiveNothing() {
        assertEquals(0.0, LEAVES.absorb(0), 0.0);
    }

    @Test
    void atNEqualsKItGivesAboutSixtyThreePercentOfTheCap() {
        assertEquals(2.0 * (1 - 1 / Math.E), LEAVES.absorb(64), 1e-12);
    }

    @Test
    void manyAbsorbersApproachTheCapAndNeverExceedIt() {
        assertEquals(2.0, LEAVES.absorb(10_000), 1e-12);
        for (int n : new int[] {0, 1, 63, 64, 500, 5_000, 1_000_000, Integer.MAX_VALUE}) {
            assertTrue(LEAVES.absorb(n) <= 2.0, "n=" + n + " gave " + LEAVES.absorb(n));
        }
    }

    @Test
    void everyExtraAbsorberAddsAlittleWhileBelowTheCap() {
        double previous = LEAVES.absorb(0);
        for (int n = 1; n <= 1_000; n++) {
            double current = LEAVES.absorb(n);
            assertTrue(current > previous, "n=" + n + ": " + current + " is not above " + previous);
            previous = current;
        }
    }

    @Test
    void aCapOfZeroAlwaysGivesZero() {
        AbsorberCurve none = new AbsorberCurve(0.0, 10);
        for (int n : new int[] {0, 1, 10, 1_000_000}) {
            assertEquals(0.0, none.absorb(n), 0.0);
        }
    }

    @Test
    void invalidCapOrKThrowsAndNamesTheValue() {
        for (double bad : new double[] {-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new AbsorberCurve(bad, 64), "cap " + bad);
            assertTrue(e.getMessage().contains(String.valueOf(bad)), e.getMessage());
        }
        for (double bad : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new AbsorberCurve(2.0, bad), "k " + bad);
            assertTrue(e.getMessage().contains(String.valueOf(bad)), e.getMessage());
        }
    }

    @Test
    void aNegativeCountThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LEAVES.absorb(-1));
        assertTrue(e.getMessage().contains("-1"), e.getMessage());
    }

    @Test
    void theAccessorsReturnWhatWasGiven() {
        assertEquals(2.0, LEAVES.cap());
        assertEquals(64.0, LEAVES.k());
    }
}
