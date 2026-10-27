package io.github.pollutioncraft.mod.emitter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class ChunkTrackTest {
    private static List<Long> shuffled(long[] values, long seed) {
        List<Long> list = Arrays.stream(values).boxed().collect(Collectors.toList());
        Collections.shuffle(list, new Random(seed));
        return list;
    }

    private static long[] lowest(long[] values, int n) {
        return Arrays.stream(values).sorted().limit(n).toArray();
    }

    private static final long[] SAMPLE = {5, -3, 40, 0, 17, -90, 8, 8_000_000_000L, -8_000_000_000L, 22, 1, 2};

    @Test
    void offersKeepTheCapLowestWhateverTheOrder() {
        long[] expected = lowest(SAMPLE, 5);
        for (long seed = 0; seed < 20; seed++) {
            ChunkTrack track = new ChunkTrack(5);
            shuffled(SAMPLE, seed).forEach(track::offer);

            assertArrayEquals(expected, track.toArray(), "seed " + seed);
        }
    }

    @Test
    void theOrderIsSignedSoNegativePackedPositionsRankFirst() {
        ChunkTrack track = new ChunkTrack(2);
        track.offer(10);
        track.offer(-1);
        track.offer(5);

        assertArrayEquals(new long[] {-1, 5}, track.toArray());
    }

    @Test
    void outcomesNameWhatHappened() {
        ChunkTrack track = new ChunkTrack(2);

        assertEquals(ChunkTrack.Outcome.ADDED, track.offer(20));
        assertEquals(ChunkTrack.Outcome.ADDED, track.offer(10));
        assertFalse(track.overCap);
        assertEquals(ChunkTrack.Outcome.PRESENT, track.offer(10));
        assertEquals(ChunkTrack.Outcome.REJECTED, track.offer(30));
        assertTrue(track.overCap);
        assertEquals(ChunkTrack.Outcome.ADDED_EVICTING, track.offer(15));
        assertArrayEquals(new long[] {10, 15}, track.toArray());
        assertTrue(track.contains(15));
        assertFalse(track.contains(20));
    }

    @Test
    void aCapOfOneKeepsOnlyTheLowest() {
        ChunkTrack track = new ChunkTrack(1);
        LongStream.of(9, 3, 7).forEach(track::offer);

        assertArrayEquals(new long[] {3}, track.toArray());
    }

    @Test
    void theScanHeapKeepsTheSameLowestWhateverTheOrderAndCountsWhatItSaw() {
        long[] expected = lowest(SAMPLE, 5);
        for (long seed = 0; seed < 20; seed++) {
            ScanHeap heap = new ScanHeap(5);
            shuffled(SAMPLE, seed).forEach(heap::offer);

            assertArrayEquals(expected, heap.sorted(), "seed " + seed);
            assertEquals(SAMPLE.length, heap.seen);
            assertEquals(5, heap.keptCount());
        }
    }

    @Test
    void theScanHeapAndTheTrackAgreeOnALargeRandomSet() {
        Random random = new Random(42);
        long[] values = random.longs(20_000).distinct().toArray();
        ScanHeap heap = new ScanHeap(2048);
        ChunkTrack track = new ChunkTrack(2048);
        for (long value : values) {
            heap.offer(value);
            track.offer(value);
        }

        assertArrayEquals(lowest(values, 2048), heap.sorted());
        assertArrayEquals(lowest(values, 2048), track.toArray());
    }

    @Test
    void aRejectedOfferToTheHeapIsNotCountedAsKept() {
        ScanHeap heap = new ScanHeap(2);
        assertTrue(heap.offer(5));
        assertTrue(heap.offer(6));
        assertFalse(heap.offer(7));
        assertTrue(heap.offer(1));

        assertEquals(3, heap.kept);
        assertEquals(4, heap.seen);
    }
}
