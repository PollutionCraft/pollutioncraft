package io.github.pollutioncraft.gametest;

import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.mod.DimensionPollution;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.PollutionManager;
import io.github.pollutioncraft.mod.emitter.EmitterEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import io.github.pollutioncraft.mod.emitter.EmitterRules;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The shipped vanilla mappings (task 029) against a live server, using the real data. Expected rates are read from
 * the shipped rules, never written here, so a rate change is a one-number data edit.
 *
 * <p>The GameTest world is a normal world with structures and a new seed each run: villages hold torches, lanterns
 * and campfires. Every test therefore picks chunks that hold no mapped block before it places its own, and fails
 * if it cannot find them. Tests that wait for real ticks run in their own batch, so a lit block waiting for a tick
 * cannot add to another test's exact {@code grossEmitted} assertions.
 */
@GameTestHolder("pollutioncraft")
@PrefixGameTestTemplate(false)
public final class VanillaEmitterGameTests {
    private static final String EMPTY_TEMPLATE = "empty";
    private static final EmitterEngine ENGINE = EmitterEngine.global();
    /** Candidate chunks per test: fixed, so a run is repeatable; the first clean ones are used. */
    private static final int CANDIDATES = 12;

    private VanillaEmitterGameTests() {}

    private static void interval(MinecraftServer server) {
        for (int i = 0; i < PollutionCraftMod.config().spreadIntervalTicks(); i++) {
            PollutionManager.tick(server, 1);
        }
    }

    private static double gross(MinecraftServer server) {
        return PollutionManager.world(server).record().grossEmitted();
    }

    private static long keyOf(ChunkPos chunk) {
        return ChunkKey.pack(chunk.x, chunk.z);
    }

    private static double local(ServerLevel level, ChunkPos chunk) {
        return PollutionManager.api().getLocal(level, chunk.x, chunk.z);
    }

    /**
     * Forces and returns {@code count} chunks that hold no mapped block (after the engine's load scan), searching a
     * fixed list that starts at {@code firstX}. Fails the test if there are not enough; never passes silently.
     */
    private static List<ChunkPos> cleanChunks(GameTestHelper helper, ServerLevel level, int firstX, int count) {
        return cleanChunks(helper, level, firstX, count, CANDIDATES);
    }

    private static List<ChunkPos> cleanChunks(GameTestHelper helper, ServerLevel level, int firstX, int count, int candidates) {
        List<ChunkPos> clean = new ArrayList<>();
        for (int i = 0; i < candidates && clean.size() < count; i++) {
            ChunkPos candidate = new ChunkPos(firstX + 3 * i, 95_000);
            level.setChunkForced(candidate.x, candidate.z, true); // loads it; the engine scans it on load
            if (ENGINE.trackedIn(level, keyOf(candidate)).length == 0) {
                clean.add(candidate);
            } else {
                level.setChunkForced(candidate.x, candidate.z, false);
            }
        }
        if (clean.size() < count) {
            clean.forEach(c -> level.setChunkForced(c.x, c.z, false));
            helper.fail("fewer than " + count + " chunks without a mapped block among " + candidates + " candidates from x=" + firstX);
        }
        return clean;
    }

    private static BlockPos centre(ChunkPos chunk) {
        return new BlockPos(chunk.getMinBlockX() + 5, 64, chunk.getMinBlockZ() + 5);
    }

    /** Fresh state around the chunks: nothing pending or in flight, no override, the field clear of them. */
    private static DimensionPollution reset(ServerLevel level, ChunkPos... chunks) {
        PollutionCraftMod.config().overrides().clearAll();
        DimensionPollution dimension = PollutionManager.dimension(level);
        dimension.scheduler().abandon();
        interval(level.getServer()); // flush what earlier tests left pending
        for (ChunkPos chunk : chunks) {
            for (int[] d : new int[][] {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                dimension.field().remove(ChunkKey.pack(chunk.x + d[0], chunk.z + d[1]));
            }
        }
        return dimension;
    }

    private static boolean ticking(ServerLevel level, List<ChunkPos> chunks) {
        return chunks.stream().allMatch(c -> level.shouldTickBlocksAt(centre(c)));
    }

    private static void release(ServerLevel level, List<ChunkPos> chunks) {
        chunks.forEach(c -> level.setChunkForced(c.x, c.z, false));
    }

    // ---- blast furnace (first) -----------------------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = "vanilla_blast_furnace", timeoutTicks = 600)
    public static void aBurningBlastFurnaceEmitsItsMappedRateAndAColdOneNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        List<ChunkPos> chunks = cleanChunks(helper, level, 95_100, 2);
        ChunkPos hotChunk = chunks.get(0);
        ChunkPos coldChunk = chunks.get(1);
        BlockPos hot = centre(hotChunk);
        BlockPos cold = centre(coldChunk);
        BlockState lit = Blocks.BLAST_FURNACE.defaultBlockState().setValue(BlastFurnaceBlock.LIT, true);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(level, chunks), "chunks ticking"))
                .thenExecute(() -> {
                    level.setBlock(hot, Blocks.BLAST_FURNACE.defaultBlockState(), 3);
                    level.setBlock(cold, Blocks.BLAST_FURNACE.defaultBlockState(), 3);
                    AbstractFurnaceBlockEntity burning = (AbstractFurnaceBlockEntity) level.getBlockEntity(hot);
                    burning.setItem(0, new ItemStack(Items.RAW_IRON));
                    burning.setItem(1, new ItemStack(Items.COAL));
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(hot).getValue(BlastFurnaceBlock.LIT), "the blast furnace is really burning"))
                .thenExecute(() -> {
                    try {
                        reset(level, hotChunk, coldChunk);
                        double rate = EmitterEngine.currentRules().activeRate(lit);
                        double grossBefore = gross(server);

                        interval(server);

                        helper.assertTrue(local(level, hotChunk) > 0, "the lit blast furnace emitted nothing");
                        helper.assertTrue(local(level, hotChunk) == rate, "exactly the mapped rate " + rate + ", got " + local(level, hotChunk));
                        helper.assertTrue(local(level, coldChunk) == 0.0, "an unlit blast furnace adds nothing");
                        helper.assertTrue(gross(server) == grossBefore + rate, "gross rose by the same amount");
                    } finally {
                        level.setBlock(hot, Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(cold, Blocks.AIR.defaultBlockState(), 3);
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    // ---- the shipped rates relate to each other -----------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE)
    public static void theBlastFurnaceAndSmokerBurnTwiceAsFastSoShipAtTwiceTheFurnaceRate(GameTestHelper helper) {
        var rules = EmitterEngine.currentRules();
        double furnace = rules.activeRate(Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.FurnaceBlock.LIT, true));
        double blast = rules.activeRate(Blocks.BLAST_FURNACE.defaultBlockState().setValue(BlastFurnaceBlock.LIT, true));
        double smoker = rules.activeRate(Blocks.SMOKER.defaultBlockState().setValue(SmokerBlock.LIT, true));
        List<String> wrong = new ArrayList<>();
        // Their block entities halve the burn duration (getBurnDuration / 2), so fuel lasts half as long: twice the output per tick.
        if (!(furnace > 0) || blast != 2 * furnace) {
            wrong.add("blast furnace " + blast + " is not twice the furnace's " + furnace);
        }
        if (!(furnace > 0) || smoker != 2 * furnace) {
            wrong.add("smoker " + smoker + " is not twice the furnace's " + furnace);
        }
        helper.assertTrue(wrong.isEmpty(), "shipped rates: " + wrong);
        helper.succeed();
    }

    // ---- smoker --------------------------------------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = "vanilla_smoker", timeoutTicks = 600)
    public static void aBurningSmokerEmitsItsMappedRateAndAColdOneNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        List<ChunkPos> chunks = cleanChunks(helper, level, 95_200, 2);
        ChunkPos hotChunk = chunks.get(0);
        ChunkPos coldChunk = chunks.get(1);
        BlockPos hot = centre(hotChunk);
        BlockPos cold = centre(coldChunk);
        BlockState lit = Blocks.SMOKER.defaultBlockState().setValue(SmokerBlock.LIT, true);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(level, chunks), "chunks ticking"))
                .thenExecute(() -> {
                    level.setBlock(hot, Blocks.SMOKER.defaultBlockState(), 3);
                    level.setBlock(cold, Blocks.SMOKER.defaultBlockState(), 3);
                    AbstractFurnaceBlockEntity burning = (AbstractFurnaceBlockEntity) level.getBlockEntity(hot);
                    burning.setItem(0, new ItemStack(Items.BEEF));
                    burning.setItem(1, new ItemStack(Items.COAL));
                })
                .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(hot).getValue(SmokerBlock.LIT), "the smoker is really burning"))
                .thenExecute(() -> {
                    try {
                        reset(level, hotChunk, coldChunk);
                        double rate = EmitterEngine.currentRules().activeRate(lit);
                        double grossBefore = gross(server);

                        interval(server);

                        helper.assertTrue(local(level, hotChunk) > 0, "the lit smoker emitted nothing");
                        helper.assertTrue(local(level, hotChunk) == rate, "exactly the mapped rate " + rate + ", got " + local(level, hotChunk));
                        helper.assertTrue(local(level, coldChunk) == 0.0, "an unlit smoker adds nothing");
                        helper.assertTrue(gross(server) == grossBefore + rate, "gross rose by the same amount");
                    } finally {
                        level.setBlock(hot, Blocks.AIR.defaultBlockState(), 3);
                        level.setBlock(cold, Blocks.AIR.defaultBlockState(), 3);
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    // ---- campfire ------------------------------------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = "vanilla_campfire", timeoutTicks = 400)
    public static void aCampfireEmitsWhileLitStopsWhenPutOutAndEmitsAgainWhenRelit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        List<ChunkPos> chunks = cleanChunks(helper, level, 95_300, 1);
        ChunkPos chunk = chunks.get(0);
        BlockPos fire = centre(chunk);
        BlockState lit = Blocks.CAMPFIRE.defaultBlockState();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(level, chunks), "chunk ticking"))
                .thenExecute(() -> {
                    try {
                        level.setBlock(fire, lit, 3);
                        helper.assertTrue(level.getBlockState(fire).getValue(CampfireBlock.LIT), "a placed campfire is lit by default");
                        double rate = EmitterEngine.currentRules().activeRate(lit);
                        reset(level, chunk);
                        double grossBefore = gross(server);

                        interval(server);
                        helper.assertTrue(local(level, chunk) > 0, "the lit campfire emitted nothing");
                        helper.assertTrue(local(level, chunk) == rate, "exactly the mapped rate " + rate + ", got " + local(level, chunk));
                        helper.assertTrue(gross(server) == grossBefore + rate, "gross rose by the same amount");

                        level.setBlock(fire, lit.setValue(CampfireBlock.LIT, false), 3); // put out, as the shovel does
                        interval(server);
                        helper.assertTrue(local(level, chunk) == rate, "put out: nothing more, still " + local(level, chunk));
                        helper.assertTrue(gross(server) == grossBefore + rate, "put out: gross unchanged");

                        level.setBlock(fire, lit, 3); // relit
                        interval(server);
                        helper.assertTrue(local(level, chunk) == 2 * rate, "relit: emits again, got " + local(level, chunk));
                    } finally {
                        level.setBlock(fire, Blocks.AIR.defaultBlockState(), 3);
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    // ---- torch, wall torch, lantern: always active -----------------------------------------------

    /** Placed, the block raises its chunk by its mapped rate per interval; removed, it stops. The rate comes from the shipped rules. */
    private static void alwaysActiveWhilePlaced(GameTestHelper helper, int firstX, BlockState emitter, Consumer<BlockPos> support, Consumer<BlockPos> removeSupport) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        List<ChunkPos> chunks = cleanChunks(helper, level, firstX, 1);
        ChunkPos chunk = chunks.get(0);
        BlockPos pos = centre(chunk);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(level, chunks), "chunk ticking"))
                .thenExecute(() -> {
                    try {
                        support.accept(pos);
                        level.setBlock(pos, emitter, 3);
                        helper.assertTrue(level.getBlockState(pos) == emitter, "the block stayed placed: " + level.getBlockState(pos));
                        helper.assertTrue(ENGINE.isTracked(level, pos), "and is tracked");
                        double rate = EmitterEngine.currentRules().activeRate(emitter);
                        reset(level, chunk);
                        double grossBefore = gross(server);

                        interval(server);
                        // 0.01 is not exact in binary, so these are compared within 1e-9.
                        helper.assertTrue(local(level, chunk) > 0, "the placed block emitted nothing");
                        helper.assertTrue(Math.abs(local(level, chunk) - rate) <= 1e-9, "the mapped rate " + rate + " per interval, got " + local(level, chunk));
                        helper.assertTrue(Math.abs(gross(server) - (grossBefore + rate)) <= 1e-9, "gross rose by the same amount");

                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        interval(server);
                        helper.assertTrue(Math.abs(local(level, chunk) - rate) <= 1e-9, "removed: nothing more, got " + local(level, chunk));
                        helper.assertTrue(Math.abs(gross(server) - (grossBefore + rate)) <= 1e-9, "removed: gross unchanged");
                    } finally {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        removeSupport.accept(pos);
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    private static void stoneBelow(ServerLevel level, BlockPos pos) {
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
    }

    private static void airBelow(ServerLevel level, BlockPos pos) {
        level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 3);
    }

    @GameTest(template = EMPTY_TEMPLATE, batch = "vanilla_torch", timeoutTicks = 400)
    public static void aTorchEmitsItsMappedRateWhilePlaced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        alwaysActiveWhilePlaced(helper, 95_400, Blocks.TORCH.defaultBlockState(), pos -> stoneBelow(level, pos), pos -> airBelow(level, pos));
    }

    @GameTest(template = EMPTY_TEMPLATE, batch = "vanilla_wall_torch", timeoutTicks = 400)
    public static void aWallTorchEmitsItsMappedRateWhilePlaced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Facing south, so it hangs on the block to its north.
        BlockState wallTorch = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH);
        alwaysActiveWhilePlaced(helper, 95_500, wallTorch,
                pos -> level.setBlock(pos.north(), Blocks.STONE.defaultBlockState(), 3),
                pos -> level.setBlock(pos.north(), Blocks.AIR.defaultBlockState(), 3));
    }

    @GameTest(template = EMPTY_TEMPLATE, batch = "vanilla_lantern", timeoutTicks = 400)
    public static void aLanternEmitsItsMappedRateWhilePlaced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        alwaysActiveWhilePlaced(helper, 95_600, Blocks.LANTERN.defaultBlockState(), pos -> stoneBelow(level, pos), pos -> airBelow(level, pos));
    }

    // ---- negative controls: blocks the user left out ---------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = "vanilla_out_list", timeoutTicks = 600)
    public static void blocksTheUserLeftOutEmitNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        record Control(String name, BlockState state, Consumer<BlockPos> support) {}
        List<Control> controls = List.of(
                new Control("soul campfire", Blocks.SOUL_CAMPFIRE.defaultBlockState(), pos -> {}),
                new Control("soul torch", Blocks.SOUL_TORCH.defaultBlockState(), pos -> stoneBelow(level, pos)),
                new Control("soul wall torch", Blocks.SOUL_WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH),
                        pos -> level.setBlock(pos.north(), Blocks.STONE.defaultBlockState(), 3)),
                new Control("soul lantern", Blocks.SOUL_LANTERN.defaultBlockState(), pos -> stoneBelow(level, pos)),
                new Control("lit candle", Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true), pos -> stoneBelow(level, pos)));
        List<ChunkPos> chunks = cleanChunks(helper, level, 95_700, controls.size());
        List<String> offenders = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(level, chunks), "chunks ticking"))
                .thenExecute(() -> {
                    try {
                        for (int i = 0; i < controls.size(); i++) {
                            Control control = controls.get(i);
                            BlockPos pos = centre(chunks.get(i));
                            control.support().accept(pos);
                            level.setBlock(pos, control.state(), 3);
                            helper.assertTrue(level.getBlockState(pos).is(control.state().getBlock()), control.name() + " stayed placed: " + level.getBlockState(pos));
                            if (EmitterEngine.currentRules().mapsBlock(control.state().getBlock())) {
                                offenders.add(control.name() + " is mapped");
                            }
                        }
                        reset(level, chunks.toArray(new ChunkPos[0]));

                        interval(level.getServer());

                        for (int i = 0; i < controls.size(); i++) {
                            if (local(level, chunks.get(i)) != 0.0) {
                                offenders.add(controls.get(i).name() + " added " + local(level, chunks.get(i)));
                            }
                        }
                        helper.assertTrue(offenders.isEmpty(), "blocks the user left out must emit nothing: " + offenders);
                    } finally {
                        for (int i = 0; i < controls.size(); i++) {
                            BlockPos pos = centre(chunks.get(i));
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                            level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 3);
                            level.setBlock(pos.north(), Blocks.AIR.defaultBlockState(), 3);
                        }
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    // ---- scale ---------------------------------------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = "vanilla_hundred_torches", timeoutTicks = 600)
    public static void oneHundredTorchesGiveOnePuAndTwiceThatWithAMultiplierOfTwo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        List<ChunkPos> chunks = cleanChunks(helper, level, 95_800, 1);
        ChunkPos chunk = chunks.get(0);
        List<BlockPos> torches = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            torches.add(new BlockPos(chunk.getMinBlockX() + i % 10, 64, chunk.getMinBlockZ() + i / 10));
        }
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(level, chunks), "chunk ticking"))
                .thenExecute(() -> {
                    try {
                        for (BlockPos pos : torches) {
                            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
                            level.setBlock(pos, Blocks.TORCH.defaultBlockState(), 3);
                        }
                        helper.assertTrue(torches.stream().allMatch(pos -> ENGINE.isTracked(level, pos)), "all 100 torches are tracked");
                        reset(level, chunk);
                        double grossBefore = gross(server);

                        interval(server);
                        helper.assertTrue(local(level, chunk) > 0, "100 torches emitted nothing");
                        helper.assertTrue(Math.abs(local(level, chunk) - 1.0) <= 1e-9, "100 torches: 1.0 PU, got " + local(level, chunk)); // 0.01 is inexact
                        helper.assertTrue(Math.abs(gross(server) - (grossBefore + 1.0)) <= 1e-9, "gross rose by 1.0");

                        helper.assertTrue(PollutionCraftMod.config().overrides().set("emissionMultiplier", 2.0).isEmpty(), "override accepted");
                        double before = local(level, chunk);
                        interval(server);
                        helper.assertTrue(Math.abs(local(level, chunk) - before - 2.0) <= 1e-9, "multiplier 2: 2.0 PU, got " + (local(level, chunk) - before));
                    } finally {
                        PollutionCraftMod.config().overrides().clearAll();
                        for (BlockPos pos : torches) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                            level.setBlock(pos.below(), Blocks.AIR.defaultBlockState(), 3);
                        }
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    // ---- measurement with the real shipped mappings (reported, not a gate) ---------------------------------

    /** Places up to {@code count} emitters on solid floors, as a player would: wall torches on a stone row, torches and lanterns on a stone floor. */
    private static int placeMixed(ServerLevel level, ChunkPos chunk, int count, List<BlockPos> placed) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState wallTorch = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH);
        int emitters = 0;
        for (int story = 0; emitters < count; story++) {
            int y = 64 + 3 * story;
            for (int cell = 0; cell < 256; cell++) {
                put(level, new BlockPos(chunk.getMinBlockX() + cell % 16, y - 1, chunk.getMinBlockZ() + cell / 16), stone, placed);
            }
            for (int z = 0; z < 16 && emitters < count; z++) {
                for (int x = 0; x < 16 && emitters < count; x++) {
                    BlockPos pos = new BlockPos(chunk.getMinBlockX() + x, y, chunk.getMinBlockZ() + z);
                    switch (z % 4) {
                        case 0 -> put(level, pos, stone, placed); // the wall the next row's torches hang on
                        case 1 -> {
                            put(level, pos, wallTorch, placed);
                            emitters++;
                        }
                        case 2 -> {
                            put(level, pos, Blocks.TORCH.defaultBlockState(), placed);
                            emitters++;
                        }
                        default -> {
                            put(level, pos, Blocks.LANTERN.defaultBlockState(), placed);
                            emitters++;
                        }
                    }
                }
            }
        }
        return emitters;
    }

    /** Places {@code count} torches, 256 per story, each story on its own stone floor. */
    private static void placeTorchStack(ServerLevel level, ChunkPos chunk, int count, List<BlockPos> placed) {
        for (int i = 0; i < count; i++) {
            int story = i / 256;
            int cell = i % 256;
            int x = chunk.getMinBlockX() + cell % 16;
            int z = chunk.getMinBlockZ() + cell / 16;
            if (cell == 0) {
                for (int f = 0; f < 256; f++) {
                    put(level, new BlockPos(chunk.getMinBlockX() + f % 16, 63 + 2 * story, chunk.getMinBlockZ() + f / 16), Blocks.STONE.defaultBlockState(), placed);
                }
            }
            put(level, new BlockPos(x, 64 + 2 * story, z), Blocks.TORCH.defaultBlockState(), placed);
        }
    }

    private static void put(ServerLevel level, BlockPos pos, BlockState state, List<BlockPos> placed) {
        level.setBlock(pos, state, 2 | 16); // no update: the measurement scans explicitly
        placed.add(pos);
    }

    private static EmitterEngine privateEngine() {
        return new EmitterEngine(
                EmitterEngine::currentRules, (l, k) -> true, () -> 1.0, PollutionManager.api(),
                () -> PollutionCraftMod.config().maxTrackedPerChunk(), message -> {});
    }

    private static double percentile(List<Double> ascending, double q) {
        return ascending.isEmpty() ? 0 : ascending.get(Math.min(ascending.size() - 1, (int) Math.ceil(q * ascending.size()) - 1));
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double timeMicros(int calls, Runnable work) {
        long begin = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            work.run();
        }
        return (System.nanoTime() - begin) / 1000.0 / calls;
    }

    private static double totalMsPerTick(MinecraftServer server, int intervals) {
        int perInterval = PollutionCraftMod.config().spreadIntervalTicks();
        for (int i = 0; i < 5 * perInterval; i++) {
            PollutionManager.tick(server, 1); // warm-up
        }
        long begin = System.nanoTime();
        for (int i = 0; i < intervals * perInterval; i++) {
            PollutionManager.tick(server, 1);
        }
        return (System.nanoTime() - begin) / 1e6 / (intervals * perInterval);
    }

    /** Rescan slices of {@code chunks}, queued again and again by a rule change, until at least 300 busy ticks. */
    private static List<Double> rescanSlices(ServerLevel level, List<ChunkPos> chunks) {
        EmitterRules[] current = {EmitterRules.EMPTY};
        EmitterEngine engine = new EmitterEngine(
                () -> current[0], (l, k) -> true, () -> 1.0, PollutionManager.api(),
                () -> PollutionCraftMod.config().maxTrackedPerChunk(), message -> {});
        chunks.forEach(c -> engine.onChunkLoad(level, level.getChunk(c.x, c.z)));
        List<Double> micros = new ArrayList<>();
        for (int round = 0; round < 4_000 && micros.size() < 300; round++) {
            current[0] = EmitterEngine.currentRules();
            for (int t = 0; t < 600; t++) {
                long begin = System.nanoTime();
                engine.tick(level, false);
                long took = System.nanoTime() - begin;
                if (round >= 3 && took > 3000) {
                    micros.add(took / 1000.0);
                }
            }
            current[0] = EmitterRules.EMPTY;
            engine.tick(level, false);
        }
        micros.sort(Double::compare);
        return micros;
    }

    @GameTest(template = EMPTY_TEMPLATE, batch = "vanilla_measure", timeoutTicks = 4000)
    public static void measureTheShippedVanillaMappings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        List<ChunkPos> clean = cleanChunks(helper, level, 94_000, 22, 150);
        ChunkPos plain = clean.get(0);
        ChunkPos atCap = clean.get(1);
        List<ChunkPos> twenty = clean.subList(2, 22);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ticking(level, clean), "ticking"))
                .thenExecute(() -> {
                    List<BlockPos> placed = new ArrayList<>();
                    try {
                        measure(helper, level, server, plain, atCap, twenty, placed);
                    } finally {
                        placed.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2 | 16));
                        PollutionCraftMod.config().overrides().clearAll();
                        clean.forEach(c -> ENGINE.onChunkUnload(level, keyOf(c)));
                        release(level, clean);
                    }
                })
                .thenSucceed();
    }

    private static void measure(
            GameTestHelper helper, ServerLevel level, MinecraftServer server, ChunkPos plain, ChunkPos atCap, List<ChunkPos> twenty, List<BlockPos> placed) {
        DimensionPollution dimension = reset(level, plain);
        List<Long> seeded = new ArrayList<>();
        for (int i = 0; i < 2500; i++) {
            long key = ChunkKey.pack(200_000 + i % 50, 200_000 + i / 50);
            dimension.field().getOrCreate(key).absorbable = 20 + i % 97;
            seeded.add(key);
        }
        totalMsPerTick(server, 10);
        double baseline = totalMsPerTick(server, 20);
        EmitterEngine scanner = privateEngine();
        LevelChunk plainChunk = level.getChunk(plain.x, plain.z);
        double scanPlain = timeMicros(2_000, () -> scanner.onChunkLoad(level, plainChunk));

        record Scenario(String name, List<ChunkPos> chunks, int expectedTrackedPerChunk) {}
        for (Scenario scenario : List.of(
                new Scenario("300 torches and lanterns (with wall torches) in each of 20 chunks", twenty, 300),
                new Scenario("one chunk at the cap: 2,304 torches placed", List.of(atCap), 2_048))) {
            for (ChunkPos chunk : scenario.chunks()) {
                helper.assertTrue(ENGINE.trackedIn(level, keyOf(chunk)).length == 0, "precondition: the chunk holds no mapped block");
                if (scenario.chunks().size() == 1) {
                    placeTorchStack(level, chunk, 2_304, placed);
                } else {
                    helper.assertTrue(placeMixed(level, chunk, 300, placed) == 300, "placed 300");
                }
            }
            EmitterEngine pass = privateEngine();
            for (ChunkPos chunk : scenario.chunks()) {
                pass.onChunkLoad(level, level.getChunk(chunk.x, chunk.z));
                ENGINE.onChunkUnload(level, keyOf(chunk));
                ENGINE.onChunkLoad(level, level.getChunk(chunk.x, chunk.z));
                helper.assertTrue(ENGINE.trackedIn(level, keyOf(chunk)).length == scenario.expectedTrackedPerChunk(),
                        "tracked " + ENGINE.trackedIn(level, keyOf(chunk)).length + " in " + chunk);
            }
            int tracked = scenario.expectedTrackedPerChunk() * scenario.chunks().size();
            for (int i = 0; i < 300; i++) {
                pass.tick(level, true); // warm-up
            }
            List<Double> batches = new ArrayList<>();
            for (int b = 0; b < 5; b++) {
                batches.add(timeMicros(scenario.chunks().size() > 1 ? 80 : 300, () -> pass.tick(level, true)));
            }
            batches.sort(Double::compare);
            double passMicros = batches.get(2);
            LevelChunk first = level.getChunk(scenario.chunks().get(0).x, scenario.chunks().get(0).z);
            double scan = timeMicros(300, () -> scanner.onChunkLoad(level, first));
            List<Double> slices = rescanSlices(level, scenario.chunks());
            double total = totalMsPerTick(server, 20);
            System.out.printf("PERF 029 [%s]: tracked %d; emission pass %.1f us/interval = %.5f ms/tick (%.3f us per tracked block); "
                            + "chunk-load scan %.1f us (a plain chunk: %.1f us); rescan slice mean %.1f us, p99 %.1f us over %d busy ticks; "
                            + "total %.4f ms/tick (diffusion alone %.4f)%n",
                    scenario.name(), tracked, passMicros, passMicros / 1000 / 20, passMicros / tracked, scan, scanPlain, mean(slices),
                    percentile(slices, 0.99), slices.size(), total, baseline);
            helper.assertTrue(slices.size() >= 200, "need at least 200 busy rescan ticks, got " + slices.size());

            placed.forEach(pos -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2 | 16));
            placed.clear();
            for (ChunkPos chunk : scenario.chunks()) {
                ENGINE.onChunkUnload(level, keyOf(chunk));
                ENGINE.onChunkLoad(level, level.getChunk(chunk.x, chunk.z));
            }
            dimension.scheduler().abandon();
            PollutionManager.stepNow(level, 1);
        }
        seeded.forEach(dimension.field()::remove);
        for (int x = 199_999; x <= 200_050; x++) {
            for (int z = 199_999; z <= 200_050; z++) {
                dimension.field().remove(ChunkKey.pack(x, z));
            }
        }
        dimension.scheduler().abandon();
        PollutionManager.stepNow(level, 1);
    }
}
