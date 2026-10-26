package io.github.pollutioncraft.core;

/**
 * Whether the simulation (and the {@code activeTicks} counter) may advance this tick.
 *
 * <p>Recommendation awaiting user confirmation (architecture 3.4): at least one player online. Changing
 * the rule is a one-line change here.
 */
public final class SimulationGate {
    private SimulationGate() {}

    public static boolean isOpen(int onlinePlayers) {
        return onlinePlayers >= 1;
    }
}
