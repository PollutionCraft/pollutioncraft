package io.github.pollutioncraft.core;

/** Mutable pollution state of one chunk (architecture 3.2). Persistence is task 007's concern. */
public final class ChunkPollution {
    /** Pollution that absorbers can remove. */
    public double absorbable;
    /** Pollution only fixed decay removes. */
    public double persistent;
    /**
     * Gross emission in this chunk during the last committed interval, including amounts the per-chunk
     * position cap left unattributed. 0 when the chunk emitted nothing.
     */
    public double emittedLastInterval;
    /**
     * Packed block position of the emitter with the highest aggregated amount in the last committed interval
     * (ties: lowest packed position). Meaningless while {@link #dominantSourceRate} is 0.
     */
    public long dominantSourcePos;
    /** Amount emitted by {@link #dominantSourcePos} that interval; 0 means the chunk has no dominant source. */
    public double dominantSourceRate;
    /** Transient: not saved. Filled by {@link DiffusionJob#apply}. */
    public ChunkIntervalStats lastIntervalStats = ChunkIntervalStats.ZERO;

    /** Local pollution = absorbable + persistent. */
    public double local() {
        return absorbable + persistent;
    }
}
