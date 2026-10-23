package io.github.pollutioncraft.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongToDoubleFunction;

/**
 * One interval of the pollution simulation (architecture 3.3): emit, spread, absorb, decay, prune.
 *
 * <p>Everything is computed from a snapshot and applied at the end. Each chunk <em>pulls</em> its inflow from
 * its four neighbours in a fixed order and chunks are processed in ascending key order, so the result does not
 * depend on map insertion order and is bit-identical for equal inputs. No wall-clock, no randomness.
 *
 * <p>Pruning drops the sub-{@code epsilon} remainder without recording it in {@link ChunkIntervalStats}.
 */
public final class DiffusionStep {
    private DiffusionStep() {}

    /**
     * @param emissions pending emissions this interval by chunk key; each finite and {@code >= 0}
     * @param absorptionCapacity per-chunk absorption capacity for this interval; asked only for chunks that
     *     hold absorbable pollution, and must be finite and {@code >= 0} (else {@link IllegalArgumentException},
     *     thrown before the field is modified)
     */
    public static void run(
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

        // Snapshot after adding emissions (emissions land in the absorbable pool).
        TreeSet<Long> candidates = new TreeSet<>();
        for (long key : field.sortedKeys()) {
            candidates.add(key);
        }
        candidates.addAll(emissions.keySet());
        List<Long> spreaders = new ArrayList<>();
        Map<Long, double[]> snapshot = new HashMap<>();
        for (long key : candidates) {
            ChunkPollution c = field.get(key);
            double a = (c == null ? 0 : c.absorbable) + emissions.getOrDefault(key, 0.0);
            double p = c == null ? 0 : c.persistent;
            snapshot.put(key, new double[] {a, p});
            if (spreads(a + p, config)) {
                spreaders.add(key);
            }
        }
        for (long key : spreaders) {
            for (long n : neighbours(key)) {
                if (candidates.add(n)) {
                    snapshot.put(n, new double[] {0, 0});
                }
            }
        }

        long[] keys = candidates.stream().mapToLong(Long::longValue).toArray();
        double f = config.spreadFraction();
        double[] newAbsorbable = new double[keys.length];
        double[] newPersistent = new double[keys.length];
        ChunkIntervalStats[] stats = new ChunkIntervalStats[keys.length];

        for (int i = 0; i < keys.length; i++) {
            long key = keys[i];
            double[] own = snapshot.get(key);
            double a = own[0];
            double p = own[1];

            double outFactor = spreads(a + p, config) ? 4 * f : 0;
            double inA = 0;
            double inP = 0;
            for (long n : neighbours(key)) {
                double[] other = snapshot.get(n);
                if (other != null && spreads(other[0] + other[1], config)) {
                    inA += f * other[0];
                    inP += f * other[1];
                }
            }
            double spreadOut = outFactor * (a + p);
            double spreadIn = inA + inP;
            double a2 = a - outFactor * a + inA;
            double p2 = p - outFactor * p + inP;

            double absorbed = 0;
            if (a2 > 0) {
                double capacity = absorptionCapacity.applyAsDouble(key);
                if (!(capacity >= 0) || Double.isInfinite(capacity)) {
                    throw new IllegalArgumentException("absorption capacity must be finite and >= 0, was " + capacity
                            + " for chunk " + ChunkKey.x(key) + "," + ChunkKey.z(key));
                }
                absorbed = Math.min(a2, capacity);
            }
            double decayed = config.persistentDecay() * p2;

            newAbsorbable[i] = a2 - absorbed;
            newPersistent[i] = p2 - decayed;
            stats[i] = new ChunkIntervalStats(spreadIn, spreadOut, absorbed, decayed);
        }

        // Apply deltas.
        for (int i = 0; i < keys.length; i++) {
            long key = keys[i];
            double local = newAbsorbable[i] + newPersistent[i];
            if (local <= 0 || local < config.epsilon()) {
                field.remove(key);
                continue;
            }
            ChunkPollution c = field.getOrCreate(key);
            c.absorbable = newAbsorbable[i];
            c.persistent = newPersistent[i];
            c.emittedLastInterval = emissions.getOrDefault(key, 0.0);
            c.lastIntervalStats = stats[i];
        }
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
