package io.github.pollutioncraft.core;

import java.util.Arrays;
import java.util.Map;
import java.util.function.LongToDoubleFunction;

/**
 * One diffusion interval that can be computed in slices (architecture 3.3). {@link #start} snapshots the
 * field; {@link #advance} computes some chunks of the new state into private arrays; {@link #apply} writes
 * all deltas at once. The field is untouched until {@code apply}, so the result is identical however the
 * work is sliced, and an exception thrown while computing leaves the field unchanged.
 *
 * <p>Each chunk <em>pulls</em> its inflow from its four neighbours in a fixed order (-x, +x, -z, +z) and
 * chunks are handled in ascending key order, so results are bit-identical for equal inputs whatever the
 * map insertion order. No wall-clock, no randomness. Primitive arrays keep a 2,500-chunk step cheap.
 *
 * <p>Pruning drops the sub-{@code epsilon} remainder without recording it in {@link ChunkIntervalStats}.
 */
public final class DiffusionJob {
    private final PollutionField field;
    private final LongToDoubleFunction absorptionCapacity;
    private final DiffusionConfig config;

    private final long[] keys;
    private final double[] absorbable;
    private final double[] persistent;
    private final double[] emitted;
    private final boolean[] spreads;

    private final double[] newAbsorbable;
    private final double[] newPersistent;
    private final ChunkIntervalStats[] stats;
    private int next;

    private DiffusionJob(
            PollutionField field,
            Map<Long, Double> emissions,
            LongToDoubleFunction absorptionCapacity,
            DiffusionConfig config) {
        this.field = field;
        this.absorptionCapacity = absorptionCapacity;
        this.config = config;

        long[] base = candidateKeys(field, emissions);
        boolean[] baseSpreads = new boolean[base.length];
        int extras = 0;
        long[] extraKeys = new long[base.length * 4];
        for (int i = 0; i < base.length; i++) {
            ChunkPollution c = field.get(base[i]);
            double local = (c == null ? 0 : c.absorbable + c.persistent) + emissions.getOrDefault(base[i], 0.0);
            baseSpreads[i] = spreads(local, config);
            if (baseSpreads[i]) {
                for (long n : neighbours(base[i])) {
                    if (Arrays.binarySearch(base, n) < 0) {
                        extraKeys[extras++] = n;
                    }
                }
            }
        }
        this.keys = merge(base, extraKeys, extras);

        int n = keys.length;
        absorbable = new double[n];
        persistent = new double[n];
        emitted = new double[n];
        spreads = new boolean[n];
        for (int i = 0; i < n; i++) {
            ChunkPollution c = field.get(keys[i]);
            double e = emissions.getOrDefault(keys[i], 0.0);
            emitted[i] = e;
            absorbable[i] = (c == null ? 0 : c.absorbable) + e; // emissions land in the absorbable pool
            persistent[i] = c == null ? 0 : c.persistent;
            spreads[i] = spreads(absorbable[i] + persistent[i], config);
        }
        newAbsorbable = new double[n];
        newPersistent = new double[n];
        stats = new ChunkIntervalStats[n];
    }

    /**
     * Snapshots {@code field} plus {@code emissions}.
     *
     * @param emissions pending emissions this interval by chunk key; each finite and {@code >= 0}
     * @param absorptionCapacity per-chunk absorption capacity; asked only for chunks that hold absorbable
     *     pollution, and must be finite and {@code >= 0} (else {@link IllegalArgumentException} from
     *     {@link #advance}, before the field is modified)
     */
    public static DiffusionJob start(
            PollutionField field,
            Map<Long, Double> emissions,
            LongToDoubleFunction absorptionCapacity,
            DiffusionConfig config) {
        for (Map.Entry<Long, Double> e : emissions.entrySet()) {
            double amount = e.getValue();
            if (!(amount >= 0) || Double.isInfinite(amount)) {
                throw new IllegalArgumentException("emission must be finite and >= 0, was " + amount);
            }
        }
        return new DiffusionJob(field, emissions, absorptionCapacity, config);
    }

    /** Chunks in this job (stored chunks, emission targets and their receiving neighbours). */
    public int chunkCount() {
        return keys.length;
    }

    public int computedChunks() {
        return next;
    }

    public boolean isComputed() {
        return next >= keys.length;
    }

    /** Computes up to {@code maxChunks} more chunks. Returns {@code true} once every chunk is computed. */
    public boolean advance(int maxChunks) {
        if (maxChunks < 1) {
            throw new IllegalArgumentException("maxChunks must be >= 1, was " + maxChunks);
        }
        int end = (int) Math.min((long) next + maxChunks, keys.length);
        for (int i = next; i < end; i++) {
            compute(i);
        }
        next = end;
        return isComputed();
    }

    private void compute(int i) {
        double f = config.spreadFraction();
        double a = absorbable[i];
        double p = persistent[i];

        double outFactor = spreads[i] ? 4 * f : 0;
        double inA = 0;
        double inP = 0;
        for (long n : neighbours(keys[i])) {
            int j = Arrays.binarySearch(keys, n);
            if (j >= 0 && spreads[j]) {
                inA += f * absorbable[j];
                inP += f * persistent[j];
            }
        }
        double spreadOut = outFactor * (a + p);
        double spreadIn = inA + inP;
        double a2 = a - outFactor * a + inA;
        double p2 = p - outFactor * p + inP;

        double absorbed = 0;
        if (a2 > 0) {
            double capacity = absorptionCapacity.applyAsDouble(keys[i]);
            if (!(capacity >= 0) || Double.isInfinite(capacity)) {
                throw new IllegalArgumentException("absorption capacity must be finite and >= 0, was " + capacity
                        + " for chunk " + ChunkKey.x(keys[i]) + "," + ChunkKey.z(keys[i]));
            }
            absorbed = Math.min(a2, capacity);
        }
        double decayed = config.persistentDecay() * p2;

        newAbsorbable[i] = a2 - absorbed;
        newPersistent[i] = p2 - decayed;
        stats[i] = new ChunkIntervalStats(spreadIn, spreadOut, absorbed, decayed);
    }

    /** Writes the computed state into the field, pruning chunks below {@code epsilon}. */
    public void apply() {
        if (!isComputed()) {
            throw new IllegalStateException("apply() before every chunk was computed");
        }
        for (int i = 0; i < keys.length; i++) {
            double local = newAbsorbable[i] + newPersistent[i];
            if (local <= 0 || local < config.epsilon()) {
                field.remove(keys[i]);
                continue;
            }
            ChunkPollution c = field.getOrCreate(keys[i]);
            c.absorbable = newAbsorbable[i];
            c.persistent = newPersistent[i];
            c.emittedLastInterval = emitted[i];
            c.lastIntervalStats = stats[i];
        }
    }

    private static long[] candidateKeys(PollutionField field, Map<Long, Double> emissions) {
        long[] stored = field.sortedKeys();
        long[] emitting = new long[emissions.size()];
        int i = 0;
        for (long key : emissions.keySet()) {
            emitting[i++] = key;
        }
        return merge(stored, emitting, emitting.length);
    }

    /** Sorted union of {@code sortedBase} and {@code extra[0, extraCount)}, without duplicates. */
    private static long[] merge(long[] sortedBase, long[] extra, int extraCount) {
        long[] all = Arrays.copyOf(sortedBase, sortedBase.length + extraCount);
        System.arraycopy(extra, 0, all, sortedBase.length, extraCount);
        Arrays.sort(all);
        int unique = 0;
        for (int i = 0; i < all.length; i++) {
            if (i == 0 || all[i] != all[i - 1]) {
                all[unique++] = all[i];
            }
        }
        return Arrays.copyOf(all, unique);
    }

    private static boolean spreads(double local, DiffusionConfig config) {
        return local > 0 && local >= config.spreadThreshold();
    }

    /** Fixed order: -x, +x, -z, +z. */
    private static long[] neighbours(long key) {
        int x = ChunkKey.x(key);
        int z = ChunkKey.z(key);
        return new long[] {ChunkKey.pack(x - 1, z), ChunkKey.pack(x + 1, z), ChunkKey.pack(x, z - 1), ChunkKey.pack(x, z + 1)};
    }
}
