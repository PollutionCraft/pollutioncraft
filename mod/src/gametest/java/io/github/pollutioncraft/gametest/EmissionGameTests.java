package io.github.pollutioncraft.gametest;

import io.github.pollutioncraft.api.PollutionApi;
import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.core.ChunkPollution;
import io.github.pollutioncraft.mod.DimensionPollution;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.PollutionManager;
import io.github.pollutioncraft.mod.WorldPollution;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The emit path (task 010) against a live server. Every test uses its own far-away chunk. */
@GameTestHolder("pollutioncraft")
@PrefixGameTestTemplate(false)
public final class EmissionGameTests {
    private static final String EMPTY_TEMPLATE = "empty";
    /** Below the default spreadThreshold, so an emitted amount stays in its chunk. */
    private static final double AMOUNT = 10.0;

    private EmissionGameTests() {}

    private static BlockPos blockIn(int chunkX, int chunkZ) {
        return new BlockPos(chunkX * 16 + 5, 64, chunkZ * 16 + 7);
    }

    /**
     * The gametest world persists between local runs and tests leave emissions behind: flush them (they would
     * otherwise commit during this test and move {@code grossEmitted}), then clear the chunk and its neighbours.
     */
    private static void reset(MinecraftServer server, DimensionPollution dimension, int chunkX, int chunkZ) {
        dimension.scheduler().abandon();
        interval(server);
        for (int[] d : new int[][] {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            dimension.field().remove(ChunkKey.pack(chunkX + d[0], chunkZ + d[1]));
        }
    }

    private static void ticks(MinecraftServer server, int ticks) {
        for (int i = 0; i < ticks; i++) {
            PollutionManager.tick(server, 1);
        }
    }

    private static void interval(MinecraftServer server) {
        ticks(server, PollutionCraftMod.config().spreadIntervalTicks());
    }

    private static double gross(MinecraftServer server) {
        return PollutionManager.world(server).record().grossEmitted();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void emitAppearsAfterOneIntervalWithGrossAndDominantSource(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionPollution dimension = PollutionManager.dimension(level);
        PollutionApi api = PollutionManager.api();
        reset(server, dimension, 90_000, 90_000);
        BlockPos source = blockIn(90_000, 90_000);
        long chunk = ChunkKey.pack(90_000, 90_000);
        double grossBefore = gross(server);

        api.emit(level, source, AMOUNT);

        helper.assertTrue(dimension.pendingEmission(chunk) == AMOUNT, "buffered, not yet applied");
        helper.assertTrue(api.getLocal(level, 90_000, 90_000) == 0.0, "emit must not write the field");
        interval(server);

        helper.assertTrue(api.getLocal(level, 90_000, 90_000) == AMOUNT, "local was " + api.getLocal(level, 90_000, 90_000));
        helper.assertTrue(gross(server) == grossBefore + AMOUNT, "gross rose by " + (gross(server) - grossBefore));
        ChunkPollution c = dimension.field().get(chunk);
        helper.assertTrue(c.emittedLastInterval == AMOUNT, "emittedLastInterval " + c.emittedLastInterval);
        helper.assertTrue(c.dominantSourcePos == source.asLong() && c.dominantSourceRate == AMOUNT, "dominant source");
        helper.assertTrue(dimension.pendingEmission(chunk) == 0.0, "buffer drained");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void aNonEmittingIntervalClearsEmissionAndDominantSource(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionPollution dimension = PollutionManager.dimension(level);
        reset(server, dimension, 91_000, 91_000);
        long chunk = ChunkKey.pack(91_000, 91_000);
        PollutionManager.api().emit(level, blockIn(91_000, 91_000), AMOUNT);
        interval(server);
        double grossAfterFirst = gross(server);

        interval(server);

        ChunkPollution c = dimension.field().get(chunk);
        helper.assertTrue(c.emittedLastInterval == 0.0 && c.dominantSourceRate == 0.0 && c.dominantSourcePos == 0L, "cleared");
        helper.assertTrue(gross(server) == grossAfterFirst, "gross must not change without emissions");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void anOffThreadCallIsRejectedAndChangesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionPollution dimension = PollutionManager.dimension(level);
        PollutionApi api = PollutionManager.api();
        reset(server, dimension, 92_000, 92_000);
        long chunk = ChunkKey.pack(92_000, 92_000);
        double grossBefore = gross(server);

        CompletableFuture.runAsync(() -> api.emit(level, blockIn(92_000, 92_000), AMOUNT)).join();

        helper.assertTrue(dimension.pendingEmission(chunk) == 0.0, "nothing buffered");
        interval(server);
        helper.assertTrue(dimension.field().get(chunk) == null, "field unchanged");
        helper.assertTrue(gross(server) == grossBefore, "gross unchanged");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void invalidAmountsChangeNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionPollution dimension = PollutionManager.dimension(level);
        PollutionApi api = PollutionManager.api();
        reset(server, dimension, 92_500, 92_500);
        long chunk = ChunkKey.pack(92_500, 92_500);
        BlockPos pos = blockIn(92_500, 92_500);

        for (double bad : new double[] {-1.0, Double.NaN, Double.POSITIVE_INFINITY, 0.0}) {
            api.emit(level, pos, bad);
        }

        helper.assertTrue(dimension.pendingEmission(chunk) == 0.0, "nothing buffered");
        interval(server);
        helper.assertTrue(dimension.field().get(chunk) == null, "field unchanged");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void abandoningAfterTheDrainRequeuesAndCountsOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionPollution dimension = PollutionManager.dimension(level);
        PollutionApi api = PollutionManager.api();
        reset(server, dimension, 93_000, 93_000);
        long chunk = ChunkKey.pack(93_000, 93_000);
        double grossBefore = gross(server);
        api.emit(level, blockIn(93_000, 93_000), AMOUNT);

        ticks(server, PollutionCraftMod.config().spreadIntervalTicks() / 2);
        helper.assertTrue(dimension.scheduler().inFlight() && dimension.pendingEmission(chunk) == 0.0, "drained into the interval");
        dimension.scheduler().abandon();

        helper.assertTrue(dimension.pendingEmission(chunk) == AMOUNT, "re-queued");
        helper.assertTrue(gross(server) == grossBefore && dimension.field().get(chunk) == null, "a dropped interval changes nothing");
        interval(server);
        helper.assertTrue(api.getLocal(level, 93_000, 93_000) == AMOUNT, "arrived exactly once");
        helper.assertTrue(gross(server) == grossBefore + AMOUNT, "gross counted once");
        interval(server);
        helper.assertTrue(api.getLocal(level, 93_000, 93_000) == AMOUNT && gross(server) == grossBefore + AMOUNT, "not applied again");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void emitIntoAnUnloadedChunkNeverLoadsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionPollution dimension = PollutionManager.dimension(level);
        reset(server, dimension, 94_000, 94_000);
        int chunkX = 94_000;
        int chunkZ = 94_000;
        helper.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "precondition: chunk not loaded");

        PollutionManager.api().emit(level, blockIn(chunkX, chunkZ), AMOUNT);
        interval(server);

        helper.assertTrue(level.getChunkSource().getChunkNow(chunkX, chunkZ) == null, "emit must not load the chunk");
        helper.assertTrue(!level.hasChunk(chunkX, chunkZ), "chunk still not present");
        helper.assertTrue(PollutionManager.api().getLocal(level, chunkX, chunkZ) == AMOUNT, "the field holds the amount");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void emitIntoAPausedDimensionIsDroppedNotBuffered(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionDataStorage storage = level.getDataStorage();
        DimensionPollution real = PollutionManager.dimension(level);
        reset(server, real, 95_000, 95_000);
        long chunk = ChunkKey.pack(95_000, 95_000);
        CompoundTag future = new CompoundTag();
        future.putInt("dataVersion", 999);
        DimensionPollution paused = DimensionPollution.load(future, level.registryAccess());
        storage.set("pollutioncraft_field", paused);
        try {
            PollutionManager.api().emit(level, blockIn(95_000, 95_000), AMOUNT);
            PollutionManager.api().emit(level, blockIn(95_000, 95_000), AMOUNT);
            helper.assertTrue(paused.pendingEmission(chunk) == 0.0, "paused reads no pending amount");
        } finally {
            storage.set("pollutioncraft_field", real);
        }

        helper.assertTrue(real.pendingEmission(chunk) == 0.0, "nothing reached the real buffer");
        interval(server);
        helper.assertTrue(real.field().get(chunk) == null, "nothing was kept for later");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void pendingAndUncommittedEmissionsAreLostOnRestartButCommittedOnesAreSaved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionPollution dimension = PollutionManager.dimension(level);
        PollutionApi api = PollutionManager.api();
        reset(server, dimension, 96_000, 96_000);
        long chunk = ChunkKey.pack(96_000, 96_000);
        double grossBefore = gross(server);
        api.emit(level, blockIn(96_000, 96_000), AMOUNT);
        interval(server); // committed
        api.emit(level, blockIn(96_000, 96_000), 7.0);
        ticks(server, 1); // drained into an interval that never commits
        api.emit(level, blockIn(96_000, 96_000), 3.0); // still pending

        DimensionPollution restoredDimension = DimensionPollution.load(
                dimension.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        WorldPollution restoredWorld = WorldPollution.load(
                PollutionManager.world(server).save(new CompoundTag(), level.registryAccess()), level.registryAccess());

        helper.assertTrue(restoredDimension.field().get(chunk).local() == AMOUNT, "only the committed amount is saved");
        helper.assertTrue(restoredDimension.pendingEmission(chunk) == 0.0, "pending emissions are not saved");
        helper.assertTrue(!restoredDimension.scheduler().inFlight(), "the in-flight interval is not saved");
        helper.assertTrue(restoredWorld.record().grossEmitted() == grossBefore + AMOUNT, "gross holds committed emissions only");
        dimension.scheduler().abandon(); // leave no interval in flight: other tests seed the field and expect a fresh snapshot
        helper.succeed();
    }
}
