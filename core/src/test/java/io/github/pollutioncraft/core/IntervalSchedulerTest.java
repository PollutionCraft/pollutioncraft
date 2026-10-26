package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleConsumer;
import java.util.function.LongToDoubleFunction;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IntervalSchedulerTest {
    private static final DiffusionConfig CONFIG = new DiffusionConfig(0.03, 1.0, 0.0, 1e-9);
    private static final LongToDoubleFunction CAPACITY = key -> Math.abs(key % 4) * 0.2;
    private static final DrainedEmissions NO_EMISSIONS = DrainedEmissions.NONE;
    private static final DoubleConsumer IGNORE = total -> {};

    private static IntervalScheduler scheduler(int intervalTicks) {
        return new IntervalScheduler(
                () -> new IntervalScheduler.Settings(intervalTicks, CONFIG), new EmissionBuffer(() -> 4096, message -> {}));
    }

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
            assertEquals(Double.doubleToLongBits(e.emittedLastInterval), Double.doubleToLongBits(a.emittedLastInterval));
            assertEquals(e.dominantSourcePos, a.dominantSourcePos);
            assertEquals(Double.doubleToLongBits(e.dominantSourceRate), Double.doubleToLongBits(a.dominantSourceRate));
        }
    }

    @Test
    void slicedIntervalEqualsTheSinglePassStep() {
        PollutionField sliced = seeded(1);
        PollutionField single = seeded(1);
        IntervalScheduler scheduler = scheduler(20);

        for (int tick = 0; tick < 20; tick++) {
            scheduler.tick(sliced, CAPACITY, IGNORE);
        }
        DiffusionStep.run(single, NO_EMISSIONS, CAPACITY, CONFIG);

        assertSameBits(single, sliced);
    }

    @Test
    void deltasAreAppliedOnlyAtTheEndOfTheInterval() {
        PollutionField field = seeded(2);
        PollutionField untouched = seeded(2);
        IntervalScheduler scheduler = scheduler(20);

        for (int tick = 0; tick < 19; tick++) {
            scheduler.tick(field, CAPACITY, IGNORE);
        }
        assertSameBits(untouched, field);
        scheduler.tick(field, CAPACITY, IGNORE);
        DiffusionStep.run(untouched, NO_EMISSIONS, CAPACITY, CONFIG);
        assertSameBits(untouched, field);
    }

    @Test
    void severalIntervalsEqualRepeatedSinglePasses() {
        PollutionField sliced = seeded(3);
        PollutionField single = seeded(3);
        IntervalScheduler scheduler = scheduler(7);

        for (int tick = 0; tick < 7 * 4; tick++) {
            scheduler.tick(sliced, CAPACITY, IGNORE);
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
        IntervalScheduler scheduler = scheduler(1);

        scheduler.tick(a, CAPACITY, IGNORE);
        DiffusionStep.run(b, NO_EMISSIONS, CAPACITY, CONFIG);

        assertSameBits(b, a);
    }

    @Test
    void stepNowRunsTheSameCodePathSynchronously() {
        PollutionField now = seeded(5);
        PollutionField single = seeded(5);
        IntervalScheduler scheduler = scheduler(20);

        scheduler.stepNow(now, CAPACITY, 3, IGNORE);
        for (int i = 0; i < 3; i++) {
            DiffusionStep.run(single, NO_EMISSIONS, CAPACITY, CONFIG);
        }

        assertSameBits(single, now);
    }

    @Test
    void stepNowFinishesAnInFlightIntervalFirst() {
        PollutionField now = seeded(6);
        PollutionField single = seeded(6);
        IntervalScheduler scheduler = scheduler(20);
        for (int tick = 0; tick < 5; tick++) {
            scheduler.tick(now, CAPACITY, IGNORE);
        }

        scheduler.stepNow(now, CAPACITY, 1, IGNORE);
        DiffusionStep.run(single, NO_EMISSIONS, CAPACITY, CONFIG);
        DiffusionStep.run(single, NO_EMISSIONS, CAPACITY, CONFIG);

        assertSameBits(single, now);
    }

    @Test
    void tickReportsTrueOnlyWhenAnIntervalWasApplied() {
        PollutionField field = seeded(8);
        IntervalScheduler scheduler = scheduler(5);

        for (int tick = 1; tick <= 10; tick++) {
            boolean applied = scheduler.tick(field, CAPACITY, IGNORE);
            assertEquals(tick % 5 == 0, applied, "tick " + tick);
        }
    }

    @Test
    void settingsChangedMidIntervalApplyFromTheNextInterval() {
        AtomicReference<IntervalScheduler.Settings> current =
                new AtomicReference<>(new IntervalScheduler.Settings(5, CONFIG));
        IntervalScheduler scheduler = new IntervalScheduler(current::get, new EmissionBuffer(() -> 4096, message -> {}));
        PollutionField field = seeded(9);
        PollutionField expected = seeded(9);
        DiffusionStep.run(expected, NO_EMISSIONS, CAPACITY, CONFIG);

        scheduler.tick(field, CAPACITY, IGNORE);
        current.set(new IntervalScheduler.Settings(3, new DiffusionConfig(0.2, 1.0, 0.0, 1e-9)));
        for (int tick = 2; tick <= 5; tick++) {
            assertEquals(tick == 5, scheduler.tick(field, CAPACITY, IGNORE), "old length kept, tick " + tick);
        }
        assertSameBits(expected, field);

        PollutionField second = seeded(9);
        DiffusionStep.run(second, NO_EMISSIONS, CAPACITY, current.get().config());
        PollutionField next = seeded(9);
        for (int tick = 1; tick <= 3; tick++) {
            assertEquals(tick == 3, scheduler.tick(next, CAPACITY, IGNORE), "new length, tick " + tick);
        }
        assertSameBits(second, next);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new IntervalScheduler.Settings(0, CONFIG));
        IntervalScheduler scheduler = scheduler(20);
        assertThrows(IllegalArgumentException.class, () -> scheduler.stepNow(seeded(7), CAPACITY, -1, IGNORE));
    }

    private static final long SOURCE_CHUNK = ChunkKey.pack(2, 3);
    private static final long SOURCE_POS = 0x1234L;

    /** A scheduler with its buffer and the totals it reported on commit. */
    private static final class Rig {
        final EmissionBuffer buffer;
        final IntervalScheduler scheduler;
        final List<Double> committed = new ArrayList<>();

        Rig(int intervalTicks, int cap) {
            buffer = new EmissionBuffer(() -> cap, message -> {});
            scheduler = new IntervalScheduler(() -> new IntervalScheduler.Settings(intervalTicks, CONFIG), buffer);
        }

        void tick(PollutionField field, int ticks) {
            for (int i = 0; i < ticks; i++) {
                scheduler.tick(field, CAPACITY, committed::add);
            }
        }

        double reported() {
            return committed.stream().mapToDouble(Double::doubleValue).sum();
        }
    }

    @Test
    void emissionsAreAppliedAndReportedOnceWhenTheIntervalCommits() {
        Rig rig = new Rig(5, 10);
        PollutionField field = new PollutionField();
        rig.buffer.add(SOURCE_CHUNK, SOURCE_POS, 40.0, "t");

        rig.tick(field, 4);
        assertEquals(List.of(), rig.committed, "nothing is reported before the commit");
        assertEquals(0, field.size(), "the field is untouched before the commit");
        rig.tick(field, 1);

        assertEquals(List.of(40.0), rig.committed);
        ChunkPollution chunk = field.get(SOURCE_CHUNK);
        assertEquals(40.0, chunk.emittedLastInterval, 0.0);
        assertEquals(SOURCE_POS, chunk.dominantSourcePos);
        assertEquals(40.0, chunk.dominantSourceRate, 0.0);
        assertEquals(0.0, rig.buffer.pending(SOURCE_CHUNK), 0.0);
    }

    @Test
    void anEmissionAddedMidIntervalWaitsForTheNextInterval() {
        Rig rig = new Rig(4, 10);
        PollutionField field = new PollutionField();
        rig.buffer.add(SOURCE_CHUNK, SOURCE_POS, 10.0, "t");
        rig.tick(field, 1);
        rig.buffer.add(SOURCE_CHUNK, SOURCE_POS, 7.0, "t");
        rig.tick(field, 3);

        assertEquals(List.of(10.0), rig.committed);
        assertEquals(10.0, field.get(SOURCE_CHUNK).emittedLastInterval, 0.0);
        assertEquals(7.0, rig.buffer.pending(SOURCE_CHUNK), 0.0);

        rig.tick(field, 4);
        assertEquals(List.of(10.0, 7.0), rig.committed);
        assertEquals(7.0, field.get(SOURCE_CHUNK).emittedLastInterval, 0.0);
    }

    @Test
    void abandonRequeuesSoTheNextCommitAppliesAndReportsTheAmountOnce() {
        Rig rig = new Rig(5, 10);
        PollutionField field = new PollutionField();
        PollutionField expected = new PollutionField();
        rig.buffer.add(SOURCE_CHUNK, SOURCE_POS, 25.0, "t");
        rig.tick(field, 3);
        assertEquals(0.0, rig.buffer.pending(SOURCE_CHUNK), 0.0, "drained into the running interval");

        rig.scheduler.abandon();
        assertFalse(rig.scheduler.inFlight());
        assertEquals(25.0, rig.buffer.pending(SOURCE_CHUNK), 0.0, "re-queued");
        assertEquals(List.of(), rig.committed, "a dropped interval reports nothing");
        assertEquals(0, field.size());

        rig.tick(field, 5);
        rig.tick(field, 5); // a further interval must not apply it again

        assertEquals(List.of(25.0, 0.0), rig.committed);
        DiffusionStep.run(expected, EmissionBufferTest.chunkTotals(Map.of(SOURCE_CHUNK, 25.0)), CAPACITY, CONFIG);
        DiffusionStep.run(expected, NO_EMISSIONS, CAPACITY, CONFIG);
        assertEquals(expected.get(SOURCE_CHUNK).absorbable, field.get(SOURCE_CHUNK).absorbable, 0.0);
    }

    @Test
    void repeatedAbandonsKeepMemoryBoundedAndLoseNothing() {
        int cap = 3;
        Rig rig = new Rig(5, cap);
        PollutionField field = new PollutionField();
        for (int i = 0; i < 10; i++) {
            rig.buffer.add(SOURCE_CHUNK, i, 1.0, "t");
        }
        for (int round = 0; round < 25; round++) {
            rig.tick(field, 1);
            rig.buffer.add(SOURCE_CHUNK, 1000 + round, 1.0, "t");
            rig.scheduler.abandon();
            assertTrue(rig.buffer.trackedEntries(SOURCE_CHUNK) <= cap + 1, "round " + round);
        }

        rig.tick(field, 5);

        assertEquals(List.of(35.0), rig.committed);
    }

    @Test
    void aFailingStepNowIntervalRequeuesItsEmissions() {
        Rig rig = new Rig(5, 10);
        PollutionField field = new PollutionField();
        rig.buffer.add(SOURCE_CHUNK, SOURCE_POS, 12.0, "t");

        assertThrows(
                IllegalArgumentException.class,
                () -> rig.scheduler.stepNow(field, key -> Double.NaN, 1, rig.committed::add));

        assertEquals(List.of(), rig.committed);
        assertEquals(0, field.size(), "the field is unchanged");
        assertEquals(12.0, rig.buffer.pending(SOURCE_CHUNK), 0.0);

        rig.scheduler.stepNow(field, CAPACITY, 1, rig.committed::add);
        assertEquals(List.of(12.0), rig.committed);
    }

    @Test
    void anInFlightIntervalThatFailsWhileFinishingIsDroppedAndRequeued() {
        Rig rig = new Rig(5, 10);
        PollutionField field = new PollutionField();
        LongToDoubleFunction broken = key -> Double.NaN;
        rig.buffer.add(SOURCE_CHUNK, SOURCE_POS, 12.0, "t");
        for (int tick = 0; tick < 4 && rig.scheduler.inFlight() == (tick > 0); tick++) {
            try {
                rig.scheduler.tick(field, broken, rig.committed::add);
            } catch (IllegalArgumentException expectedWhileComputing) {
                break; // the failing slice leaves the interval in flight for the caller to abandon
            }
        }
        assertTrue(rig.scheduler.inFlight());

        assertThrows(IllegalArgumentException.class, () -> rig.scheduler.stepNow(field, CAPACITY, 0, rig.committed::add));

        assertFalse(rig.scheduler.inFlight());
        assertEquals(12.0, rig.buffer.pending(SOURCE_CHUNK), 0.0);
        assertEquals(List.of(), rig.committed);
    }

    @Test
    void stepNowReportsEachCommittedIntervalIncludingAFinishedInFlightOne() {
        Rig rig = new Rig(5, 10);
        PollutionField field = new PollutionField();
        rig.buffer.add(SOURCE_CHUNK, SOURCE_POS, 3.0, "t");
        rig.tick(field, 2); // drains the 3.0 into an in-flight interval
        rig.buffer.add(SOURCE_CHUNK, SOURCE_POS, 4.0, "t");
        List<Double> reported = new ArrayList<>();

        rig.scheduler.stepNow(field, CAPACITY, 3, reported::add);

        assertEquals(List.of(3.0, 4.0, 0.0, 0.0), reported);
    }

    @Test
    void slicedAndStepNowGiveBitIdenticalFieldsWithEmissions() {
        Rig sliced = new Rig(7, 10);
        Rig now = new Rig(7, 10);
        PollutionField slicedField = seeded(21);
        PollutionField nowField = seeded(21);
        Random rnd = new Random(5);
        for (int interval = 0; interval < 4; interval++) {
            for (int i = 0; i < 6; i++) {
                long chunk = ChunkKey.pack(rnd.nextInt(10) - 5, rnd.nextInt(10) - 5);
                long pos = rnd.nextInt(16);
                double amount = rnd.nextDouble() * 30;
                sliced.buffer.add(chunk, pos, amount, "t");
                now.buffer.add(chunk, pos, amount, "t");
            }
            sliced.tick(slicedField, 7);
            now.scheduler.stepNow(nowField, CAPACITY, 1, IGNORE);
        }

        assertSameBits(nowField, slicedField);
    }

    @Test
    void aChunkThatEmittedNothingThisIntervalLosesItsEmissionAndDominantSource() {
        Rig rig = new Rig(3, 10);
        PollutionField field = new PollutionField();
        rig.buffer.add(SOURCE_CHUNK, SOURCE_POS, 500.0, "t");
        rig.tick(field, 3);
        assertEquals(SOURCE_POS, field.get(SOURCE_CHUNK).dominantSourcePos);

        rig.tick(field, 3);

        ChunkPollution chunk = field.get(SOURCE_CHUNK);
        assertEquals(0.0, chunk.emittedLastInterval, 0.0);
        assertEquals(0L, chunk.dominantSourcePos);
        assertEquals(0.0, chunk.dominantSourceRate, 0.0);
        assertEquals(List.of(500.0, 0.0), rig.committed);
    }

    @Test
    void reportedTotalsAddUpToEverythingEmittedAndNeverGoNegative() {
        Rig rig = new Rig(4, 10);
        PollutionField field = new PollutionField();
        Random rnd = new Random(3);
        double emitted = 0;
        for (int interval = 0; interval < 30; interval++) {
            if (rnd.nextBoolean()) {
                double amount = rnd.nextDouble() * 10;
                rig.buffer.add(ChunkKey.pack(rnd.nextInt(3), 0), 1L, amount, "t");
                emitted += amount;
            }
            rig.tick(field, 4);
        }

        assertTrue(rig.committed.stream().allMatch(total -> total >= 0));
        assertEquals(emitted, rig.reported(), 1e-9);
    }
}
