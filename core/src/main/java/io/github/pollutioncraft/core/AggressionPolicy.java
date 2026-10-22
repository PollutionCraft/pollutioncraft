package io.github.pollutioncraft.core;

/** Decides whether a chunk is aggressive (ADR 0002). Maths arrive in task 016. */
public interface AggressionPolicy {
    boolean isAggressive(double localPollution, double pressure);
}
