package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongToDoubleFunction;
import org.junit.jupiter.api.Test;

/** The soft-cap capacity feeding the real diffusion step (ADR 0004 B), and the {@code r = 0} invariant (ADR 0004 E). */
class AbsorptionStepTest {
    private static final long CHUNK = ChunkKey.pack(0, 0);
    /** Nothing spreads: the threshold is far above anything here, so the chunk is isolated. */
    private static final DiffusionConfig ISOLATED = new DiffusionConfig(0.02, 1e9, 0.0, 1e-12);

    private static SoftCapAbsorption leavesOnly() {
        return new SoftCapAbsorption(List.of(new SoftCapAbsorption.Type("leaves", new AbsorberCurve(2.0, 64))));
    }

    private static void emit(PollutionField field, double amount, LongToDoubleFunction capacity) {
        DiffusionStep.run(field, EmissionBufferTest.chunkTotals(Map.of(CHUNK, amount)), capacity, ISOLATED);
    }

    @Test
    void emissionAtOrBelowTheCapacityIsAbsorbedEveryInterval() {
        double capacity = leavesOnly().capacity(new int[] {64});
        PollutionField field = new PollutionField();
        for (double emission : new double[] {0.5 * capacity, capacity}) {
            for (int interval = 1; interval <= 10; interval++) {
                emit(field, emission, key -> capacity);

                ChunkPollution chunk = field.get(CHUNK);
                assertEquals(0.0, chunk == null ? 0.0 : chunk.local(), 0.0, "emission " + emission + ", interval " + interval);
            }
        }
    }

    @Test
    void emissionAboveTheCapacityGrowsByTheDifferencePerIntervalAndAbsorbsExactlyTheCapacity() {
        double capacity = leavesOnly().capacity(new int[] {64});
        double emission = 3.0;
        PollutionField field = new PollutionField();

        for (int interval = 1; interval <= 20; interval++) {
            emit(field, emission, key -> capacity);

            ChunkPollution chunk = field.get(CHUNK);
            assertNotNull(chunk, "interval " + interval);
            assertEquals(interval * (emission - capacity), chunk.local(), 1e-9, "interval " + interval);
            assertEquals(Math.min(emission, capacity), chunk.lastIntervalStats.absorbed(), 1e-12, "interval " + interval);
        }
    }

    @Test
    void aChunkWithNoAbsorbersAbsorbsNothing() {
        double none = leavesOnly().capacity(new int[] {0});
        PollutionField field = new PollutionField();

        emit(field, 1.0, key -> none);

        assertEquals(1.0, field.get(CHUNK).local(), 0.0);
        assertEquals(0.0, field.get(CHUNK).lastIntervalStats.absorbed(), 0.0);
    }

    @Test
    void ifTheChunkEndsEmptyItIsPrunedSoNothingIsLeftBehind() {
        double capacity = leavesOnly().capacity(new int[] {64});
        PollutionField field = new PollutionField();

        emit(field, capacity / 2, key -> capacity);

        assertNull(field.get(CHUNK));
    }

    // ---- r = 0: nothing goes to the persistent pool in the alpha -----------------------------------------

    @Test
    void emissionsArriveOnlyInTheAbsorbablePool() {
        PollutionField field = new PollutionField();

        DiffusionStep.run(field, EmissionBufferTest.chunkTotals(Map.of(CHUNK, 7.5)), key -> 0.0, ISOLATED);

        assertEquals(7.5, field.get(CHUNK).absorbable, 0.0);
        assertEquals(0.0, field.get(CHUNK).persistent, 0.0);
    }

    @Test
    void overManyIntervalsWithEmissionsSpreadAndAbsorptionPersistentStaysExactlyZero() {
        SoftCapAbsorption absorption = new SoftCapAbsorption(List.of(
                new SoftCapAbsorption.Type("leaves", new AbsorberCurve(2.0, 64)), new SoftCapAbsorption.Type("grass", new AbsorberCurve(0.5, 128))));
        // Spreading is on (threshold well below the amounts), decay is on (it acts on persistent, which must stay 0).
        DiffusionConfig config = new DiffusionConfig(0.05, 5.0, 0.1, 1e-9);
        LongToDoubleFunction capacity = key -> absorption.capacity(new int[] {(int) (Math.abs(key) % 200), (int) (Math.abs(key / 3) % 300)});
        EmissionBuffer buffer = new EmissionBuffer(() -> 4096, message -> {});
        IntervalScheduler scheduler = new IntervalScheduler(() -> new IntervalScheduler.Settings(5, config), buffer);
        PollutionField field = new PollutionField();
        Random random = new Random(13);
        double[] committed = {0};

        for (int interval = 0; interval < 150; interval++) {
            for (int i = 0; i < 6; i++) {
                buffer.add(ChunkKey.pack(random.nextInt(6) - 3, random.nextInt(6) - 3), random.nextInt(32), 1 + random.nextDouble() * 40, "test");
            }
            for (int tick = 0; tick < 5; tick++) {
                scheduler.tick(field, capacity, total -> committed[0] += total);
            }
            for (long key : field.sortedKeys()) {
                ChunkPollution chunk = field.get(key);
                assertEquals(0.0, chunk.persistent, 0.0, "interval " + interval + ", chunk " + ChunkKey.x(key) + "," + ChunkKey.z(key));
                assertEquals(chunk.absorbable, chunk.local(), 0.0);
            }
        }
        assertTrue(committed[0] > 1_000, "the run really emitted: " + committed[0]);
        assertTrue(field.size() > 10, "and spread: " + field.size() + " chunks");
    }
}
