package io.github.pollutioncraft.core;

/** Mutable pollution state of one chunk (architecture 3.2). Persistence is task 007's concern. */
public final class ChunkPollution {
    /** Pollution that absorbers can remove. */
    public double absorbable;
    /** Pollution only fixed decay removes. */
    public double persistent;
    /** Gross emission in this chunk during the last interval. */
    public double emittedLastInterval;
    /** Packed block position of the highest-rate emitter last interval (see {@link #dominantSourceRate}). */
    public long dominantSourcePos;
    /** Emission rate of {@link #dominantSourcePos}; 0 when there is none. */
    public double dominantSourceRate;
    /** Transient: not saved. Filled by {@link DiffusionStep}. */
    public ChunkIntervalStats lastIntervalStats = ChunkIntervalStats.ZERO;

    /** Local pollution = absorbable + persistent. */
    public double local() {
        return absorbable + persistent;
    }
}
