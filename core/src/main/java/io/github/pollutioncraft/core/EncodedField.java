package io.github.pollutioncraft.core;

/**
 * Column-wise, Minecraft-free form of a {@link PollutionField} for storage. Doubles are carried as raw
 * IEEE-754 bits so a round trip is lossless. The platform layer maps each column to a {@code long[]} tag.
 */
public record EncodedField(
        int dataVersion,
        long[] keys,
        long[] absorbable,
        long[] persistent,
        long[] emitted,
        long[] dominantPos,
        long[] dominantRate) {

    /** Bytes of column data (6 longs per chunk), for the 200 KB-per-dimension budget. */
    public long payloadBytes() {
        return 8L * (keys.length + absorbable.length + persistent.length + emitted.length + dominantPos.length
                + dominantRate.length);
    }
}
