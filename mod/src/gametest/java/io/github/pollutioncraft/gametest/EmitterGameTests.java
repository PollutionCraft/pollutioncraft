package io.github.pollutioncraft.gametest;

import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.core.ChunkPollution;
import io.github.pollutioncraft.mod.DimensionPollution;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.PollutionManager;
import io.github.pollutioncraft.mod.emitter.EmitterEngine;
import io.github.pollutioncraft.mod.emitter.EmitterRules;
import io.github.pollutioncraft.mod.emitter.MappingEntry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The block emitter engine (task 011) against a live server. The shipped mapping is the furnace; the lamp
 * mapping under {@code gametest/resources} is test-only. Tests that need several real server ticks run in
 * their own batch (batches run one after another), so a lit block waiting for a tick can never emit into a
 * neighbouring test's exact {@code grossEmitted} assertions.
 */
@GameTestHolder("pollutioncraft")
@PrefixGameTestTemplate(false)
public final class EmitterGameTests {
    private static final String EMPTY_TEMPLATE = "empty";
    private static final double LAMP_RATE = 2.0;
    private static final EmitterEngine ENGINE = EmitterEngine.global();

    private EmitterGameTests() {}

    private static BlockState litLamp() {
        return Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true);
    }

    private static long chunkOf(BlockPos pos) {
        return ChunkKey.pack(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static void interval(MinecraftServer server) {
        for (int i = 0; i < PollutionCraftMod.config().spreadIntervalTicks(); i++) {
            PollutionManager.tick(server, 1);
        }
    }

    private static double gross(MinecraftServer server) {
        return PollutionManager.world(server).record().grossEmitted();
    }

    /**
     * Fresh state around the chunks: no leftover emissions, interval or field, and a multiplier of 1. The flush
     * runs the engine, so lit blocks in <em>any</em> of the chunks emit once before the field is cleared.
     */
    private static DimensionPollution reset(ServerLevel level, long... chunks) {
        PollutionCraftMod.config().overrides().clearAll();
        DimensionPollution dimension = PollutionManager.dimension(level);
        dimension.scheduler().abandon();
        interval(level.getServer()); // flush what earlier tests left pending
        for (long chunk : chunks) {
            for (int[] d : new int[][] {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                dimension.field().remove(ChunkKey.pack(ChunkKey.x(chunk) + d[0], ChunkKey.z(chunk) + d[1]));
            }
        }
        return dimension;
    }

    /** The test arena's chunk must be block-ticking for the lamp tests to mean anything. */
    private static void requireTicking(GameTestHelper helper, ServerLevel level, BlockPos pos) {
        helper.assertTrue(level.shouldTickBlocksAt(pos), "precondition: the test chunk must be block-ticking");
    }

    // ---- registry resolution (plan test 3) --------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE)
    public static void resolutionSkipsUnknownThingsAndExpandsTags(GameTestHelper helper) {
        List<String> log = new ArrayList<>();
        List<EmitterRules.NamedEntry> entries = List.of(
                named("a:unknown_block", block("minecraft:no_such_block", 1, "lit", "true")),
                named("b:unknown_property", block("minecraft:redstone_lamp", 1, "no_such_property", "true")),
                named("c:unknown_value", block("minecraft:redstone_lamp", 1, "lit", "maybe")),
                named("d:unknown_tag", tag("minecraft:no_such_tag", 1, "lit", "true")),
                named("e:campfires", tag("minecraft:campfires", 3, "lit", "true")));

        EmitterRules rules = EmitterRules.resolve(entries, BuiltInRegistries.BLOCK, log::add);

        helper.assertTrue(log.size() == 4, "one log line per bad entry, got " + log);
        for (String id : List.of("a:unknown_block", "b:unknown_property", "c:unknown_value", "d:unknown_tag")) {
            helper.assertTrue(log.stream().filter(line -> line.contains(id)).count() == 1, "one line names " + id + ": " + log);
        }
        helper.assertTrue(!rules.mapsBlock(Blocks.REDSTONE_LAMP), "a skipped entry maps nothing");
        helper.assertTrue(rules.mapsBlock(Blocks.CAMPFIRE) && rules.mapsBlock(Blocks.SOUL_CAMPFIRE), "the tag expanded to its blocks");
        helper.assertTrue(rules.activeRate(Blocks.CAMPFIRE.defaultBlockState()) == 3.0, "lit campfire is active at the rate");
        helper.assertTrue(
                rules.activeRate(Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false)) < 0,
                "an unlit campfire is not active");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void lowestResourceIdWinsAConflictWithOneWarning(GameTestHelper helper) {
        List<String> log = new ArrayList<>();
        List<EmitterRules.NamedEntry> entries = List.of(
                named("zzz:second", block("minecraft:redstone_lamp", 9, "lit", "true")),
                named("aaa:first", block("minecraft:redstone_lamp", 4, "lit", "true")));

        EmitterRules rules = EmitterRules.resolve(entries, BuiltInRegistries.BLOCK, log::add);

        helper.assertTrue(rules.activeRate(litLamp()) == 4.0, "the lowest id wins, got " + rules.activeRate(litLamp()));
        helper.assertTrue(log.size() == 1 && log.get(0).contains("aaa:first") && log.get(0).contains("zzz:second"), "one warning: " + log);
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void theShippedFurnaceMappingIsActiveAfterANormalStart(GameTestHelper helper) {
        helper.assertTrue(EmitterEngine.currentRules().mapsBlock(Blocks.FURNACE), "furnace is mapped without any rescan wait");
        helper.assertTrue(
                EmitterEngine.currentRules().activeRate(Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.FurnaceBlock.LIT, true)) == 1.0,
                "a lit furnace is worth 1 PU per interval");
        helper.assertTrue(
                EmitterEngine.currentRules().activeRate(Blocks.FURNACE.defaultBlockState()) < 0, "an unlit furnace is not active");
        helper.succeed();
    }

    private static EmitterRules.NamedEntry named(String id, MappingEntry entry) {
        return new EmitterRules.NamedEntry(id, entry);
    }

    private static MappingEntry block(String id, double rate, String property, String value) {
        return new MappingEntry(MappingEntry.TargetKind.BLOCK, id, rate, property, value);
    }

    private static MappingEntry tag(String id, double rate, String property, String value) {
        return new MappingEntry(MappingEntry.TargetKind.TAG, id, rate, property, value);
    }

    // ---- engine with the test lamp mapping (plan test 4) ------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE)
    public static void aLitLampEmitsItsRateAndIsBufferedBeforeTheDrain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos lamp = helper.absolutePos(new BlockPos(1, 2, 1));
        requireTicking(helper, level, lamp);
        DimensionPollution dimension = reset(level, chunkOf(lamp));
        double grossBefore = gross(server);
        try {
            helper.setBlock(new BlockPos(1, 2, 1), litLamp());
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.REDSTONE_BLOCK);

            ENGINE.tick(level, true);
            helper.assertTrue(dimension.pendingEmission(chunkOf(lamp)) == LAMP_RATE, "pending before the drain: " + dimension.pendingEmission(chunkOf(lamp)));
            PollutionManager.stepNow(level, 1);

            helper.assertTrue(PollutionManager.api().getLocal(level, lamp.getX() >> 4, lamp.getZ() >> 4) == LAMP_RATE, "the chunk gained the rate");
            helper.assertTrue(gross(server) == grossBefore + LAMP_RATE, "gross rose by the rate");
        } finally {
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.AIR);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void theManagerTickEmitsOncePerIntervalAndAttributesTheLamp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos lamp = helper.absolutePos(new BlockPos(1, 2, 1));
        requireTicking(helper, level, lamp);
        DimensionPollution dimension = reset(level, chunkOf(lamp));
        double grossBefore = gross(server);
        try {
            helper.setBlock(new BlockPos(1, 2, 1), litLamp());
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.REDSTONE_BLOCK);

            interval(server);

            helper.assertTrue(PollutionManager.api().getLocal(level, lamp.getX() >> 4, lamp.getZ() >> 4) == LAMP_RATE, "one emission per interval");
            helper.assertTrue(gross(server) == grossBefore + LAMP_RATE, "gross counted once");
            ChunkPollution chunk = dimension.field().get(chunkOf(lamp));
            helper.assertTrue(chunk.dominantSourcePos == lamp.asLong() && chunk.emittedLastInterval == LAMP_RATE, "dominant source is the lamp");
        } finally {
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.AIR);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void anUnlitLampEmitsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos lamp = helper.absolutePos(new BlockPos(1, 2, 1));
        requireTicking(helper, level, lamp);
        reset(level, chunkOf(lamp));
        double grossBefore = gross(server);
        try {
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.REDSTONE_LAMP.defaultBlockState());
            helper.assertTrue(ENGINE.isTracked(level, lamp), "a mapped block is tracked whether or not it is active");

            interval(server);

            helper.assertTrue(PollutionManager.api().getLocal(level, lamp.getX() >> 4, lamp.getZ() >> 4) == 0.0, "no pollution");
            helper.assertTrue(gross(server) == grossBefore, "no gross");
        } finally {
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void removingTheLampStopsEmissionFromTheNextInterval(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos lamp = helper.absolutePos(new BlockPos(1, 2, 1));
        requireTicking(helper, level, lamp);
        DimensionPollution dimension = reset(level, chunkOf(lamp));
        double grossBefore = gross(server);
        helper.setBlock(new BlockPos(1, 2, 1), litLamp());
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.REDSTONE_BLOCK);
        interval(server);
        helper.assertTrue(gross(server) == grossBefore + LAMP_RATE, "emitted while present");

        helper.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.AIR);
        interval(server);

        helper.assertTrue(gross(server) == grossBefore + LAMP_RATE, "nothing more after removal");
        helper.assertTrue(dimension.field().get(chunkOf(lamp)).emittedLastInterval == 0.0, "emittedLastInterval cleared");
        helper.assertTrue(!ENGINE.isTracked(level, lamp), "the removed block is no longer tracked");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void aLampPlacedAfterTheChunkLoadedIsTrackedAtOnceByEveryPlacementPath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos viaHelper = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos viaCommand = helper.absolutePos(new BlockPos(3, 2, 1));
        BlockPos viaFill = helper.absolutePos(new BlockPos(5, 2, 1));
        requireTicking(helper, level, viaHelper);
        reset(level, chunkOf(viaHelper));
        try {
            helper.setBlock(new BlockPos(1, 2, 1), litLamp());
            CommandSourceStack source = server.createCommandSourceStack().withLevel(level);
            server.getCommands().performPrefixedCommand(
                    source, "setblock " + viaCommand.getX() + " " + viaCommand.getY() + " " + viaCommand.getZ() + " minecraft:redstone_lamp");
            server.getCommands().performPrefixedCommand(
                    source,
                    "fill " + viaFill.getX() + " " + viaFill.getY() + " " + viaFill.getZ() + " " + viaFill.getX() + " "
                            + viaFill.getY() + " " + viaFill.getZ() + " minecraft:redstone_lamp");

            helper.assertTrue(ENGINE.isTracked(level, viaHelper), "helper.setBlock (flag 3)");
            helper.assertTrue(ENGINE.isTracked(level, viaCommand), "/setblock (flag 2 then blockUpdated)");
            helper.assertTrue(ENGINE.isTracked(level, viaFill), "/fill");
        } finally {
            for (BlockPos pos : List.of(new BlockPos(1, 2, 1), new BlockPos(3, 2, 1), new BlockPos(5, 2, 1))) {
                helper.setBlock(pos, Blocks.AIR);
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void aPlacementWithoutAnUpdateIsFoundByTheRescanAfterTheRulesChange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lamp = helper.absolutePos(new BlockPos(1, 2, 1));
        requireTicking(helper, level, lamp);
        reset(level, chunkOf(lamp));
        EmitterRules[] current = {EmitterRules.EMPTY};
        EmitterEngine engine = new EmitterEngine(() -> current[0], (l, k) -> true, () -> 1.0, PollutionManager.api());
        LevelChunk chunk = level.getChunkAt(lamp);
        try {
            engine.onChunkLoad(level, chunk);
            level.setBlock(lamp, litLamp(), 2); // flag 2 only: no neighbour update, so no event
            helper.assertTrue(!engine.isTracked(level, lamp), "nothing saw the placement");

            current[0] = EmitterEngine.currentRules(); // the rules change: every loaded chunk is queued
            for (int tick = 0; tick < 200 && !engine.isTracked(level, lamp); tick++) {
                engine.tick(level, false);
            }

            helper.assertTrue(engine.isTracked(level, lamp), "the rescan found the block");
        } finally {
            level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 2);
        }
        helper.succeed();
    }

    // ---- no mapping data (plan test 7) and the gate (plan test 8) ---------------------------------

    @GameTest(template = EMPTY_TEMPLATE)
    public static void anEngineWithNoRulesEmitsNothingForALitFurnace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos furnace = helper.absolutePos(new BlockPos(1, 2, 1));
        requireTicking(helper, level, furnace);
        DimensionPollution dimension = reset(level, chunkOf(furnace));
        BlockState lit = Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.FurnaceBlock.LIT, true);
        EmitterEngine empty = new EmitterEngine(() -> EmitterRules.EMPTY, (l, k) -> true, () -> 1.0, PollutionManager.api());
        EmitterEngine shipped = new EmitterEngine(EmitterEngine::currentRules, (l, k) -> true, () -> 1.0, PollutionManager.api());
        try {
            level.setBlock(furnace, lit, 2);
            empty.onChunkLoad(level, level.getChunkAt(furnace));
            empty.tick(level, true);
            helper.assertTrue(dimension.pendingEmission(chunkOf(furnace)) == 0.0, "no rules, no emission");
            helper.assertTrue(empty.trackedCount(level) == 0, "no rules, nothing tracked");

            shipped.onChunkLoad(level, level.getChunkAt(furnace));
            shipped.tick(level, true);
            helper.assertTrue(dimension.pendingEmission(chunkOf(furnace)) == 1.0, "control: the shipped rules do emit");
            dimension.scheduler().abandon();
            PollutionManager.stepNow(level, 1);
        } finally {
            level.setBlock(furnace, Blocks.AIR.defaultBlockState(), 2);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void noPlayerOnlineMeansNoEmission(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos lamp = helper.absolutePos(new BlockPos(1, 2, 1));
        requireTicking(helper, level, lamp);
        DimensionPollution dimension = reset(level, chunkOf(lamp));
        double grossBefore = gross(server);
        try {
            helper.setBlock(new BlockPos(1, 2, 1), litLamp());
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.REDSTONE_BLOCK);

            for (int i = 0; i < 3 * PollutionCraftMod.config().spreadIntervalTicks(); i++) {
                PollutionManager.tick(server, 0);
            }

            helper.assertTrue(dimension.pendingEmission(chunkOf(lamp)) == 0.0, "nothing buffered while the gate is closed");
            helper.assertTrue(gross(server) == grossBefore, "no gross");
        } finally {
            helper.setBlock(new BlockPos(1, 2, 1), Blocks.AIR);
            helper.setBlock(new BlockPos(2, 2, 1), Blocks.AIR);
        }
        helper.succeed();
    }

    // ---- loaded but not ticking, unload and reload, and the real burning furnace (plan tests 5, 6) ----

    private static final TicketType<ChunkPos> HOLD = TicketType.create("pollutioncraft_test_hold", java.util.Comparator.comparingLong(ChunkPos::toLong), 600);

    @GameTest(template = EMPTY_TEMPLATE, batch = "emitters_nonticking", timeoutTicks = 400)
    public static void aLoadedChunkThatDoesNotBlockTickEmitsNothingUntilItDoes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ChunkPos chunkPos = new ChunkPos(98_200, 98_000);
        BlockPos lamp = new BlockPos(chunkPos.getMinBlockX() + 3, 64, chunkPos.getMinBlockZ() + 3);
        BlockPos power = lamp.east();
        level.getChunkSource().addRegionTicket(HOLD, chunkPos, 0, chunkPos); // ticket level 33: loaded, not block-ticking
        level.getChunk(chunkPos.x, chunkPos.z); // blocks until generated and full: the test server ticks faster than worldgen
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z) != null, "chunk loaded"))
                .thenExecute(() -> {
                    helper.assertTrue(!level.shouldTickBlocksAt(lamp), "precondition: loaded but not block-ticking");
                    DimensionPollution dimension = reset(level, chunkOf(lamp));
                    double grossBefore = gross(server);
                    level.setBlock(lamp, litLamp(), 3);
                    level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                    helper.assertTrue(ENGINE.isTracked(level, lamp), "tracked even though the chunk does not tick");

                    interval(server);

                    helper.assertTrue(dimension.field().get(chunkOf(lamp)) == null, "a non-ticking chunk emits nothing");
                    helper.assertTrue(gross(server) == grossBefore, "no gross");
                    level.getChunkSource().addRegionTicket(HOLD, chunkPos, 1, chunkPos); // level 32: block-ticking
                })
                .thenWaitUntil(() -> helper.assertTrue(level.shouldTickBlocksAt(lamp), "chunk now ticks"))
                .thenExecute(() -> {
                    try {
                        double grossBefore = gross(server);
                        interval(server);
                        helper.assertTrue(PollutionManager.api().getLocal(level, chunkPos.x, chunkPos.z) == LAMP_RATE, "emits once it ticks");
                        helper.assertTrue(gross(server) == grossBefore + LAMP_RATE, "gross rose");
                    } finally {
                        level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(power, Blocks.AIR.defaultBlockState(), 3);
                        level.getChunkSource().removeRegionTicket(HOLD, chunkPos, 0, chunkPos);
                        level.getChunkSource().removeRegionTicket(HOLD, chunkPos, 1, chunkPos);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY_TEMPLATE, batch = "emitters_unload", timeoutTicks = 600)
    public static void unloadingForgetsTheChunkAndReloadingFindsTheLampAgain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ChunkPos chunkPos = new ChunkPos(98_300, 98_000);
        BlockPos lamp = new BlockPos(chunkPos.getMinBlockX() + 3, 64, chunkPos.getMinBlockZ() + 3);
        BlockPos power = lamp.east();
        level.setChunkForced(chunkPos.x, chunkPos.z, true);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.shouldTickBlocksAt(lamp), "chunk loaded and ticking"))
                .thenExecute(() -> {
                    level.setBlock(lamp, litLamp(), 3);
                    level.setBlock(power, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                    helper.assertTrue(ENGINE.isTracked(level, lamp), "tracked while loaded");
                    level.setChunkForced(chunkPos.x, chunkPos.z, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z) == null, "chunk unloaded"))
                .thenWaitUntil(() -> helper.assertTrue(!ENGINE.isTracked(level, lamp), "ChunkEvent.Unload dropped the chunk's positions"))
                .thenExecute(() -> level.setChunkForced(chunkPos.x, chunkPos.z, true))
                .thenWaitUntil(() -> helper.assertTrue(level.shouldTickBlocksAt(lamp), "chunk reloaded and ticking"))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(ENGINE.isTracked(level, lamp), "the chunk-load scan found the saved lamp");
                        reset(level, chunkOf(lamp));
                        double grossBefore = gross(server);
                        interval(server);
                        helper.assertTrue(gross(server) == grossBefore + LAMP_RATE, "and it emits again");
                    } finally {
                        level.setBlock(lamp, Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(power, Blocks.AIR.defaultBlockState(), 3);
                        level.setChunkForced(chunkPos.x, chunkPos.z, false);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY_TEMPLATE, batch = "emitters_furnace", timeoutTicks = 600)
    public static void aBurningFurnaceEmitsOnePuPerIntervalTimesTheMultiplierAndAColdOneNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ChunkPos hotChunk = new ChunkPos(98_000, 98_000);
        ChunkPos coldChunk = new ChunkPos(98_100, 98_000);
        BlockPos hot = new BlockPos(hotChunk.getMinBlockX() + 5, 64, hotChunk.getMinBlockZ() + 5);
        BlockPos cold = new BlockPos(coldChunk.getMinBlockX() + 5, 64, coldChunk.getMinBlockZ() + 5);
        level.setChunkForced(hotChunk.x, hotChunk.z, true);
        level.setChunkForced(coldChunk.x, coldChunk.z, true);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.shouldTickBlocksAt(hot) && level.shouldTickBlocksAt(cold), "chunks ticking"))
                .thenExecute(() -> {
                    level.setBlock(hot, Blocks.FURNACE.defaultBlockState(), 3);
                    level.setBlock(cold, Blocks.FURNACE.defaultBlockState(), 3);
                    AbstractFurnaceBlockEntity burning = (AbstractFurnaceBlockEntity) level.getBlockEntity(hot);
                    burning.setItem(0, new ItemStack(Items.COBBLESTONE));
                    burning.setItem(1, new ItemStack(Items.COAL));
                })
                .thenWaitUntil(() -> helper.assertTrue(
                        level.getBlockState(hot).getValue(net.minecraft.world.level.block.FurnaceBlock.LIT), "the furnace is really burning"))
                .thenExecute(() -> {
                    try {
                        reset(level, chunkOf(hot), chunkOf(cold));
                        double grossBefore = gross(server);
                        interval(server);
                        helper.assertTrue(PollutionManager.api().getLocal(level, hotChunk.x, hotChunk.z) == 1.0, "lit furnace: exactly 1 PU");
                        helper.assertTrue(PollutionManager.api().getLocal(level, coldChunk.x, coldChunk.z) == 0.0, "unlit furnace: nothing");
                        helper.assertTrue(gross(server) == grossBefore + 1.0, "gross rose by 1 PU");

                        helper.assertTrue(PollutionCraftMod.config().overrides().set("emissionMultiplier", 2.0).isEmpty(), "override accepted");
                        double hotBefore = PollutionManager.api().getLocal(level, hotChunk.x, hotChunk.z);
                        interval(server);
                        helper.assertTrue(
                                PollutionManager.api().getLocal(level, hotChunk.x, hotChunk.z) - hotBefore == 2.0, "multiplier 2: exactly 2 PU");
                        helper.assertTrue(gross(server) == grossBefore + 3.0, "gross rose by 2 more");
                        helper.assertTrue(PollutionManager.api().getLocal(level, coldChunk.x, coldChunk.z) == 0.0, "cold still nothing");
                    } finally {
                        PollutionCraftMod.config().overrides().clearAll();
                        level.setBlock(hot, Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(cold, Blocks.AIR.defaultBlockState(), 3);
                        level.setChunkForced(hotChunk.x, hotChunk.z, false);
                        level.setChunkForced(coldChunk.x, coldChunk.z, false);
                    }
                })
                .thenSucceed();
    }

    // ---- measurements (plan test 9): reported, not gates ------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = "emitters_measure", timeoutTicks = 1200)
    public static void measureTheEngineCost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ChunkPos busy = new ChunkPos(99_000, 99_000);
        ChunkPos plain = new ChunkPos(99_050, 99_000);
        List<ChunkPos> worst = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            worst.add(new ChunkPos(99_000 + i * 3, 99_100));
        }
        level.setChunkForced(busy.x, busy.z, true);
        level.setChunkForced(plain.x, plain.z, true);
        worst.forEach(c -> level.setChunkForced(c.x, c.z, true));
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(level.shouldTickBlocksAt(busy.getWorldPosition()), "ticking"))
                .thenExecute(() -> {
                    List<BlockPos> placed = new ArrayList<>();
                    try {
                        measureAll(helper, level, server, busy, plain, worst, placed);
                    } finally {
                        placed.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2 | 16));
                        PollutionCraftMod.config().overrides().clearAll();
                        level.setChunkForced(busy.x, busy.z, false);
                        level.setChunkForced(plain.x, plain.z, false);
                        worst.forEach(c -> level.setChunkForced(c.x, c.z, false));
                    }
                })
                .thenSucceed();
    }

    private static void measureAll(
            GameTestHelper helper, ServerLevel level, MinecraftServer server, ChunkPos busy, ChunkPos plain, List<ChunkPos> worst,
            List<BlockPos> placed) {
        DimensionPollution dimension = reset(level, ChunkKey.pack(busy.x, busy.z));
        EmitterRules rules = EmitterEngine.currentRules();
        int minY = level.getMinBuildHeight();
        int sections = level.getSectionsCount();

        // The field a real server would have: 2,500 spreading chunks, to price diffusion next to the engine.
        List<Long> seeded = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            long key = ChunkKey.pack(200_000 + i % 50, 200_000 + i / 50);
            dimension.field().getOrCreate(key).absorbable = 20 + i % 97;
            seeded.add(key);
        }

        // 100 emitters in one chunk, then 1,024.
        for (int i = 0; i < 100; i++) {
            placeLamp(level, new BlockPos(busy.getMinBlockX() + i % 10, 64, busy.getMinBlockZ() + i / 10), placed);
        }
        EmitterEngine engine = EmitterEngine.global();
        engine.onChunkLoad(level, level.getChunk(busy.x, busy.z));
        helper.assertTrue(allTracked(engine, level, placed), "each of the 100 placed lamps is tracked");
        double totalPerTick100 = totalMsPerTick(server, 30);
        double pass100 = passMicros(engine, level, 3000);

        for (int y = 64; y < 68; y++) {
            for (int i = 0; i < 256; i++) {
                BlockPos pos = new BlockPos(busy.getMinBlockX() + i % 16, y, busy.getMinBlockZ() + i / 16);
                if (!level.getBlockState(pos).is(Blocks.REDSTONE_LAMP)) {
                    placeLamp(level, pos, placed);
                }
            }
        }
        engine.onChunkLoad(level, level.getChunk(busy.x, busy.z));
        helper.assertTrue(allTracked(engine, level, placed), "each of the 1,024 placed lamps is tracked");
        int tracked = placed.size();
        double pass1000 = passMicros(engine, level, 1500);

        // Chunk-load scans: a plain chunk, and the worst case (a mapped block in every section).
        EmitterEngine scanner = new EmitterEngine(EmitterEngine::currentRules, (l, k) -> true, () -> 1.0, PollutionManager.api());
        LevelChunk plainChunk = level.getChunk(plain.x, plain.z);
        double scanPlain = timeMicros(300, () -> scanner.onChunkLoad(level, plainChunk));
        for (ChunkPos c : worst) {
            for (int k = 0; k < sections; k++) {
                placeLamp(level, new BlockPos(c.getMinBlockX() + 1, minY + 1 + 16 * k, c.getMinBlockZ() + 1), placed);
            }
        }
        LevelChunk worstChunk = level.getChunk(worst.get(0).x, worst.get(0).z);
        double scanWorst = timeMicros(300, () -> scanner.onChunkLoad(level, worstChunk));

        // Rescan worst case: 8 chunks, every section holds a mapped block; rules arrive after the chunks loaded.
        EmitterRules[] current = {EmitterRules.EMPTY};
        EmitterEngine rescanner = new EmitterEngine(() -> current[0], (l, k) -> true, () -> 1.0, PollutionManager.api());
        worst.forEach(c -> rescanner.onChunkLoad(level, level.getChunk(c.x, c.z)));
        List<Double> sliceMicros = new ArrayList<>();
        for (int round = 0; round < 12; round++) {
            boolean rulesArrive = round % 2 == 1; // EMPTY -> rules changes the rule set: every loaded chunk is queued again
            current[0] = rulesArrive ? rules : EmitterRules.EMPTY;
            for (int t = 0; t < 300; t++) {
                long begin = System.nanoTime();
                rescanner.tick(level, false);
                long took = System.nanoTime() - begin;
                if (rulesArrive && round >= 5 && took > 3000) { // warmed up, and a tick that did work, not an idle one
                    sliceMicros.add(took / 1000.0);
                }
            }
        }
        sliceMicros.sort(Double::compare);
        double sliceMean = sliceMicros.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sliceMax = sliceMicros.isEmpty() ? 0 : sliceMicros.get(sliceMicros.size() - 1);

        // Neighbour-update handler: a burst of updates at blocks nobody maps, through the real event bus.
        BlockPos probe = busy.getWorldPosition();
        BlockState stone = Blocks.STONE.defaultBlockState();
        java.util.EnumSet<net.minecraft.core.Direction> all = java.util.EnumSet.allOf(net.minecraft.core.Direction.class);
        int events = 500_000;
        for (int i = 0; i < events; i++) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent(level, probe, stone, all, false));
        }
        long begin = System.nanoTime();
        for (int i = 0; i < events; i++) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.BlockEvent.NeighborNotifyEvent(level, probe, stone, all, false));
        }
        double nsPerEvent = (System.nanoTime() - begin) / (double) events;

        System.out.printf("PERF 011 total (diffusion @2500 chunks + emission pass @100 emitters + idle rescan): %.4f ms/tick%n", totalPerTick100);
        System.out.printf("PERF 011 emission pass @100 emitters: median-of-batch %.1f us/interval = %.5f ms/tick%n", pass100, pass100 / 1000 / 20);
        System.out.printf("PERF 011 emission pass @%d emitters: %.1f us/interval = %.5f ms/tick%n", tracked, pass1000, pass1000 / 1000 / 20);
        System.out.printf("PERF 011 chunk-load scan: plain chunk %.1f us, worst case (mapped block in all %d sections) %.1f us%n", scanPlain, sections, scanWorst);
        System.out.printf("PERF 011 rescan slice, worst case: mean %.1f us, max %.1f us per tick (%d busy ticks)%n", sliceMean, sliceMax, sliceMicros.size());
        System.out.printf("PERF 011 NeighborNotifyEvent, non-matching, incl. bus dispatch (event allocated in the loop): %.0f ns/event%n", nsPerEvent);

        PollutionManager.dimension(level).scheduler().abandon();
        seeded.forEach(dimension.field()::remove);
        for (int x = 199_999; x <= 200_050; x++) {
            for (int z = 199_999; z <= 200_050; z++) {
                dimension.field().remove(ChunkKey.pack(x, z));
            }
        }
        PollutionManager.stepNow(level, 1);
    }

    /** Only this test's own blocks are checked: generated structures elsewhere in the world are tracked too. */
    private static boolean allTracked(EmitterEngine engine, ServerLevel level, List<BlockPos> positions) {
        return positions.stream().allMatch(pos -> engine.isTracked(level, pos));
    }

    private static void placeLamp(ServerLevel level, BlockPos pos, List<BlockPos> placed) {
        level.setBlock(pos, litLamp(), 2 | 16); // no update: the measurement scans explicitly
        placed.add(pos);
    }

    private static double totalMsPerTick(MinecraftServer server, int intervals) {
        int perInterval = PollutionCraftMod.config().spreadIntervalTicks();
        for (int i = 0; i < 10 * perInterval; i++) {
            PollutionManager.tick(server, 1); // warm-up
        }
        long begin = System.nanoTime();
        for (int i = 0; i < intervals * perInterval; i++) {
            PollutionManager.tick(server, 1);
        }
        return (System.nanoTime() - begin) / 1e6 / (intervals * perInterval);
    }

    private static double passMicros(EmitterEngine engine, ServerLevel level, int calls) {
        for (int i = 0; i < calls / 3; i++) {
            engine.tick(level, true);
        }
        double[] batches = new double[5];
        for (int b = 0; b < batches.length; b++) {
            batches[b] = timeMicros(calls / 5, () -> engine.tick(level, true));
        }
        java.util.Arrays.sort(batches);
        return batches[batches.length / 2];
    }

    private static double timeMicros(int calls, Runnable work) {
        long begin = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            work.run();
        }
        return (System.nanoTime() - begin) / 1000.0 / calls;
    }
}
