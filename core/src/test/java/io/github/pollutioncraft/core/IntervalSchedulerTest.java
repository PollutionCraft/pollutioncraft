package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Random;
import java.util.function.LongToDoubleFunction;
import org.junit.jupiter.api.Test;

class IntervalSchedulerTest {
    private static final DiffusionConfig CONFIG = new DiffusionConfig(0.03, 1.0, 0.0, 1e-9);
    private static final LongToDoubleFunction CAPACITY = key -> Math.abs(key % 4) * 0.2;
    private static final Map<Long, Double> NO_EMISSIONS = Map.of();

    private static PollutionField seeded(long seed) {
        Random rnd = new Random(seed);
        PollutionField field = new PollutionField();
        for (int i = 0; i < 80; i++) {
            ChunkPollution c = field.getOrCreate(ChunkKey.pack(rnd.nextInt(14) - 7, rnd.nextInt(14) - 7));
            c.absorbable = rnd.nextDouble() * 60;
            c.persistent = rnd.nextDouble() * 5;
        }
        return field;
    }

    private static void assertSameBits(PollutionField expected, PollutionField actual) {
        assertEquals(expected.size(), actual.size());
        for (long k : expected.sortedKeys()) {
            ChunkPollution e = expected.get(k);
            ChunkPollution a = actual.get(k);
            assertEquals(Double.doubleToLongBits(e.absorbable), Double.doubleToLongBits(a.absorbable));
            assertEquals(Double.doubleToLongBits(e.persistent), Double.doubleToLongBits(a.persistent));
            assertEquals(e.lastIntervalStats, a.lastIntervalStats);
        }
    }

    @Test
    void slicedIntervalEqualsTheSinglePassStep() {
        PollutionField sliced = seeded(1);
        PollutionField single = seeded(1);
        IntervalScheduler scheduler = new IntervalScheduler(20, CONFIG);

        for (int tick = 0; tick < 20; tick++) {
            scheduler.tick(sliced, NO_EMISSIONS, CAPACITY);
        }
        DiffusionStep.run(single, NO_EMISSIONS, CAPACITY, CONFIG);

        assertSameBits(single, sliced);
    }

    @Test
    void deltasAreAppliedOnlyAtTheEndOfTheInterval() {
        PollutionField field = seeded(2);
        PollutionField untouched = seeded(2);
        IntervalScheduler scheduler = new IntervalScheduler(20, CONFIG);

        for (int tick = 0; tick < 19; tick++) {
            scheduler.tick(field, NO_EMISSIONS, CAPACITY);
        }
        assertSameBits(untouched, field);
        scheduler.tick(field, NO_EMISSIONS, CAPACITY);
        DiffusionStep.run(untouched, NO_EMISSIONS, CAPACITY, CONFIG);
        assertSameBits(untouched, field);
    }

    @Test
    void severalIntervalsEqualRepeatedSinglePasses() {
        PollutionField sliced = seeded(3);
        PollutionField single = seeded(3);
        IntervalScheduler scheduler = new IntervalScheduler(7, CONFIG);

        for (int tick = 0; tick < 7 * 4; tick++) {
            scheduler.tick(sliced, NO_EMISSIONS, CAPACITY);
        }
        for (int i = 0; i < 4; i++) {
            DiffusionStep.run(single, NO_EMISSIONS, CAPACITY, CONFIG);
        }

        assertSameBits(single, sliced);
    }

    @Test
    void intervalOfOneTickStepsEveryTick() {
        PollutionField a = seeded(4);
        PollutionField b = seeded(4);
        IntervalScheduler scheduler = new IntervalScheduler(1, CONFIG);

        scheduler.tick(a, NO_EMISSIONS, CAPACITY);
        DiffusionStep.run(b, NO_EMISSIONS, CAPACITY, CONFIG);

        assertSameBits(b, a);
    }

    @Test
    void stepNowRunsTheSameCodePathSynchronously() {
        PollutionField now = seeded(5);
        PollutionField single = seeded(5);
        IntervalScheduler scheduler = new IntervalScheduler(20, CONFIG);

        scheduler.stepNow(now, NO_EMISSIONS, CAPACITY, 3);
        for (int i = 0; i < 3; i++) {
            DiffusionStep.run(single, NO_EMISSIONS, CAPACITY, CONFIG);
        }

        assertSameBits(single, now);
    }

    @Test
    void stepNowFinishesAnInFlightIntervalFirst() {
        PollutionField now = seeded(6);
        PollutionField single = seeded(6);
        IntervalScheduler scheduler = new IntervalScheduler(20, CONFIG);
        for (int tick = 0; tick < 5; tick++) {
            scheduler.tick(now, NO_EMISSIONS, CAPACITY);
        }

        scheduler.stepNow(now, NO_EMISSIONS, CAPACITY, 1);
        DiffusionStep.run(single, NO_EMISSIONS, CAPACITY, CONFIG);
        DiffusionStep.run(single, NO_EMISSIONS, CAPACITY, CONFIG);

        assertSameBits(single, now);
    }

    @Test
    void tickReportsTrueOnlyWhenAnIntervalWasApplied() {
        PollutionField field = seeded(8);
        IntervalScheduler scheduler = new IntervalScheduler(5, CONFIG);

        for (int tick = 1; tick <= 10; tick++) {
            boolean applied = scheduler.tick(field, NO_EMISSIONS, CAPACITY);
            assertEquals(tick % 5 == 0, applied, "tick " + tick);
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new IntervalScheduler(0, CONFIG));
        IntervalScheduler scheduler = new IntervalScheduler(20, CONFIG);
        assertThrows(IllegalArgumentException.class, () -> scheduler.stepNow(seeded(7), NO_EMISSIONS, CAPACITY, -1));
    }
}
