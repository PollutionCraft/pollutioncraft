package io.github.pollutioncraft.core;

/**
 * Parameters of one diffusion step, already parsed from config (validation of user input lives in task 008;
 * this record still rejects values that would break the maths).
 *
 * @param spreadFraction fraction of a chunk's pollution sent to each of its 4 neighbours; {@code 4 × it < 1}
 * @param spreadThreshold a chunk spreads only when its local pollution is at least this much
 * @param persistentDecay fraction of {@code persistent} removed per interval, in [0, 1]
 * @param epsilon chunks whose local pollution ends below this are pruned
 */
public record DiffusionConfig(double spreadFraction, double spreadThreshold, double persistentDecay, double epsilon) {
    public DiffusionConfig {
        if (!(spreadFraction >= 0) || !(4 * spreadFraction < 1)) {
            throw new IllegalArgumentException("spreadFraction must satisfy 0 <= f and 4*f < 1, was " + spreadFraction);
        }
        if (!(spreadThreshold >= 0) || Double.isInfinite(spreadThreshold)) {
            throw new IllegalArgumentException("spreadThreshold must be finite and >= 0, was " + spreadThreshold);
        }
        if (!(persistentDecay >= 0 && persistentDecay <= 1)) {
            throw new IllegalArgumentException("persistentDecay must be in [0, 1], was " + persistentDecay);
        }
        if (!(epsilon >= 0) || Double.isInfinite(epsilon)) {
            throw new IllegalArgumentException("epsilon must be finite and >= 0, was " + epsilon);
        }
    }
}
