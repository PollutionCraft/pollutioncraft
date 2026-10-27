package io.github.pollutioncraft.mod.overlay;

import io.github.pollutioncraft.core.ChunkPollution;
import io.github.pollutioncraft.mod.command.PollutionText;
import io.github.pollutioncraft.mod.command.RatesText;
import io.github.pollutioncraft.mod.config.ConfigRegistry;
import io.github.pollutioncraft.mod.config.ServerConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * The lines of the F3 {@code Pollution} section, formatted on the server (architecture 3.5 dev overlay). This
 * only chooses which lines and in what order; every number is formatted by the existing {@link PollutionText}
 * and {@link RatesText}, so there is one formatter. Free of Minecraft types, so it is unit-testable.
 */
public final class OverlayText {
    public static final String TITLE = "Pollution";
    public static final String NO_DATA = "no data";
    /** The payload bounds; {@link #chunkLines} never exceeds them. */
    public static final int MAX_LINES = 32;
    public static final int MAX_LINE_LENGTH = 256;

    private OverlayText() {}

    /**
     * @param chunk the stored state, or null for a chunk with no entry (reads as zeros)
     * @param pending buffered emission not yet applied
     * @param pressure the world pressure as stored (0 until task 016)
     */
    public static List<String> chunkLines(
            int chunkX, int chunkZ, ChunkPollution chunk, double pending, double pressure, ServerConfig config) {
        List<String> lines = new ArrayList<>(PollutionText.get(chunkX, chunkZ, chunk, pending));
        List<String> rates = PollutionText.rates(chunkX, chunkZ, chunk);
        lines.addAll(rates.subList(1, rates.size())); // the first rates line repeats the chunk header
        lines.add("pressure: " + PollutionText.number(pressure) + " (until task 016)");
        lines.add("aggressive: n/a (until task 016)"); // PollutionApi.isAggressive throws until task 016
        ConfigRegistry.KEYS.stream()
                .filter(key -> config.overrides().get(key.name()).isPresent())
                .forEach(key -> lines.add(RatesText.line(config, key)));
        return bounded(lines);
    }

    private static List<String> bounded(List<String> lines) {
        return lines.stream()
                .limit(MAX_LINES)
                .map(line -> line.length() <= MAX_LINE_LENGTH ? line : line.substring(0, MAX_LINE_LENGTH))
                .toList();
    }
}
