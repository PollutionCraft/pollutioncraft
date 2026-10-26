package io.github.pollutioncraft.mod.command;

import io.github.pollutioncraft.core.ChunkIntervalStats;
import io.github.pollutioncraft.core.ChunkPollution;
import io.github.pollutioncraft.core.WorldRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Reply text of the debug commands, free of Minecraft types so it is unit-testable. */
public final class PollutionText {
    private static final String GATE_NOTE = "(ran without the player-online gate)";

    private PollutionText() {}

    public static String pausedWorld() {
        return "paused: unreadable world record";
    }

    public static String pausedDimension(String dimensionId) {
        return "paused: unreadable pollution field in " + dimensionId;
    }

    public static String withGateNote(String reply) {
        return reply + " " + GATE_NOTE;
    }

    /** A number without exponent or trailing zeros; used for every PU value in replies. */
    public static String number(double value) {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.signum() == 0 ? "0" : rounded.toPlainString();
    }

    /** {@code chunk} is null for a chunk with no stored entry, which reads as zeros; {@code pending} is the buffered, not yet applied emission. */
    public static List<String> get(int chunkX, int chunkZ, ChunkPollution chunk, double pending) {
        ChunkPollution c = chunk == null ? new ChunkPollution() : chunk;
        return List.of(
                "chunk " + chunkX + ", " + chunkZ,
                "absorbable: " + number(c.absorbable),
                "persistent: " + number(c.persistent),
                "dominantSource: " + dominantSource(c),
                "emittedLastInterval: " + number(c.emittedLastInterval),
                "pending: " + number(pending));
    }

    /** Last-interval flows of the chunk; per-neighbour outflow is the equal split of the total. */
    public static List<String> rates(int chunkX, int chunkZ, ChunkPollution chunk) {
        ChunkPollution c = chunk == null ? new ChunkPollution() : chunk;
        ChunkIntervalStats stats = c.lastIntervalStats;
        String each = number(stats.spreadOut() / 4);
        return List.of(
                "chunk " + chunkX + ", " + chunkZ + " last interval",
                "generated: " + number(c.emittedLastInterval),
                "spreadIn: " + number(stats.spreadIn()),
                "spreadOut: " + number(stats.spreadOut()) + " (per neighbour: -x " + each + ", +x " + each
                        + ", -z " + each + ", +z " + each + ")",
                "absorbed: " + number(stats.absorbed()),
                "decayed: " + number(stats.decayed()));
    }

    public static List<String> pressure(WorldRecord record) {
        return List.of(
                "activeTicks: " + record.activeTicks(),
                "grossEmitted: " + number(record.grossEmitted()),
                "pressure: " + number(record.pressure()));
    }

    private static String dominantSource(ChunkPollution c) {
        if (c.dominantSourceRate <= 0) {
            return "none";
        }
        // Same layout as BlockPos.asLong: x 26 bits at offset 38, z 26 bits at 12, y 12 bits at 0.
        long pos = c.dominantSourcePos;
        long x = pos >> 38;
        long z = pos << 26 >> 38;
        long y = pos << 52 >> 52;
        return x + " " + y + " " + z + " (" + number(c.dominantSourceRate) + " PU/interval)";
    }
}
