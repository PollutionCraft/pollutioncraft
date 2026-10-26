package io.github.pollutioncraft.core;

import java.util.Arrays;

/**
 * The emissions an {@link EmissionBuffer} handed to one interval (architecture 3.3): per emitting chunk the
 * total, the dominant source and the per-position amounts, so a dropped interval can put them back without
 * losing attribution. Immutable. Chunk keys are sorted ascending; positions within a chunk are sorted
 * ascending by signed packed value, so every sum below is taken in a fixed order.
 */
public final class DrainedEmissions {
    /** No emissions. */
    public static final DrainedEmissions NONE = new DrainedEmissions(new long[0], new Chunk[0], 0.0);

    /** One chunk's drained emissions. {@code unattributed} is what arrived past the per-chunk position cap. */
    record Chunk(long[] positions, double[] amounts, double unattributed, double total, long dominantPos, double dominantRate) {}

    private final long[] chunkKeys;
    private final Chunk[] chunks;
    private final double total;

    DrainedEmissions(long[] chunkKeys, Chunk[] chunks, double total) {
        this.chunkKeys = chunkKeys;
        this.chunks = chunks;
        this.total = total;
    }

    /** Everything drained, in PU: the chunk totals summed in ascending chunk-key order. */
    public double total() {
        return total;
    }

    public boolean isEmpty() {
        return chunkKeys.length == 0;
    }

    /** Total emitted into the chunk, 0 if it did not emit. */
    public double chunkTotal(long chunkKey) {
        Chunk chunk = chunk(chunkKey);
        return chunk == null ? 0.0 : chunk.total();
    }

    /** Packed position of the chunk's dominant source; 0 when it has none (see {@link #dominantRate}). */
    public long dominantPos(long chunkKey) {
        Chunk chunk = chunk(chunkKey);
        return chunk == null ? 0L : chunk.dominantPos();
    }

    /** Aggregated amount of the dominant source; 0 when the chunk has none. */
    public double dominantRate(long chunkKey) {
        Chunk chunk = chunk(chunkKey);
        return chunk == null ? 0.0 : chunk.dominantRate();
    }

    /** Distinct attributed positions drained for the chunk. */
    public int positionCount(long chunkKey) {
        Chunk chunk = chunk(chunkKey);
        return chunk == null ? 0 : chunk.positions().length;
    }

    /** Emitting chunk keys, ascending. Not copied: callers must not modify it. */
    long[] chunkKeys() {
        return chunkKeys;
    }

    Chunk chunkAt(int index) {
        return chunks[index];
    }

    private Chunk chunk(long chunkKey) {
        int index = Arrays.binarySearch(chunkKeys, chunkKey);
        return index < 0 ? null : chunks[index];
    }
}
