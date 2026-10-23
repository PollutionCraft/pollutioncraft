package io.github.pollutioncraft.core;

/**
 * What happened to a chunk's pollution during the last interval. Transient: never persisted. Read by the
 * debug {@code rates} command and the F3 overlay.
 *
 * <p>For a chunk that survives pruning:
 * {@code Δlocal = emittedLastInterval + spreadIn - spreadOut - absorbed - decayed}.
 */
public record ChunkIntervalStats(double spreadIn, double spreadOut, double absorbed, double decayed) {
    public static final ChunkIntervalStats ZERO = new ChunkIntervalStats(0, 0, 0, 0);
}
