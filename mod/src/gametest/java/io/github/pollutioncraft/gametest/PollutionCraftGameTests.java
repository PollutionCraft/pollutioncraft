package io.github.pollutioncraft.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Harness proof: the headless GameTest run discovers, runs and judges a test of ours. */
@GameTestHolder("pollutioncraft")
@PrefixGameTestTemplate(false)
public final class PollutionCraftGameTests {
    private static final String EMPTY_TEMPLATE = "empty";

    private PollutionCraftGameTests() {}

    @GameTest(template = EMPTY_TEMPLATE)
    public static void placedBlockIsPresent(GameTestHelper helper) {
        BlockPos pos = new BlockPos(0, 1, 0);
        helper.setBlock(pos, Blocks.STONE);
        helper.assertBlockPresent(Blocks.STONE, pos);
        helper.succeed();
    }
}
