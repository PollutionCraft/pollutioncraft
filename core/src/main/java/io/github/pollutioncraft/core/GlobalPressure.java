package io.github.pollutioncraft.core;

/** Global pressure in [0, 1) derived from time and total emission (ADR 0002). Maths arrive in task 016. */
public interface GlobalPressure {
    double pressure(long activeTicks, double grossEmitted);
}
