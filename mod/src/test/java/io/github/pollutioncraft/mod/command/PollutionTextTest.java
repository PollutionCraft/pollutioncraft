package io.github.pollutioncraft.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pollutioncraft.core.ChunkIntervalStats;
import io.github.pollutioncraft.core.ChunkPollution;
import io.github.pollutioncraft.core.WorldRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class PollutionTextTest {
    @Test
    void pausedLines() {
        assertEquals("paused: unreadable world record", PollutionText.pausedWorld());
        assertEquals(
                "paused: unreadable pollution field in minecraft:the_nether",
                PollutionText.pausedDimension("minecraft:the_nether"));
    }

    @Test
    void getWithoutDominantSourceShowsNone() {
        ChunkPollution chunk = new ChunkPollution();
        chunk.absorbable = 12.5;
        chunk.persistent = 1;

        List<String> lines = PollutionText.get(3, -4, chunk, 2.5);

        String all = String.join("\n", lines);
        assertTrue(all.contains("chunk 3, -4"), all);
        assertTrue(all.contains("absorbable: 12.5"), all);
        assertTrue(all.contains("persistent: 1"), all);
        assertTrue(all.contains("dominantSource: none"), all);
        assertTrue(all.contains("emittedLastInterval: 0"), all);
        assertEquals(lines.indexOf("emittedLastInterval: 0") + 1, lines.indexOf("pending: 2.5"), all);
    }

    @Test
    void pendingZeroIsShownAsZero() {
        assertTrue(PollutionText.get(0, 0, null, 0).contains("pending: 0"));
    }

    @Test
    void absentChunkReadsAsZeros() {
        String all = String.join("\n", PollutionText.get(0, 0, null, 0));

        assertTrue(all.contains("absorbable: 0"), all);
        assertTrue(all.contains("persistent: 0"), all);
        assertTrue(all.contains("dominantSource: none"), all);
    }

    @Test
    void getShowsDominantSourceBlockPositionAndRate() {
        ChunkPollution chunk = new ChunkPollution();
        // Same packing as BlockPos.asLong: x 26 bits at 38, z 26 bits at 12, y 12 bits at 0.
        chunk.dominantSourcePos = ((100L & 0x3FFFFFF) << 38) | ((-20L & 0x3FFFFFF) << 12) | (64L & 0xFFF);
        chunk.dominantSourceRate = 2.5;

        String all = String.join("\n", PollutionText.get(6, -2, chunk, 0));

        assertTrue(all.contains("dominantSource: 100 64 -20 (2.5 PU/interval)"), all);
    }

    @Test
    void ratesShowTotalsAndEvenSplitPerNeighbour() {
        ChunkPollution chunk = new ChunkPollution();
        chunk.emittedLastInterval = 7;
        chunk.lastIntervalStats = new ChunkIntervalStats(1.5, 8, 2, 0.25);

        String all = String.join("\n", PollutionText.rates(0, 0, chunk));

        assertTrue(all.contains("generated: 7"), all);
        assertTrue(all.contains("spreadIn: 1.5"), all);
        assertTrue(all.contains("spreadOut: 8"), all);
        assertTrue(all.contains("-x 2"), all);
        assertTrue(all.contains("+z 2"), all);
        assertTrue(all.contains("absorbed: 2"), all);
        assertTrue(all.contains("decayed: 0.25"), all);
    }

    @Test
    void pressureLinesShowRecordValues() {
        WorldRecord record = new WorldRecord(1200, 33.5);

        String all = String.join("\n", PollutionText.pressure(record));

        assertTrue(all.contains("activeTicks: 1200"), all);
        assertTrue(all.contains("grossEmitted: 33.5"), all);
        assertTrue(all.contains("pressure: 0"), all);
    }

    @Test
    void gateBypassNoteIsAppended() {
        assertTrue(PollutionText.withGateNote("Set 9 chunks").endsWith("(ran without the player-online gate)"));
    }
}
