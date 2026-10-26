package io.github.pollutioncraft.mod.emitter;

import java.util.function.DoubleSupplier;

/**
 * Turns a mapping's rate into the PU the engine emits. The {@code emissionMultiplier} is read once per
 * interval, by {@link #startInterval}, so a change (for example {@code /pollution tune}) applies from the
 * next interval and never mid-interval. The multiplier is applied here, not in the API, so API callers
 * see exact amounts.
 */
final class IntervalAmounts {
    private final DoubleSupplier multiplierSource;
    private double multiplier = 1.0;

    IntervalAmounts(DoubleSupplier multiplierSource) {
        this.multiplierSource = multiplierSource;
    }

    /** Reads the multiplier for the interval that is starting. A non-finite or negative one counts as 0. */
    void startInterval() {
        double value = multiplierSource.getAsDouble();
        multiplier = Double.isFinite(value) && value > 0 ? value : 0.0;
    }

    /** PU to emit for an active emitter of this rate in the current interval. */
    double amountFor(double rate) {
        return rate * multiplier;
    }
}
