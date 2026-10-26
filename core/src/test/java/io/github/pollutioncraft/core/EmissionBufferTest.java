package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmissionBufferTest {
    private static final long CHUNK = ChunkKey.pack(3, -4);
    private static final long OTHER_CHUNK = ChunkKey.pack(-7, 9);

    private final List<String> logged = new ArrayList<>();

    private EmissionBuffer buffer(int cap) {
        return new EmissionBuffer(() -> cap, logged::add);
    }

    /** Emissions of one position per chunk, for tests of the diffusion that only care about chunk totals. */
    static DrainedEmissions chunkTotals(Map<Long, Double> totals) {
        EmissionBuffer buffer = new EmissionBuffer(() -> 4096, message -> {});
        totals.forEach((chunk, amount) -> buffer.add(chunk, 1L, amount, "test"));
        return buffer.drain();
    }

    @Test
    void repeatedCallsFromOnePositionSum() {
        EmissionBuffer buffer = buffer(10);
        buffer.add(CHUNK, 100L, 1.5, "a");
        buffer.add(CHUNK, 100L, 2.5, "a");

        DrainedEmissions drained = buffer.drain();

        assertEquals(4.0, drained.chunkTotal(CHUNK), 0.0);
        assertEquals(100L, drained.dominantPos(CHUNK));
        assertEquals(4.0, drained.dominantRate(CHUNK), 0.0);
        assertEquals(1, drained.positionCount(CHUNK));
    }

    @Test
    void dominantSourceIsTheHighestAggregatedPosition() {
        EmissionBuffer buffer = buffer(10);
        buffer.add(CHUNK, 1L, 3.0, "a");
        buffer.add(CHUNK, 2L, 2.0, "a");
        buffer.add(CHUNK, 2L, 2.0, "a"); // 4.0 in total beats 3.0

        DrainedEmissions drained = buffer.drain();

        assertEquals(2L, drained.dominantPos(CHUNK));
        assertEquals(4.0, drained.dominantRate(CHUNK), 0.0);
        assertEquals(7.0, drained.chunkTotal(CHUNK), 0.0);
    }

    @Test
    void tiesGoToTheLowestPackedPositionBySignedComparison() {
        for (boolean lowFirst : new boolean[] {true, false}) {
            EmissionBuffer buffer = buffer(10);
            long negative = -5L; // sorts below 3 under signed comparison
            if (lowFirst) {
                buffer.add(CHUNK, negative, 2.0, "a");
                buffer.add(CHUNK, 3L, 2.0, "a");
            } else {
                buffer.add(CHUNK, 3L, 2.0, "a");
                buffer.add(CHUNK, negative, 2.0, "a");
            }
            assertEquals(negative, buffer.drain().dominantPos(CHUNK), "lowFirst=" + lowFirst);
        }
    }

    @Test
    void chunkTotalDoesNotDependOnCallOrder() {
        double[] amounts = {0.1, 1e16, 0.2, 0.3, 1.0, 1e-9, 7.7};
        EmissionBuffer forward = buffer(10);
        EmissionBuffer backward = buffer(10);
        for (int i = 0; i < amounts.length; i++) {
            forward.add(CHUNK, i, amounts[i], "a");
            backward.add(CHUNK, amounts.length - 1 - i, amounts[amounts.length - 1 - i], "a");
        }

        DrainedEmissions a = forward.drain();
        DrainedEmissions b = backward.drain();

        assertEquals(Double.doubleToLongBits(a.chunkTotal(CHUNK)), Double.doubleToLongBits(b.chunkTotal(CHUNK)));
        assertEquals(Double.doubleToLongBits(a.total()), Double.doubleToLongBits(b.total()));
    }

    @Test
    void zeroIsASilentNoOp() {
        EmissionBuffer buffer = buffer(10);
        buffer.add(CHUNK, 1L, 0.0, "a");
        buffer.add(CHUNK, 1L, -0.0, "a");

        assertTrue(buffer.drain().isEmpty());
        assertEquals(0.0, buffer.pending(CHUNK), 0.0);
        assertTrue(logged.isEmpty());
    }

    @Test
    void negativeNanAndInfiniteAmountsAreRejected() {
        EmissionBuffer buffer = buffer(10);
        for (double bad : new double[] {-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(EmissionBuffer.isValidAmount(bad), "amount " + bad);
            buffer.add(CHUNK, 1L, bad, "a" + bad);
        }
        buffer.add(CHUNK, 1L, 2.0, "good");

        DrainedEmissions drained = buffer.drain();

        assertEquals(2.0, drained.total(), 0.0);
        assertEquals(4, logged.size());
    }

    @Test
    void invalidAmountsAreLoggedOncePerSource() {
        EmissionBuffer buffer = buffer(10);
        buffer.add(CHUNK, 1L, Double.NaN, "mod.A");
        buffer.add(CHUNK, 1L, -3.0, "mod.A");
        assertEquals(1, logged.size());
        assertTrue(logged.get(0).contains("mod.A"));

        buffer.add(CHUNK, 1L, Double.NaN, "mod.B");
        assertEquals(2, logged.size());
        assertTrue(logged.get(1).contains("mod.B"));
    }

    @Test
    void overTheCapNewPositionsAreUnattributedButStillCounted() {
        EmissionBuffer buffer = buffer(2);
        buffer.add(CHUNK, 10L, 1.0, "a");
        buffer.add(CHUNK, 20L, 3.0, "a");
        buffer.add(CHUNK, 30L, 50.0, "a"); // third distinct position: over the cap
        buffer.add(CHUNK, 10L, 1.0, "a"); // an already tracked position keeps summing

        DrainedEmissions drained = buffer.drain();

        assertEquals(55.0, drained.chunkTotal(CHUNK), 0.0);
        assertEquals(2, drained.positionCount(CHUNK));
        assertEquals(20L, drained.dominantPos(CHUNK), "the unattributed 50 can never be dominant");
        assertEquals(3.0, drained.dominantRate(CHUNK), 0.0);
        assertEquals(55.0, drained.total(), 0.0);
    }

    @Test
    void overflowIsLoggedOncePerIntervalNamingTheChunkAndCap() {
        EmissionBuffer buffer = buffer(1);
        buffer.add(CHUNK, 1L, 1.0, "a");
        buffer.add(CHUNK, 2L, 1.0, "a");
        buffer.add(OTHER_CHUNK, 1L, 1.0, "a");
        buffer.add(OTHER_CHUNK, 2L, 1.0, "a");
        buffer.add(CHUNK, 3L, 1.0, "a");

        assertEquals(1, logged.size());
        assertTrue(logged.get(0).contains("3,-4") && logged.get(0).contains("1"), logged.get(0));

        buffer.drain();
        buffer.add(CHUNK, 1L, 1.0, "a");
        buffer.add(CHUNK, 2L, 1.0, "a");
        assertEquals(2, logged.size(), "a new interval may log again");
    }

    @Test
    void drainingTwiceNeverDoubleCounts() {
        EmissionBuffer buffer = buffer(10);
        buffer.add(CHUNK, 1L, 4.0, "a");
        buffer.add(OTHER_CHUNK, 2L, 6.0, "a");

        DrainedEmissions first = buffer.drain();
        DrainedEmissions second = buffer.drain();

        assertEquals(10.0, first.total(), 0.0);
        assertTrue(second.isEmpty());
        assertEquals(0.0, second.total(), 0.0);
        assertEquals(0.0, buffer.pending(CHUNK), 0.0);
    }

    @Test
    void pendingReportsTheBufferedTotalOfAChunk() {
        EmissionBuffer buffer = buffer(10);
        buffer.add(CHUNK, 1L, 4.0, "a");
        buffer.add(CHUNK, 2L, 1.0, "a");

        assertEquals(5.0, buffer.pending(CHUNK), 0.0);
        assertEquals(0.0, buffer.pending(OTHER_CHUNK), 0.0);
    }

    @Test
    void requeueMergesBackWithoutLosingOrDoublingAmounts() {
        EmissionBuffer buffer = buffer(10);
        buffer.add(CHUNK, 1L, 4.0, "a");
        buffer.add(CHUNK, 2L, 1.0, "a");
        DrainedEmissions drained = buffer.drain();
        buffer.add(CHUNK, 2L, 2.0, "a"); // arrived while the drained interval was running

        buffer.requeue(drained);
        DrainedEmissions next = buffer.drain();

        assertEquals(7.0, next.total(), 0.0);
        assertEquals(1L, next.dominantPos(CHUNK));
        assertEquals(4.0, next.dominantRate(CHUNK), 0.0);
        assertEquals(2, next.positionCount(CHUNK));
        assertTrue(buffer.drain().isEmpty());
    }

    @Test
    void repeatedDrainAndRequeueKeepsAtMostCapPositionsPerChunk() {
        int cap = 5;
        EmissionBuffer buffer = buffer(cap);
        double expected = 0;
        for (int round = 0; round < 50; round++) {
            DrainedEmissions dropped = buffer.drain();
            for (int i = 0; i < 20; i++) {
                buffer.add(CHUNK, round * 100L + i, 1.0, "a"); // new positions every round
                expected += 1.0;
            }
            buffer.requeue(dropped);
            assertTrue(buffer.trackedEntries(CHUNK) <= cap + 1, "entries " + buffer.trackedEntries(CHUNK));
        }
        DrainedEmissions drained = buffer.drain();
        assertTrue(drained.positionCount(CHUNK) <= cap);
        assertEquals(expected, drained.chunkTotal(CHUNK), 1e-9);
    }
}
