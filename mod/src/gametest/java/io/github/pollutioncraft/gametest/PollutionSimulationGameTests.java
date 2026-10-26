package io.github.pollutioncraft.gametest;

import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.core.ChunkPollution;
import io.github.pollutioncraft.mod.DimensionPollution;
import io.github.pollutioncraft.mod.PollutionManager;
import io.github.pollutioncraft.mod.WorldPollution;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Storage, activeTicks gate and scheduler against a live server. Each test seeds its own far-away chunks. */
@GameTestHolder("pollutioncraft")
@PrefixGameTestTemplate(false)
public final class PollutionSimulationGameTests {
    private static final String EMPTY_TEMPLATE = "empty";

    private PollutionSimulationGameTests() {}

    /** The gametest world persists between local runs, so clear the chunk and its neighbours first. */
    private static void clearAround(DimensionPollution dimension, int chunkX, int chunkZ) {
        dimension.field().remove(ChunkKey.pack(chunkX, chunkZ));
        dimension.field().remove(ChunkKey.pack(chunkX - 1, chunkZ));
        dimension.field().remove(ChunkKey.pack(chunkX + 1, chunkZ));
        dimension.field().remove(ChunkKey.pack(chunkX, chunkZ - 1));
        dimension.field().remove(ChunkKey.pack(chunkX, chunkZ + 1));
    }

    private static void tickIntervals(MinecraftServer server, int onlinePlayers, int intervals) {
        for (int i = 0; i < intervals * PollutionManager.DEFAULTS.spreadIntervalTicks(); i++) {
            PollutionManager.tick(server, onlinePlayers);
        }
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void nothingChangesWithNoPlayerOnline(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionPollution dimension = PollutionManager.dimension(level);
        clearAround(dimension, 50_000, 50_000);
        long seeded = ChunkKey.pack(50_000, 50_000);
        dimension.field().getOrCreate(seeded).absorbable = 100.0;
        long activeBefore = PollutionManager.world(server).record().activeTicks();

        tickIntervals(server, 0, 3);

        helper.assertTrue(dimension.field().get(ChunkKey.pack(50_001, 50_000)) == null, "neighbour must stay empty");
        helper.assertTrue(dimension.field().get(seeded).absorbable == 100.0, "seeded chunk must be unchanged");
        helper.assertTrue(PollutionManager.world(server).record().activeTicks() == activeBefore, "activeTicks must not advance");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void pollutionSpreadsToNeighboursWhileAPlayerIsOnline(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionPollution dimension = PollutionManager.dimension(level);
        clearAround(dimension, 60_000, 60_000);
        long seeded = ChunkKey.pack(60_000, 60_000);
        dimension.field().getOrCreate(seeded).absorbable = 100.0;
        long activeBefore = PollutionManager.world(server).record().activeTicks();

        tickIntervals(server, 1, 1);

        ChunkPollution east = dimension.field().get(ChunkKey.pack(60_001, 60_000));
        helper.assertTrue(east != null && east.absorbable > 0, "neighbour must hold pollution after one interval");
        helper.assertTrue(
                PollutionManager.api().getLocal(level, 60_001, 60_000) == east.local(), "API read must match the field");
        helper.assertTrue(
                PollutionManager.world(server).record().activeTicks() == activeBefore + PollutionManager.DEFAULTS.spreadIntervalTicks(),
                "activeTicks must advance once per tick with a player online");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void savedFormSurvivesRealSerialisationAndStaysSmall(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        DimensionPollution original = new DimensionPollution();
        for (int i = 0; i < 2500; i++) {
            ChunkPollution c = original.field().getOrCreate(ChunkKey.pack(70_000 + i % 50, 70_000 + i / 50));
            c.absorbable = 1.0 + i * 0.37;
            c.persistent = i * 0.011;
            c.emittedLastInterval = i * 0.5;
            c.dominantSourcePos = 12345L * i;
            c.dominantSourceRate = i * 0.25;
        }

        CompoundTag saved = original.save(new CompoundTag(), level.registryAccess());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(saved, bytes);
        CompoundTag read = NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()), NbtAccounter.unlimitedHeap());
        DimensionPollution restored = DimensionPollution.load(read, level.registryAccess());

        helper.assertTrue(bytes.size() < 200_000, "compressed save is " + bytes.size() + " bytes");
        helper.assertTrue(restored.field().size() == 2500, "all chunks restored");
        for (long key : original.field().sortedKeys()) {
            ChunkPollution a = original.field().get(key);
            ChunkPollution b = restored.field().get(key);
            helper.assertTrue(
                    b != null
                            && a.absorbable == b.absorbable
                            && a.persistent == b.persistent
                            && a.emittedLastInterval == b.emittedLastInterval
                            && a.dominantSourcePos == b.dominantSourcePos
                            && a.dominantSourceRate == b.dominantSourceRate,
                    "chunk " + ChunkKey.x(key) + "," + ChunkKey.z(key) + " differs after reload");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void worldRecordSurvivesSerialisation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldPollution original = new WorldPollution();
        for (int i = 0; i < 40; i++) {
            original.record().onServerTick(1);
        }
        original.record().addGrossEmitted(12.5);

        WorldPollution restored = WorldPollution.load(original.save(new CompoundTag(), level.registryAccess()), level.registryAccess());

        helper.assertTrue(restored.record().activeTicks() == 40, "activeTicks restored");
        helper.assertTrue(restored.record().grossEmitted() == 12.5, "grossEmitted restored");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void corruptOrFutureSavedDataIsSkippedNotFatal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CompoundTag garbage = new CompoundTag();
        garbage.putInt("dataVersion", 999);
        garbage.putString("keys", "not a long array");

        DimensionPollution dimension = DimensionPollution.load(garbage, level.registryAccess());
        WorldPollution world = WorldPollution.load(garbage, level.registryAccess());

        helper.assertTrue(dimension.isPreserved() && throwsIllegalState(dimension::field), "future/corrupt dimension data is kept, not interpreted");
        helper.assertTrue(world.isPreserved() && throwsIllegalState(world::record), "future/corrupt world data is kept, not interpreted");
        helper.succeed();
    }

    // ---- Downgrade protection: an unreadable record is kept and written back unchanged ----------------

    private static boolean throwsIllegalState(java.util.function.Supplier<?> call) {
        try {
            call.get();
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    private static byte[] bytes(CompoundTag tag) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.write(tag, new java.io.DataOutputStream(out));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CompoundTag futureDimensionRecord() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", 7);
        tag.put("keys", new LongArrayTag(new long[] {ChunkKey.pack(1, 2), ChunkKey.pack(3, 4)}));
        tag.put("absorbable", new LongArrayTag(new long[] {Double.doubleToRawLongBits(12.5), Double.doubleToRawLongBits(7.0)}));
        tag.putString("futureColumn", "unknown to this version");
        return tag;
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void futureVersionDimensionRecordSurvivesLoadAndSaveUnchanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CompoundTag original = futureDimensionRecord();

        byte[] before = bytes(original);
        DimensionPollution loaded = DimensionPollution.load(original, level.registryAccess());

        helper.assertTrue(loaded.isPreserved(), "future-version record must be marked preserved");
        helper.assertTrue(
                Arrays.equals(before, bytes(loaded.save(new CompoundTag(), level.registryAccess()))),
                "saved bytes must equal the loaded record");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void missingVersionDimensionRecordSurvivesLoadAndSaveUnchanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CompoundTag original = futureDimensionRecord();
        original.remove("dataVersion");

        byte[] before = bytes(original);
        DimensionPollution loaded = DimensionPollution.load(original, level.registryAccess());

        helper.assertTrue(loaded.isPreserved(), "record without dataVersion must be marked preserved");
        helper.assertTrue(
                Arrays.equals(before, bytes(loaded.save(new CompoundTag(), level.registryAccess()))),
                "saved bytes must equal the loaded record");
        helper.succeed();
    }

    private static CompoundTag worldRecord(int version, long activeTicks) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("dataVersion", version);
        tag.putLong("activeTicks", activeTicks);
        tag.putDouble("grossEmitted", 321.5);
        return tag;
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void unreadableWorldRecordsSurviveLoadAndSaveUnchanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CompoundTag futureVersion = worldRecord(99, 123_456L);
        CompoundTag missingVersion = worldRecord(1, 123_456L);
        missingVersion.remove("dataVersion");
        CompoundTag negativeTicks = worldRecord(1, -5L);
        CompoundTag missingTicks = worldRecord(1, 5L);
        missingTicks.remove("activeTicks");
        CompoundTag wrongType = worldRecord(1, 5L);
        wrongType.putString("grossEmitted", "lots");
        CompoundTag nanEmitted = worldRecord(1, 5L);
        nanEmitted.putDouble("grossEmitted", Double.NaN);

        for (CompoundTag original : new CompoundTag[] {futureVersion, missingVersion, negativeTicks, missingTicks, wrongType, nanEmitted}) {
            byte[] before = bytes(original);
            WorldPollution loaded = WorldPollution.load(original, level.registryAccess());
            helper.assertTrue(loaded.isPreserved(), "must be preserved: " + original);
            helper.assertTrue(
                    Arrays.equals(before, bytes(loaded.save(new CompoundTag(), level.registryAccess()))),
                    "saved bytes must equal the loaded record: " + original);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void readableWorldRecordIsNotPreserved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldPollution loaded = WorldPollution.load(worldRecord(1, 77L), level.registryAccess());

        helper.assertTrue(!loaded.isPreserved(), "a valid record is interpreted normally");
        helper.assertTrue(loaded.record().activeTicks() == 77L, "activeTicks read");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void runningServerNeverResetsAPreservedWorldRecord(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        net.minecraft.world.level.storage.DimensionDataStorage storage = server.overworld().getDataStorage();
        WorldPollution real = PollutionManager.world(server);
        CompoundTag original = worldRecord(99, 123_456L);
        byte[] before = bytes(original);
        WorldPollution preserved = WorldPollution.load(original, level.registryAccess());
        storage.set("pollutioncraft_world", preserved);
        try {
            tickIntervals(server, 1, 2);

            helper.assertTrue(
                    Arrays.equals(before, bytes(preserved.save(new CompoundTag(), level.registryAccess()))),
                    "activeTicks 123456 and the rest of the record must be unchanged after ticking");
            helper.assertTrue(throwsIllegalState(preserved::record), "a preserved record hands out no writable stand-in");
        } finally {
            storage.set("pollutioncraft_world", real);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void preservedDimensionIsNotSimulatedOrChanged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        net.minecraft.world.level.storage.DimensionDataStorage storage = level.getDataStorage();
        DimensionPollution real = PollutionManager.dimension(level);
        CompoundTag original = futureDimensionRecord();
        byte[] before = bytes(original);
        DimensionPollution preserved = DimensionPollution.load(original, level.registryAccess());
        storage.set("pollutioncraft_field", preserved);
        try {
            tickIntervals(server, 1, 2);

            helper.assertTrue(throwsIllegalState(preserved::field), "a preserved dimension hands out no writable stand-in");
            helper.assertTrue(
                    Arrays.equals(before, bytes(preserved.save(new CompoundTag(), level.registryAccess()))),
                    "record must stay byte-identical");
        } finally {
            storage.set("pollutioncraft_field", real);
        }
        helper.succeed();
    }

    // ---- A data file Minecraft cannot parse must never be replaced by a fresh record -----------------

    private static DimensionDataStorage storageIn(java.nio.file.Path dir, ServerLevel level) {
        return new DimensionDataStorage(dir.toFile(), DataFixers.getDataFixer(), level.registryAccess());
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void unparsableWorldFileIsNeverOverwritten(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        java.nio.file.Path dir = Files.createTempDirectory("pollutioncraft-world");
        java.nio.file.Path file = dir.resolve("pollutioncraft_world.dat");
        byte[] garbage = "this is not gzip nbt, a truncated save".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, garbage);
        DimensionDataStorage storage = storageIn(dir, level);

        WorldPollution world = PollutionManager.loadWorld(storage, file);
        DimensionDataStorage realStorage = level.getServer().overworld().getDataStorage();
        WorldPollution real = PollutionManager.world(level.getServer());
        realStorage.set("pollutioncraft_world", world);
        try {
            tickIntervals(level.getServer(), 1, 2); // a player is online: the production path runs
        } finally {
            realStorage.set("pollutioncraft_world", real);
        }
        world.setDirty();
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete(); // saves are written on an IO worker

        helper.assertTrue(world.isPreserved() && !world.isDirty(), "an existing but unparsable file must give a paused, never-dirty record");
        helper.assertTrue(Arrays.equals(garbage, Files.readAllBytes(file)), "the file must be byte-identical after a forced save");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void unparsableDimensionFileIsNeverOverwritten(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        java.nio.file.Path dir = Files.createTempDirectory("pollutioncraft-dim");
        java.nio.file.Path file = dir.resolve("pollutioncraft_field.dat");
        byte[] garbage = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        Files.write(file, garbage);
        DimensionDataStorage storage = storageIn(dir, level);

        DimensionPollution dimension = PollutionManager.loadDimension(storage, file);
        dimension.setDirty();
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete(); // saves are written on an IO worker

        helper.assertTrue(dimension.isPreserved(), "an existing but unparsable file must give a paused record");
        helper.assertTrue(Arrays.equals(garbage, Files.readAllBytes(file)), "the file must be byte-identical after a forced save");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void missingFilesStartFreshAndAreWritten(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        java.nio.file.Path dir = Files.createTempDirectory("pollutioncraft-fresh");
        DimensionDataStorage storage = storageIn(dir, level);

        WorldPollution world = PollutionManager.loadWorld(storage, dir.resolve("pollutioncraft_world.dat"));
        DimensionPollution dimension = PollutionManager.loadDimension(storage, dir.resolve("pollutioncraft_field.dat"));
        helper.assertTrue(!world.isPreserved() && !dimension.isPreserved(), "no file yet means a normal fresh record");
        world.record().onServerTick(1);
        world.setDirty();
        dimension.setDirty();
        storage.save();
        IOUtilities.waitUntilIOWorkerComplete(); // saves are written on an IO worker

        helper.assertTrue(Files.exists(dir.resolve("pollutioncraft_world.dat")), "fresh world record is saved");
        helper.assertTrue(Files.exists(dir.resolve("pollutioncraft_field.dat")), "fresh field is saved");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void dataFilePathMatchesWhereMinecraftSaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        PollutionManager.world(server).setDirty();
        server.overworld().getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();

        helper.assertTrue(
                Files.exists(PollutionManager.dataFile(server.overworld(), "pollutioncraft_world")),
                "computed data file path must be the file Minecraft wrote");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void pausedRecordsReadAsZero(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionDataStorage dimStorage = level.getDataStorage();
        DimensionDataStorage worldStorage = server.overworld().getDataStorage();
        DimensionPollution realDim = PollutionManager.dimension(level);
        WorldPollution realWorld = PollutionManager.world(server);
        dimStorage.set("pollutioncraft_field", DimensionPollution.load(futureDimensionRecord(), level.registryAccess()));
        worldStorage.set("pollutioncraft_world", WorldPollution.load(worldRecord(99, 5L), level.registryAccess()));
        try {
            helper.assertTrue(PollutionManager.api().getLocal(level, 1, 2) == 0.0, "paused dimension reads 0");
            helper.assertTrue(PollutionManager.api().getPressure(server) == 0.0, "paused world reads 0");
        } finally {
            dimStorage.set("pollutioncraft_field", realDim);
            worldStorage.set("pollutioncraft_world", realWorld);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void stepNowReportsWhyItRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionDataStorage dimStorage = level.getDataStorage();
        DimensionDataStorage worldStorage = server.overworld().getDataStorage();
        DimensionPollution realDim = PollutionManager.dimension(level);
        WorldPollution realWorld = PollutionManager.world(server);
        try {
            helper.assertTrue(PollutionManager.stepNow(level, 1) == PollutionManager.StepResult.RAN, "normal run");

            dimStorage.set("pollutioncraft_field", DimensionPollution.load(futureDimensionRecord(), level.registryAccess()));
            helper.assertTrue(
                    PollutionManager.stepNow(level, 1) == PollutionManager.StepResult.PAUSED_DIMENSION_RECORD, "paused dimension");
            dimStorage.set("pollutioncraft_field", realDim);

            worldStorage.set("pollutioncraft_world", WorldPollution.load(worldRecord(99, 5L), level.registryAccess()));
            helper.assertTrue(
                    PollutionManager.stepNow(level, 1) == PollutionManager.StepResult.PAUSED_WORLD_RECORD, "paused world");
        } finally {
            dimStorage.set("pollutioncraft_field", realDim);
            worldStorage.set("pollutioncraft_world", realWorld);
        }
        helper.succeed();
    }
}
