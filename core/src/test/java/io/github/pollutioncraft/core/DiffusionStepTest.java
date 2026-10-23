package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongToDoubleFunction;
import org.junit.jupiter.api.Test;

class DiffusionStepTest {
    private static final LongToDoubleFunction NO_ABSORPTION = key -> 0.0;
    private static final Map<Long, Double> NO_EMISSIONS = Map.of();

    private static DiffusionConfig config(double fraction, double threshold, double decay, double epsilon) {
        return new DiffusionConfig(fraction, threshold, decay, epsilon);
    }

    private static PollutionField fieldWith(long key, double absorbable, double persistent) {
        PollutionField field = new PollutionField();
        ChunkPollution c = field.getOrCreate(key);
        c.absorbable = absorbable;
        c.persistent = persistent;
        return field;
    }

    private static double total(PollutionField field) {
        double sum = 0;
        for (long key : field.sortedKeys()) {
            sum += field.get(key).local();
        }
        return sum;
    }

    @Test
    void seededChunkSpreadsToFourNeighboursAndTotalRisesOnlyByEmission() {
        long origin = ChunkKey.pack(0, 0);
        PollutionField field = fieldWith(origin, 100.0, 0.0);

        DiffusionStep.run(field, Map.of(origin, 10.0), NO_ABSORPTION, config(0.02, 1.0, 0.0, 1e-12));

        // 110 after emission; each neighbour gets 2% of 110, the origin keeps 92%.
        assertEquals(110.0 * 0.92, field.get(origin).absorbable, 1e-9);
        for (long n : List.of(ChunkKey.pack(1, 0), ChunkKey.pack(-1, 0), ChunkKey.pack(0, 1), ChunkKey.pack(0, -1))) {
            assertEquals(110.0 * 0.02, field.get(n).absorbable, 1e-9);
        }
        assertNull(field.get(ChunkKey.pack(1, 1)), "diagonals receive nothing");
        assertEquals(110.0, total(field), 1e-9);
    }

    @Test
    void belowSpreadThresholdNothingSpreads() {
        long origin = ChunkKey.pack(0, 0);
        PollutionField field = fieldWith(origin, 0.6, 0.3); // local 0.9 < 1.0

        DiffusionStep.run(field, NO_EMISSIONS, NO_ABSORPTION, config(0.02, 1.0, 0.0, 1e-12));

        assertEquals(1, field.size());
        assertEquals(0.6, field.get(origin).absorbable, 0.0);
        assertEquals(0.3, field.get(origin).persistent, 0.0);
    }

    @Test
    void bothPoolsSpreadPersistentDecaysAbsorbableDoesNot() {
        long origin = ChunkKey.pack(0, 0);
        PollutionField field = fieldWith(origin, 50.0, 50.0);

        DiffusionStep.run(field, NO_EMISSIONS, NO_ABSORPTION, config(0.1, 1.0, 0.5, 1e-12));

        ChunkPollution east = field.get(ChunkKey.pack(1, 0));
        assertEquals(5.0, east.absorbable, 1e-12, "absorbable spreads and does not decay");
        assertEquals(2.5, east.persistent, 1e-12, "persistent spreads (5.0) then decays by half");
        ChunkPollution centre = field.get(origin);
        assertEquals(30.0, centre.absorbable, 1e-12);
        assertEquals(15.0, centre.persistent, 1e-12);
    }

    @Test
    void chunksBelowEpsilonArePruned() {
        long faint = ChunkKey.pack(5, 5);
        long strong = ChunkKey.pack(0, 0);
        PollutionField field = fieldWith(faint, 1e-9, 0.0);
        field.getOrCreate(strong).absorbable = 0.5;

        DiffusionStep.run(field, NO_EMISSIONS, NO_ABSORPTION, config(0.02, 100.0, 0.0, 1e-6));

        assertNull(field.get(faint));
        assertTrue(field.get(strong) != null);
    }

    @Test
    void absorptionIsCappedByCapacityAndByWhatIsPresent() {
        long a = ChunkKey.pack(0, 0);
        long b = ChunkKey.pack(10, 0);
        PollutionField field = fieldWith(a, 5.0, 0.0);
        field.getOrCreate(b).absorbable = 1.0;
        LongToDoubleFunction capacity = key -> key == a ? 2.0 : 100.0;

        DiffusionStep.run(field, NO_EMISSIONS, capacity, config(0.02, 100.0, 0.0, 1e-12));

        assertEquals(3.0, field.get(a).absorbable, 1e-12);
        assertEquals(2.0, field.get(a).lastIntervalStats.absorbed(), 1e-12);
        assertNull(field.get(b), "fully absorbed chunk is pruned");
    }

    @Test
    void statsReconcileForEveryChunk() {
        Random rnd = new Random(42);
        PollutionField field = new PollutionField();
        for (int i = 0; i < 40; i++) {
            ChunkPollution c = field.getOrCreate(ChunkKey.pack(rnd.nextInt(8) - 4, rnd.nextInt(8) - 4));
            c.absorbable = rnd.nextDouble() * 80;
            c.persistent = rnd.nextDouble() * 40;
        }
        Map<Long, Double> emissions = Map.of(ChunkKey.pack(0, 0), 7.5, ChunkKey.pack(20, 20), 3.0);
        HashMap<Long, Double> localBefore = new HashMap<>();
        for (long k : field.sortedKeys()) {
            localBefore.put(k, field.get(k).local());
        }
        LongToDoubleFunction capacity = key -> (key & 1) == 0 ? 3.0 : 0.5;

        DiffusionStep.run(field, emissions, capacity, config(0.05, 2.0, 0.1, 0.0));

        for (long k : field.sortedKeys()) {
            ChunkPollution c = field.get(k);
            ChunkIntervalStats s = c.lastIntervalStats;
            double delta = c.local() - localBefore.getOrDefault(k, 0.0);
            double expected = c.emittedLastInterval + s.spreadIn() - s.spreadOut() - s.absorbed() - s.decayed();
            assertEquals(expected, delta, 1e-9, "chunk " + ChunkKey.x(k) + "," + ChunkKey.z(k));
        }
        assertEquals(7.5, field.get(ChunkKey.pack(0, 0)).emittedLastInterval, 0.0);
    }

    @Test
    void resultIsBitIdenticalWhateverTheInsertionOrder() {
        Random rnd = new Random(7);
        long[] keys = new long[60];
        double[] abs = new double[keys.length];
        double[] per = new double[keys.length];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = ChunkKey.pack(rnd.nextInt(12) - 6, rnd.nextInt(12) - 6);
            abs[i] = rnd.nextDouble() * 100;
            per[i] = rnd.nextDouble() * 30;
        }
        PollutionField forward = new PollutionField();
        PollutionField backward = new PollutionField();
        for (int i = 0; i < keys.length; i++) {
            ChunkPollution c = forward.getOrCreate(keys[i]);
            c.absorbable = abs[i];
            c.persistent = per[i];
        }
        for (int i = keys.length - 1; i >= 0; i--) {
            ChunkPollution c = backward.getOrCreate(keys[i]);
            c.absorbable = abs[i];
            c.persistent = per[i];
        }
        // Duplicate keys were overwritten in a different order above; make both fields equal first.
        for (long k : forward.sortedKeys()) {
            backward.get(k).absorbable = forward.get(k).absorbable;
            backward.get(k).persistent = forward.get(k).persistent;
        }
        LongToDoubleFunction capacity = key -> Math.abs(key % 5) * 0.37;
        DiffusionConfig cfg = config(0.037, 1.5, 0.013, 1e-7);
        Map<Long, Double> emissions = Map.of(ChunkKey.pack(1, 1), 12.345, ChunkKey.pack(-3, 2), 0.1);

        for (int step = 0; step < 5; step++) {
            DiffusionStep.run(forward, emissions, capacity, cfg);
            DiffusionStep.run(backward, emissions, capacity, cfg);
        }

        assertEquals(forward.size(), backward.size());
        for (long k : forward.sortedKeys()) {
            ChunkPollution f = forward.get(k);
            ChunkPollution b = backward.get(k);
            assertEquals(Double.doubleToLongBits(f.absorbable), Double.doubleToLongBits(b.absorbable));
            assertEquals(Double.doubleToLongBits(f.persistent), Double.doubleToLongBits(b.persistent));
            assertEquals(f.lastIntervalStats, b.lastIntervalStats);
        }
    }

    @Test
    void configRejectsUnstableOrInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> config(0.25, 1, 0, 0), "4 x fraction = 1");
        assertThrows(IllegalArgumentException.class, () -> config(0.3, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> config(-0.01, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> config(Double.NaN, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> config(0.02, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> config(0.02, 1, 1.5, 0));
        assertThrows(IllegalArgumentException.class, () -> config(0.02, 1, 0, -1));
        config(0.2499, 0, 1.0, 0); // valid edge values
    }

    @Test
    void invalidEmissionsAreRejectedAndLeaveTheFieldUntouched() {
        long origin = ChunkKey.pack(0, 0);
        PollutionField field = fieldWith(origin, 10.0, 0.0);
        DiffusionConfig cfg = config(0.02, 1.0, 0.0, 1e-9);
        assertThrows(IllegalArgumentException.class, () -> DiffusionStep.run(field, Map.of(origin, -1.0), NO_ABSORPTION, cfg));
        assertThrows(IllegalArgumentException.class, () -> DiffusionStep.run(field, Map.of(origin, Double.NaN), NO_ABSORPTION, cfg));
        assertThrows(IllegalArgumentException.class, () -> DiffusionStep.run(field, Map.of(origin, Double.POSITIVE_INFINITY), NO_ABSORPTION, cfg));
        assertEquals(10.0, field.get(origin).absorbable, 0.0);
        assertFalse(field.size() != 1);
    }

    @Test
    void invalidCapacityIsRejectedAndLeavesTheFieldUntouched() {
        long origin = ChunkKey.pack(0, 0);
        PollutionField field = fieldWith(origin, 10.0, 4.0);
        DiffusionConfig cfg = config(0.02, 1.0, 0.1, 1e-9);
        for (double bad : new double[] {Double.NaN, -1.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class, () -> DiffusionStep.run(field, NO_EMISSIONS, key -> bad, cfg), "capacity " + bad);
            assertTrue(e.getMessage().matches(".*for chunk -?\\d+,-?\\d+"), "message names the chunk: " + e.getMessage());
        }
        assertEquals(1, field.size());
        assertEquals(10.0, field.get(origin).absorbable, 0.0);
        assertEquals(4.0, field.get(origin).persistent, 0.0);
        assertEquals(ChunkIntervalStats.ZERO, field.get(origin).lastIntervalStats);
    }
}
