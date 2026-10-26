package io.github.pollutioncraft.mod;

import io.github.pollutioncraft.core.DiffusionConfig;

/**
 * Plain stand-in for the server config until task 008 lands (it then replaces this with config accessors).
 * Values follow the starting defaults of task 008 (1 PU = one lit furnace's output for one interval):
 * spreadThreshold 15, epsilon 0.001, persistentDecay 0.
 */
public record PollutionDefaults(int spreadIntervalTicks, DiffusionConfig diffusion) {
    public static PollutionDefaults standard() {
        // spreadIntervalTicks 20 and spreadFraction 0.02 are the ADR 0003 defaults.
        return new PollutionDefaults(20, new DiffusionConfig(0.02, 15.0, 0.0, 0.001));
    }
}
