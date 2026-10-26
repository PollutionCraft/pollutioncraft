package io.github.pollutioncraft.core;

import java.util.function.Consumer;

/**
 * Converts a {@link PollutionField} to and from {@link EncodedField}. Decoding never fails: unusable
 * entries (NaN, infinite or negative amounts, duplicate keys, incomplete columns) are skipped and
 * reported to {@code log}; data of an unknown future version is skipped entirely.
 *
 * <p>The transient {@link ChunkPollution#lastIntervalStats} is never encoded.
 */
public final class FieldCodec {
    public static final int DATA_VERSION = 1;

    private FieldCodec() {}

    public static EncodedField encode(PollutionField field) {
        long[] keys = field.sortedKeys();
        int n = keys.length;
        long[] absorbable = new long[n];
        long[] persistent = new long[n];
        long[] emitted = new long[n];
        long[] dominantPos = new long[n];
        long[] dominantRate = new long[n];
        for (int i = 0; i < n; i++) {
            ChunkPollution c = field.get(keys[i]);
            absorbable[i] = Double.doubleToRawLongBits(c.absorbable);
            persistent[i] = Double.doubleToRawLongBits(c.persistent);
            emitted[i] = Double.doubleToRawLongBits(c.emittedLastInterval);
            dominantPos[i] = c.dominantSourcePos;
            dominantRate[i] = Double.doubleToRawLongBits(c.dominantSourceRate);
        }
        return new EncodedField(DATA_VERSION, keys, absorbable, persistent, emitted, dominantPos, dominantRate);
    }

    public static PollutionField decode(EncodedField encoded, Consumer<String> log) {
        PollutionField field = new PollutionField();
        if (encoded.dataVersion() != DATA_VERSION) {
            log.accept("Ignoring pollution data of unsupported dataVersion " + encoded.dataVersion()
                    + " (this mod reads " + DATA_VERSION + ")");
            return field;
        }
        int n = encoded.keys().length;
        int complete = Math.min(n, Math.min(
                Math.min(encoded.absorbable().length, encoded.persistent().length),
                Math.min(encoded.emitted().length, Math.min(encoded.dominantPos().length, encoded.dominantRate().length))));
        if (complete < n) {
            log.accept("Pollution data has incomplete columns: keeping " + complete + " of " + n + " entries");
        }
        for (int i = 0; i < complete; i++) {
            long key = encoded.keys()[i];
            double absorbable = Double.longBitsToDouble(encoded.absorbable()[i]);
            double persistent = Double.longBitsToDouble(encoded.persistent()[i]);
            double emitted = Double.longBitsToDouble(encoded.emitted()[i]);
            double rate = Double.longBitsToDouble(encoded.dominantRate()[i]);
            if (!valid(absorbable) || !valid(persistent) || !valid(emitted) || !valid(rate)) {
                log.accept("Skipping corrupt pollution entry for chunk " + ChunkKey.x(key) + "," + ChunkKey.z(key));
                continue;
            }
            if (field.get(key) != null) {
                log.accept("Skipping duplicate pollution entry for chunk " + ChunkKey.x(key) + "," + ChunkKey.z(key));
                continue;
            }
            ChunkPollution c = field.getOrCreate(key);
            c.absorbable = absorbable;
            c.persistent = persistent;
            c.emittedLastInterval = emitted;
            c.dominantSourcePos = encoded.dominantPos()[i];
            c.dominantSourceRate = rate;
        }
        return field;
    }

    private static boolean valid(double v) {
        return v >= 0 && !Double.isInfinite(v);
    }
}
