package io.github.pollutioncraft.mod.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.pollutioncraft.core.ChunkIntervalStats;
import io.github.pollutioncraft.core.ChunkPollution;
import io.github.pollutioncraft.mod.config.ServerConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class OverlayTextTest {
    private final ServerConfig config = new ServerConfig(message -> {});

    private static ChunkPollution chunk() {
        ChunkPollution c = new ChunkPollution();
        c.absorbable = 12.5;
        c.persistent = 3;
        c.emittedLastInterval = 2;
        c.dominantSourcePos = (5L << 38) | (7L << 12) | 64; // x 5, z 0, y 64 in BlockPos.asLong layout is not needed in full
        c.dominantSourceRate = 2;
        c.lastIntervalStats = new ChunkIntervalStats(1.5, 4.0, 0.25, 0.125);
        return c;
    }

    @Test
    void aChunkWithValuesShowsEveryPlannedLine() {
        List<String> lines = OverlayText.chunkLines(3, -4, chunk(), 1.0, 0.0, config);

        assertTrue(lines.contains("absorbable: 12.5"), lines::toString);
        assertTrue(lines.contains("persistent: 3"), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("dominantSource: ") && l.contains("2 PU/interval")), lines::toString);
        assertTrue(lines.contains("emittedLastInterval: 2"), lines::toString);
        assertTrue(lines.contains("generated: 2"), lines::toString);
        assertTrue(lines.contains("spreadIn: 1.5"), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("spreadOut: 4")), lines::toString);
        assertTrue(lines.contains("absorbed: 0.25"), lines::toString);
        assertTrue(lines.contains("decayed: 0.125"), lines::toString);
        assertTrue(lines.stream().anyMatch(l -> l.contains("3, -4")), "the chunk is named: " + lines);
    }

    @Test
    void anAbsentChunkReadsAsZeros() {
        List<String> lines = OverlayText.chunkLines(0, 0, null, 0.0, 0.0, config);

        assertTrue(lines.contains("absorbable: 0"), lines::toString);
        assertTrue(lines.contains("persistent: 0"), lines::toString);
        assertTrue(lines.contains("dominantSource: none"), lines::toString);
        assertTrue(lines.contains("generated: 0"), lines::toString);
    }

    @Test
    void pressureAndAggressiveAreLabelledUntilTask016() {
        List<String> lines = OverlayText.chunkLines(0, 0, null, 0.0, 0.0, config);

        assertTrue(lines.contains("pressure: 0 (until task 016)"), lines::toString);
        assertTrue(lines.contains("aggressive: n/a (until task 016)"), lines::toString);
    }

    @Test
    void exactlyTheActiveOverrideIsMarked() {
        assertTrue(config.overrides().set("spreadFraction", 0.1).isEmpty());

        List<String> marked = OverlayText.chunkLines(0, 0, null, 0.0, 0.0, config).stream()
                .filter(line -> line.contains("(overridden)"))
                .toList();

        assertEquals(1, marked.size(), marked::toString);
        assertTrue(marked.get(0).startsWith("spreadFraction: 0.1"), marked.get(0));
    }

    @Test
    void noOverrideMeansNoMarkedLine() {
        assertTrue(OverlayText.chunkLines(0, 0, null, 0.0, 0.0, config).stream().noneMatch(l -> l.contains("(overridden)")));
    }

    @Test
    void everyLineFitsThePayloadBounds() {
        for (String line : OverlayText.chunkLines(-30_000_000, 30_000_000, chunk(), 1e12, 0.0, config)) {
            assertTrue(line.length() <= OverlayText.MAX_LINE_LENGTH, line);
        }
        assertTrue(OverlayText.chunkLines(0, 0, chunk(), 0.0, 0.0, config).size() <= OverlayText.MAX_LINES);
    }

    @Test
    void theNoDataTextIsFixed() {
        assertEquals("no data", OverlayText.NO_DATA);
        assertEquals("Pollution", OverlayText.TITLE);
    }
}
