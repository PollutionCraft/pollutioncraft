package io.github.pollutioncraft.gametest;

import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.core.ChunkPollution;
import io.github.pollutioncraft.mod.DimensionPollution;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.PollutionManager;
import io.github.pollutioncraft.mod.WorldPollution;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** {@code /pollution} run through the server's command dispatcher with a capturing source. */
@GameTestHolder("pollutioncraft")
@PrefixGameTestTemplate(false)
public final class DebugCommandGameTests {
    private static final String EMPTY_TEMPLATE = "empty";
    // Storage keys of DimensionPollution / WorldPollution (package-private there).
    private static final String FIELD_KEY = "pollutioncraft_field";
    private static final String WORLD_KEY = "pollutioncraft_world";

    private DebugCommandGameTests() {}

    /** Collects replies; {@code failures} are those the command sent as errors (red). */
    private static final class Capture implements CommandSource {
        final List<String> messages = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            messages.add(component.getString());
            if (component.getStyle().getColor() != null
                    && component.getStyle().getColor().equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.RED))) {
                failures.add(component.getString());
            }
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        String text() {
            return String.join("\n", messages);
        }

        boolean failed() {
            return !failures.isEmpty();
        }
    }

    private static Capture run(ServerLevel level, int permission, int chunkX, int chunkZ, String command) {
        Capture capture = new Capture();
        CommandSourceStack source = new CommandSourceStack(
                capture, new Vec3(chunkX * 16 + 8, 64, chunkZ * 16 + 8), Vec2.ZERO, level, permission, "test",
                Component.literal("test"), level.getServer(), null);
        level.getServer().getCommands().performPrefixedCommand(source, command);
        return capture;
    }

    private static void clearSquare(DimensionPollution dimension, int cx, int cz, int radius) {
        dimension.scheduler().abandon();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                dimension.field().remove(ChunkKey.pack(cx + dx, cz + dz));
            }
        }
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void setStepGetShowsSpreadAndClearEmptiesTheSquare(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DimensionPollution dimension = PollutionManager.dimension(level);
        PollutionCraftMod.config().overrides().clearAll();
        clearSquare(dimension, 90_000, 90_000, 2);

        Capture set = run(level, 2, 0, 0, "pollution set 1000 90000 90000");
        helper.assertTrue(!set.failed(), "set failed: " + set.text());
        helper.assertTrue(set.text().contains("(ran without the player-online gate)"), "set must state the gate bypass: " + set.text());
        helper.assertTrue(dimension.field().get(ChunkKey.pack(90_000, 90_000)).absorbable == 1000.0, "set did not write the field");

        Capture step = run(level, 2, 0, 0, "pollution step 1");
        helper.assertTrue(!step.failed() && step.text().contains("(ran without the player-online gate)"), "step reply: " + step.text());

        ChunkPollution east = dimension.field().get(ChunkKey.pack(90_001, 90_000));
        helper.assertTrue(east != null && east.absorbable > 0, "spread must reach the neighbour");
        Capture get = run(level, 2, 0, 0, "pollution get 90001 90000");
        helper.assertTrue(get.text().contains("absorbable: " + io.github.pollutioncraft.mod.command.PollutionText.number(east.absorbable)),
                "get must show the neighbour's value: " + get.text());
        helper.assertTrue(get.text().contains("dominantSource: none"), "get reply: " + get.text());

        Capture rates = run(level, 2, 0, 0, "pollution rates 90000 90000");
        helper.assertTrue(rates.text().contains("spreadOut: ") && rates.text().contains("spreadFraction: "), "rates reply: " + rates.text());

        Capture clear = run(level, 2, 0, 0, "pollution clear 90000 90000 1");
        helper.assertTrue(!clear.failed() && clear.text().contains("9 chunk(s)") && clear.text().contains("pending emissions are not discarded"),
                "clear reply: " + clear.text());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.assertTrue(dimension.field().get(ChunkKey.pack(90_000 + dx, 90_000 + dz)) == null, "chunk " + dx + "," + dz + " not cleared");
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void defaultChunkIsTheExecutorsChunkAndAbsentChunksReadAsZeros(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DimensionPollution dimension = PollutionManager.dimension(level);
        clearSquare(dimension, 91_000, 91_000, 0);

        Capture set = run(level, 2, 91_000, 91_000, "pollution set 7");
        helper.assertTrue(!set.failed(), "set failed: " + set.text());
        helper.assertTrue(dimension.field().get(ChunkKey.pack(91_000, 91_000)).absorbable == 7.0, "default chunk must be the executor's");

        clearSquare(dimension, 91_000, 91_000, 0);
        Capture get = run(level, 2, 91_000, 91_000, "pollution get");
        helper.assertTrue(!get.failed() && get.text().contains("absorbable: 0"), "absent chunk reads as zeros: " + get.text());
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void boundsAreRefusedWithoutChangingTheField(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DimensionPollution dimension = PollutionManager.dimension(level);
        clearSquare(dimension, 92_000, 92_000, 0);
        int before = dimension.field().size();

        for (String command : List.of(
                "pollution set -1 92000 92000", "pollution set 1000000001 92000 92000",
                "pollution set 5 92000 92000 9", "pollution set 5 92000 92000 -1",
                "pollution clear 92000 92000 9", "pollution step 0", "pollution step 1001")) {
            Capture capture = run(level, 2, 0, 0, command);
            helper.assertTrue(capture.failed(), "must be refused: " + command + " -> " + capture.text());
        }
        helper.assertTrue(dimension.field().size() == before, "refused commands must not touch the field");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void setMidIntervalIsNotOverwrittenByTheAbandonedInterval(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionPollution dimension = PollutionManager.dimension(level);
        PollutionCraftMod.config().overrides().clearAll();
        clearSquare(dimension, 93_000, 93_000, 1);
        long key = ChunkKey.pack(93_000, 93_000);
        dimension.field().getOrCreate(key).absorbable = 100.0;
        int interval = PollutionCraftMod.config().spreadIntervalTicks();

        for (int i = 0; i < interval / 2; i++) {
            PollutionManager.tick(server, 1);
        }
        Capture set = run(level, 2, 0, 0, "pollution set 500 93000 93000");
        helper.assertTrue(!set.failed() && set.text().contains("interval in flight was dropped"), "set reply: " + set.text());
        for (int i = interval / 2; i < interval; i++) {
            PollutionManager.tick(server, 1);
        }

        helper.assertTrue(dimension.field().get(key).absorbable == 500.0,
                "the abandoned interval must not overwrite the set value, got " + dimension.field().get(key).absorbable);
        helper.assertTrue(dimension.field().get(ChunkKey.pack(93_001, 93_000)) == null, "the abandoned interval must not spread");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void aPermissionLevelZeroSourceCannotRunAnySubcommand(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DimensionPollution dimension = PollutionManager.dimension(level);
        PollutionCraftMod.config().overrides().clearAll();
        clearSquare(dimension, 94_000, 94_000, 0);
        dimension.field().getOrCreate(ChunkKey.pack(94_000, 94_000)).absorbable = 42.0;
        int sizeBefore = dimension.field().size();
        long activeBefore = PollutionManager.world(level.getServer()).record().activeTicks();

        for (String command : List.of(
                "pollution get", "pollution rates", "pollution pressure", "pollution set 1", "pollution clear",
                "pollution step", "pollution tune", "pollution tune spreadFraction 0.1", "pollution tune reset",
                "pollution tune reset spreadFraction")) {
            Capture capture = run(level, 0, 94_000, 94_000, command);
            helper.assertTrue(capture.failed(), "must be refused at level 0: " + command + " -> " + capture.text());
        }

        helper.assertTrue(dimension.field().size() == sizeBefore, "field changed");
        helper.assertTrue(dimension.field().get(ChunkKey.pack(94_000, 94_000)).absorbable == 42.0, "value changed");
        helper.assertTrue(PollutionCraftMod.config().overrides().list().isEmpty(), "overrides changed");
        helper.assertTrue(PollutionManager.world(level.getServer()).record().activeTicks() == activeBefore, "step must not have run");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void tuneLeavesTheConfigFileByteIdenticalAndResetRestoresTheValue(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        PollutionCraftMod.config().overrides().clearAll();
        Path file = serverConfigFile(level.getServer());
        byte[] before = Files.readAllBytes(file);
        double baseline = PollutionCraftMod.config().spreadFraction();
        try {
            Capture tune = run(level, 2, 0, 0, "pollution tune spreadFraction 0.1");
            helper.assertTrue(!tune.failed() && tune.text().contains("next interval"), "tune reply: " + tune.text());
            helper.assertTrue(PollutionCraftMod.config().spreadFraction() == 0.1, "override not in effect");
            helper.assertTrue(run(level, 2, 0, 0, "pollution tune").text().contains("spreadFraction: 0.1 (overridden)"), "list must show the override");
            helper.assertTrue(run(level, 2, 0, 0, "pollution rates").text().contains("spreadFraction: 0.1 (overridden)"), "rates must mark the override");
            helper.assertTrue(run(level, 2, 0, 0, "pollution tune spreadFraction 0.25").failed(), "4 x spreadFraction >= 1 must be refused");

            Capture reset = run(level, 2, 0, 0, "pollution tune reset");
            helper.assertTrue(!reset.failed(), "reset failed: " + reset.text());
            helper.assertTrue(PollutionCraftMod.config().spreadFraction() == baseline, "reset must restore the file value");
            helper.assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)), "the config file must be byte-identical");
            helper.succeed();
        } finally {
            PollutionCraftMod.config().overrides().clearAll();
        }
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void pausedDimensionIsRefusedAndShownEverywhere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DimensionPollution original = PollutionManager.dimension(level);
        DimensionPollution paused = unreadableDimension(level);
        level.getDataStorage().set(FIELD_KEY, paused);
        try {
            String expected = "paused: unreadable pollution field in minecraft:overworld";
            for (String command : List.of("pollution set 5", "pollution clear", "pollution step")) {
                Capture capture = run(level, 2, 0, 0, command);
                helper.assertTrue(capture.failed() && capture.text().contains(expected), command + " -> " + capture.text());
            }
            for (String command : List.of("pollution get", "pollution rates")) {
                Capture capture = run(level, 2, 0, 0, command);
                helper.assertTrue(capture.text().contains(expected) && !capture.text().contains("absorbable"), command + " -> " + capture.text());
            }
            Capture pressure = run(level, 2, 0, 0, "pollution pressure");
            helper.assertTrue(pressure.text().contains("activeTicks:") && pressure.text().contains(expected), "pressure -> " + pressure.text());
            helper.succeed();
        } finally {
            level.getDataStorage().set(FIELD_KEY, original);
        }
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void pausedWorldRecordIsRefusedAndShown(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldPollution original = PollutionManager.world(level.getServer());
        CompoundTag garbage = new CompoundTag();
        garbage.putInt("dataVersion", 999);
        level.getServer().overworld().getDataStorage().set(WORLD_KEY, WorldPollution.load(garbage, level.registryAccess()));
        try {
            String expected = "paused: unreadable world record";
            for (String command : List.of("pollution set 5", "pollution clear", "pollution step")) {
                Capture capture = run(level, 2, 0, 0, command);
                helper.assertTrue(capture.failed() && capture.text().contains(expected), command + " -> " + capture.text());
            }
            for (String command : List.of("pollution get", "pollution rates")) {
                Capture capture = run(level, 2, 0, 0, command);
                helper.assertTrue(!capture.failed() && capture.messages.get(0).startsWith(expected) && capture.text().contains("absorbable") == command.endsWith("get"),
                        command + " -> " + capture.text());
            }
            Capture pressure = run(level, 2, 0, 0, "pollution pressure");
            helper.assertTrue(pressure.text().contains(expected) && !pressure.text().contains("activeTicks"), "pressure -> " + pressure.text());
            helper.succeed();
        } finally {
            level.getServer().overworld().getDataStorage().set(WORLD_KEY, original);
        }
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void dominantSourceIsDecodedLikeBlockPosIncludingNegativeY(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DimensionPollution dimension = PollutionManager.dimension(level);
        clearSquare(dimension, 95_000, 95_000, 0);
        ChunkPollution chunk = dimension.field().getOrCreate(ChunkKey.pack(95_000, 95_000));
        chunk.dominantSourcePos = new BlockPos(100, -60, -20).asLong();
        chunk.dominantSourceRate = 2.5;

        Capture get = run(level, 2, 0, 0, "pollution get 95000 95000");

        helper.assertTrue(get.text().contains("dominantSource: 100 -60 -20 (2.5 PU/interval)"), "get reply: " + get.text());
        clearSquare(dimension, 95_000, 95_000, 0);
        helper.succeed();
    }

    private static DimensionPollution unreadableDimension(ServerLevel level) {
        CompoundTag garbage = new CompoundTag();
        garbage.putInt("dataVersion", 999);
        return DimensionPollution.load(garbage, level.registryAccess());
    }

    /** The gametest server keeps server configs in the game's config folder; a normal server in the world. */
    private static Path serverConfigFile(MinecraftServer server) {
        String name = "pollutioncraft-server.toml";
        return Stream.of(
                        server.getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve(name),
                        FMLPaths.CONFIGDIR.get().resolve(name))
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no " + name + " found"));
    }
}
