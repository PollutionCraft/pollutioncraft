package io.github.pollutioncraft.gametest;

import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.core.DiffusionStep;
import io.github.pollutioncraft.core.DrainedEmissions;
import io.github.pollutioncraft.core.PollutionField;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.config.ServerConfig;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The runtime-override layer is wired into what the simulation reads, in the real mod environment. */
@GameTestHolder("pollutioncraft")
@PrefixGameTestTemplate(false)
public final class ConfigOverrideGameTests {
    private static final long ORIGIN = ChunkKey.pack(0, 0);
    private static final long EAST = ChunkKey.pack(1, 0);

    private ConfigOverrideGameTests() {}

    /** Pollution that reached the east neighbour after one interval of the real diffusion step. */
    private static double eastNeighbourAfterOneInterval(ServerConfig config) {
        PollutionField field = new PollutionField();
        field.getOrCreate(ORIGIN).absorbable = 1000;
        DiffusionStep.run(field, DrainedEmissions.NONE, key -> 0, config.diffusionConfig());
        return field.get(EAST).local();
    }

    @GameTest(template = "empty")
    public static void spreadFractionOverrideChangesTheNextIntervalsSpread(GameTestHelper helper) {
        ServerConfig config = PollutionCraftMod.config();
        config.overrides().clearAll();
        try {
            double before = eastNeighbourAfterOneInterval(config);
            helper.assertTrue(before == 1000 * config.spreadFraction(), "baseline spread was " + before);

            helper.assertTrue(config.overrides().set("spreadFraction", 0.1).isEmpty(), "override refused");
            double during = eastNeighbourAfterOneInterval(config);
            helper.assertTrue(during == 100.0, "overridden spread was " + during);

            config.overrides().clearAll();
            helper.assertTrue(eastNeighbourAfterOneInterval(config) == before, "clearing did not restore the spread");
            helper.succeed();
        } finally {
            config.overrides().clearAll();
        }
    }
}
