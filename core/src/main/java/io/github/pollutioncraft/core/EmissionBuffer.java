package io.github.pollutioncraft.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Emissions waiting for the next interval (architecture 3.3 "Emission"). Server thread only; never saved.
 * Amounts are aggregated by block position, so repeated calls from one block sum.
 *
 * <p>Memory is bounded: a chunk tracks at most {@code cap} distinct positions per interval. Amounts from
 * further new positions are added to one per-chunk <em>unattributed</em> amount. It counts toward every total
 * but can never be a dominant source. {@link #requeue} follows the same rule, so a chunk holds at most
 * {@code cap + 1} entries however many intervals are dropped. The cap is read once per interval, when the
 * first emission after a {@link #drain} arrives.
 *
 * <p>Totals are summed over positions in ascending signed order of the packed position and then the
 * unattributed amount, so they do not depend on call order.
 */
public final class EmissionBuffer {
    /** Mutable per-chunk state. */
    private static final class Pending {
        final Map<Long, Double> byPosition = new HashMap<>();
        double unattributed;
        /** Running sum for {@link #pending}; the drained total is recomputed in a fixed order. */
        double running;
    }

    private final IntSupplier cap;
    private final Consumer<String> log;
    private final Map<Long, Pending> chunks = new HashMap<>();
    private final Set<String> rejectedSources = new HashSet<>();
    private int currentCap; // 0 = read it from the supplier on next use
    private boolean overflowLogged;

    /**
     * @param cap distinct attributed positions per chunk per interval; values below 1 count as 1
     * @param log receives one line per problem; see {@link #add} and the overflow rule
     */
    public EmissionBuffer(IntSupplier cap, Consumer<String> log) {
        this.cap = cap;
        this.log = log;
    }

    /** True for a finite amount {@code >= 0}, the only kind {@link #add} accepts. */
    public static boolean isValidAmount(double amount) {
        return amount >= 0 && !Double.isInfinite(amount);
    }

    /**
     * Adds {@code amount} PU at a block position. NaN, infinite or negative amounts are dropped and logged
     * once per {@code sourceKey}; {@code 0} does nothing.
     *
     * @param chunkKey the chunk of the position ({@link ChunkKey#pack})
     * @param packedPos the block position as {@code BlockPos.asLong()}
     */
    public void add(long chunkKey, long packedPos, double amount, String sourceKey) {
        if (!isValidAmount(amount)) {
            if (rejectedSources.add(sourceKey)) {
                log.accept("Dropped emission of " + amount + " PU from " + sourceKey
                        + ": amounts must be finite and >= 0. Further bad calls from this source are not logged");
            }
            return;
        }
        if (amount == 0) {
            return;
        }
        addPosition(chunkKey, chunks.computeIfAbsent(chunkKey, key -> new Pending()), packedPos, amount);
    }

    /**
     * Takes everything buffered and leaves the buffer empty, so draining twice never counts an amount twice.
     * Starts a new interval for the cap and the overflow log line.
     */
    public DrainedEmissions drain() {
        currentCap = 0;
        overflowLogged = false;
        if (chunks.isEmpty()) {
            return DrainedEmissions.NONE;
        }
        long[] keys = chunks.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        DrainedEmissions.Chunk[] drained = new DrainedEmissions.Chunk[keys.length];
        double total = 0;
        for (int i = 0; i < keys.length; i++) {
            drained[i] = freeze(chunks.get(keys[i]));
            total += drained[i].total();
        }
        chunks.clear();
        return new DrainedEmissions(keys, drained, total);
    }

    /**
     * Merges a drained snapshot back (its interval was dropped), never losing an amount. Anything over the
     * cap is folded into the unattributed amount.
     */
    public void requeue(DrainedEmissions drained) {
        long[] keys = drained.chunkKeys();
        for (int i = 0; i < keys.length; i++) {
            DrainedEmissions.Chunk back = drained.chunkAt(i);
            Pending pending = chunks.computeIfAbsent(keys[i], key -> new Pending());
            for (int p = 0; p < back.positions().length; p++) {
                addPosition(keys[i], pending, back.positions()[p], back.amounts()[p]);
            }
            pending.unattributed += back.unattributed();
            pending.running += back.unattributed();
        }
    }

    /** Buffered, not yet drained PU of the chunk (informational: a running sum, 0 if none). */
    public double pending(long chunkKey) {
        Pending pending = chunks.get(chunkKey);
        return pending == null ? 0.0 : pending.running;
    }

    /** Entries held for the chunk: tracked positions plus one for a non-empty unattributed amount. */
    int trackedEntries(long chunkKey) {
        Pending pending = chunks.get(chunkKey);
        return pending == null ? 0 : pending.byPosition.size() + (pending.unattributed > 0 ? 1 : 0);
    }

    private void addPosition(long chunkKey, Pending pending, long packedPos, double amount) {
        pending.running += amount;
        Double current = pending.byPosition.get(packedPos);
        if (current != null) {
            pending.byPosition.put(packedPos, current + amount);
        } else if (pending.byPosition.size() < currentCap()) {
            pending.byPosition.put(packedPos, amount);
        } else {
            pending.unattributed += amount;
            if (!overflowLogged) {
                overflowLogged = true;
                log.accept("Chunk " + ChunkKey.x(chunkKey) + "," + ChunkKey.z(chunkKey) + " has more than "
                        + currentCap() + " distinct emitter positions this interval; the excess still counts but is not "
                        + "attributed (config maxEmittersPerChunk). Logged once per interval");
            }
        }
    }

    private int currentCap() {
        if (currentCap == 0) {
            currentCap = Math.max(1, cap.getAsInt());
        }
        return currentCap;
    }

    private static DrainedEmissions.Chunk freeze(Pending pending) {
        long[] positions = pending.byPosition.keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        double[] amounts = new double[positions.length];
        double total = 0;
        long dominantPos = 0;
        double dominantRate = 0;
        for (int i = 0; i < positions.length; i++) {
            amounts[i] = pending.byPosition.get(positions[i]);
            total += amounts[i];
            if (amounts[i] > dominantRate) { // strict: on a tie the lower position, met first, stays
                dominantRate = amounts[i];
                dominantPos = positions[i];
            }
        }
        total += pending.unattributed;
        return new DrainedEmissions.Chunk(
                positions, amounts, pending.unattributed, total, dominantPos, dominantRate);
    }
}
