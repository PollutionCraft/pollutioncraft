package io.github.pollutioncraft.mod.command;

import java.util.Optional;

/** Bounds of the debug command arguments (architecture 4: mutating commands are bounded). */
public final class CommandLimits {
    public static final double MAX_AMOUNT = 1e9;
    public static final int MIN_RADIUS = 0;
    public static final int MAX_RADIUS = 8;
    public static final int MIN_STEPS = 1;
    public static final int MAX_STEPS = 1000;

    private CommandLimits() {}

    /** Why {@code amount} (PU) is refused, or empty when acceptable. */
    public static Optional<String> problemWithAmount(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return Optional.of("amount must be a finite number but was " + amount);
        }
        if (amount < 0 || amount > MAX_AMOUNT) {
            return Optional.of("amount must be between 0 and " + PollutionText.number(MAX_AMOUNT) + " PU but was " + amount);
        }
        return Optional.empty();
    }

    public static Optional<String> problemWithRadius(int radius) {
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
            return Optional.of("radius must be between " + MIN_RADIUS + " and " + MAX_RADIUS + " but was " + radius);
        }
        return Optional.empty();
    }

    public static Optional<String> problemWithSteps(int steps) {
        if (steps < MIN_STEPS || steps > MAX_STEPS) {
            return Optional.of("n must be between " + MIN_STEPS + " and " + MAX_STEPS + " but was " + steps);
        }
        return Optional.empty();
    }
}
