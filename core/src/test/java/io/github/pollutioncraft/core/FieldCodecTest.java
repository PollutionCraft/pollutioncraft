package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FieldCodecTest {
    private static PollutionField randomField(int chunks, long seed) {
        Random rnd = new Random(seed);
        PollutionField field = new PollutionField();
        while (field.size() < chunks) {
            ChunkPollution c = field.getOrCreate(ChunkKey.pack(rnd.nextInt(200) - 100, rnd.nextInt(200) - 100));
            c.absorbable = rnd.nextDouble() * 1000;
            c.persistent = rnd.nextDouble() * 10;
            c.emittedLastInterval = rnd.nextDouble();
            c.dominantSourcePos = rnd.nextLong();
            c.dominantSourceRate = rnd.nextDouble();
        }
        return field;
    }

    @Test
    void roundTripIsLossless() {
        PollutionField original = randomField(300, 1);
        List<String> log = new ArrayList<>();

        PollutionField restored = FieldCodec.decode(FieldCodec.encode(original), log::add);

        assertTrue(log.isEmpty(), log.toString());
        assertEquals(original.size(), restored.size());
        for (long key : original.sortedKeys()) {
            ChunkPollution a = original.get(key);
            ChunkPollution b = restored.get(key);
            assertEquals(Double.doubleToLongBits(a.absorbable), Double.doubleToLongBits(b.absorbable));
            assertEquals(Double.doubleToLongBits(a.persistent), Double.doubleToLongBits(b.persistent));
            assertEquals(Double.doubleToLongBits(a.emittedLastInterval), Double.doubleToLongBits(b.emittedLastInterval));
            assertEquals(a.dominantSourcePos, b.dominantSourcePos);
            assertEquals(Double.doubleToLongBits(a.dominantSourceRate), Double.doubleToLongBits(b.dominantSourceRate));
        }
    }

    @Test
    void transientStatsAreNotPersisted() {
        PollutionField field = randomField(3, 2);
        long key = field.sortedKeys()[0];
        field.get(key).lastIntervalStats = new ChunkIntervalStats(1, 2, 3, 4);

        PollutionField restored = FieldCodec.decode(FieldCodec.encode(field), s -> {});

        assertEquals(ChunkIntervalStats.ZERO, restored.get(key).lastIntervalStats);
    }

    @Test
    void corruptEntriesAreSkippedAndLoggedTheRestSurvive() {
        PollutionField field = randomField(5, 3);
        long[] keys = field.sortedKeys();
        EncodedField enc = FieldCodec.encode(field);
        enc.absorbable()[1] = Double.doubleToRawLongBits(Double.NaN);
        enc.persistent()[2] = Double.doubleToRawLongBits(-5.0);
        enc.emitted()[3] = Double.doubleToRawLongBits(Double.POSITIVE_INFINITY);
        List<String> log = new ArrayList<>();

        PollutionField restored = FieldCodec.decode(enc, log::add);

        assertEquals(2, restored.size());
        assertTrue(restored.get(keys[0]) != null && restored.get(keys[4]) != null);
        assertNull(restored.get(keys[1]));
        assertEquals(3, log.size(), log.toString());
    }

    @Test
    void mismatchedColumnLengthsKeepOnlyTheCompleteEntries() {
        EncodedField good = FieldCodec.encode(randomField(4, 4));
        EncodedField truncated = new EncodedField(
                good.dataVersion(),
                good.keys(),
                java.util.Arrays.copyOf(good.absorbable(), 2),
                good.persistent(),
                good.emitted(),
                good.dominantPos(),
                good.dominantRate());
        List<String> log = new ArrayList<>();

        PollutionField restored = FieldCodec.decode(truncated, log::add);

        assertEquals(2, restored.size());
        assertEquals(1, log.size(), log.toString());
    }

    @Test
    void duplicateKeysKeepTheFirstEntry() {
        PollutionField field = randomField(2, 5);
        EncodedField enc = FieldCodec.encode(field);
        enc.keys()[1] = enc.keys()[0];
        List<String> log = new ArrayList<>();

        PollutionField restored = FieldCodec.decode(enc, log::add);

        assertEquals(1, restored.size());
        assertEquals(1, log.size());
    }

    @Test
    void unknownFutureDataVersionIsSkippedWithALog() {
        EncodedField enc = FieldCodec.encode(randomField(4, 6));
        EncodedField future = new EncodedField(
                FieldCodec.DATA_VERSION + 1, enc.keys(), enc.absorbable(), enc.persistent(), enc.emitted(), enc.dominantPos(), enc.dominantRate());
        List<String> log = new ArrayList<>();

        PollutionField restored = FieldCodec.decode(future, log::add);

        assertEquals(0, restored.size());
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains(String.valueOf(FieldCodec.DATA_VERSION + 1)), log.get(0));
    }

    @Test
    void twoThousandFiveHundredChunksStayUnder200KB() {
        EncodedField enc = FieldCodec.encode(randomField(2500, 7));

        assertTrue(enc.payloadBytes() < 200_000, "payload bytes: " + enc.payloadBytes());
    }
}
