package io.github.pollutioncraft.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.mod.DimensionPollution;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.PollutionManager;
import io.github.pollutioncraft.mod.overlay.OverlayPayload;
import io.github.pollutioncraft.mod.overlay.OverlaySender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The dev overlay (task 030) on the server side. Players are {@link FakePlayer} subclasses that were never added
 * to the player list or the level, so {@code getPlayerCount()} stays 0 (the simulation gate and every concurrent
 * test are unaffected) and no op entry is ever written: their permission level is a field. The sender's seams
 * (players source and sink) are installed, used and reset inside one synchronous block per test.
 */
@GameTestHolder("pollutioncraft")
@PrefixGameTestTemplate(false)
public final class OverlayGameTests {
    private static final String EMPTY_TEMPLATE = "empty";
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    private OverlayGameTests() {}

    /** A server player outside every list, with a permission level that is just a field. */
    private static final class TestPlayer extends FakePlayer {
        private int permissionLevel;

        TestPlayer(ServerLevel level, int permissionLevel, int chunkX, int chunkZ) {
            super(level, new GameProfile(UUID.randomUUID(), "overlay-test"));
            this.permissionLevel = permissionLevel;
            moveTo(chunkX * 16 + 8.5, 64, chunkZ * 16 + 8.5);
        }

        @Override
        protected int getPermissionLevel() {
            return permissionLevel;
        }
    }

    /** Everything the sender sent through the seam. */
    private record Sent(ServerPlayer player, OverlayPayload payload) {}

    private static final class Rig {
        final List<ServerPlayer> players = new ArrayList<>();
        final List<Sent> sent = new ArrayList<>();
        final ServerLevel level;

        Rig(ServerLevel level) {
            this.level = level;
        }

        Function<ServerLevel, Collection<ServerPlayer>> source() {
            return l -> l == level ? players : List.of();
        }

        BiConsumer<ServerPlayer, OverlayPayload> sink() {
            return (player, payload) -> sent.add(new Sent(player, payload));
        }
    }

    private static void interval(MinecraftServer server) {
        for (int i = 0; i < PollutionCraftMod.config().spreadIntervalTicks(); i++) {
            PollutionManager.tick(server, 1);
        }
    }

    private static DimensionPollution fresh(ServerLevel level, int chunkX, int chunkZ) {
        PollutionCraftMod.config().overrides().clearAll();
        DimensionPollution dimension = PollutionManager.dimension(level);
        dimension.scheduler().abandon();
        dimension.field().remove(ChunkKey.pack(chunkX, chunkZ));
        return dimension;
    }

    // ---- the payload codec (plan test 3) --------------------------------------------------------

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void aVersionOnePayloadSurvivesEncodeAndDecode(GameTestHelper helper) {
        OverlayPayload payload = new OverlayPayload(1, OVERWORLD, 3, -4, 20, List.of("absorbable: 5", "persistent: 0"));
        RegistryFriendlyByteBuf buf = buffer(helper);

        OverlayPayload.CODEC.encode(buf, payload);
        OverlayPayload decoded = OverlayPayload.CODEC.decode(buf);

        helper.assertTrue(decoded.equals(payload), "round trip gave " + decoded);
        helper.assertTrue(!decoded.isUnsupported() && buf.readableBytes() == 0, "all bytes consumed");
        helper.succeed();
    }

    /** The bytes of a version-1 body, built by hand so the tests do not depend on the encoder. */
    private static ByteBuf handBuiltV1(GameTestHelper helper, int lineCount, int lineLength) {
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        body.writeResourceLocation(OVERWORLD);
        body.writeVarInt(1);
        body.writeVarInt(2);
        body.writeVarInt(20);
        body.writeVarInt(lineCount);
        for (int i = 0; i < lineCount; i++) {
            body.writeUtf("x".repeat(lineLength), 32767);
        }
        FriendlyByteBuf whole = new FriendlyByteBuf(Unpooled.buffer());
        whole.writeVarInt(1);
        whole.writeVarInt(body.readableBytes());
        whole.writeBytes(body);
        return whole;
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void anUnknownVersionDecodesToUnsupportedAndStopsExactlyAtItsEnd(GameTestHelper helper) {
        RegistryFriendlyByteBuf buf = buffer(helper);
        buf.writeVarInt(2);
        buf.writeVarInt(7);
        buf.writeBytes(new byte[] {9, 9, 9, 9, 9, 9, 9}); // a body this client has never heard of
        buf.writeByte(0x5A); // the next thing in the stream

        OverlayPayload decoded = OverlayPayload.CODEC.decode(buf);

        helper.assertTrue(decoded.isUnsupported() && decoded.protocolVersion() == 2, "unsupported marker for version 2");
        helper.assertTrue(buf.readableBytes() == 1 && buf.readByte() == 0x5A, "the reader stopped exactly at the end of the payload");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void aHandBuiltVersionOneWithinTheBoundsDecodes(GameTestHelper helper) {
        RegistryFriendlyByteBuf buf = buffer(helper);
        buf.writeBytes(handBuiltV1(helper, 32, 256));

        OverlayPayload decoded = OverlayPayload.CODEC.decode(buf);

        helper.assertTrue(decoded.lines().size() == 32 && decoded.lines().get(0).length() == 256, "the bounds themselves are allowed");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void tooManyLinesOrTooLongALineThrows(GameTestHelper helper) {
        for (int[] bad : new int[][] {{33, 5}, {2, 257}}) {
            RegistryFriendlyByteBuf buf = buffer(helper);
            buf.writeBytes(handBuiltV1(helper, bad[0], bad[1]));
            boolean threw = false;
            try {
                OverlayPayload.CODEC.decode(buf);
            } catch (RuntimeException expected) {
                threw = true;
            }
            helper.assertTrue(threw, "decode must reject " + bad[0] + " lines of " + bad[1] + " characters");
        }
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            tooMany.add("x");
        }
        boolean encodeThrew = false;
        try {
            OverlayPayload.CODEC.encode(buffer(helper), new OverlayPayload(1, OVERWORLD, 0, 0, 20, tooMany));
        } catch (RuntimeException expected) {
            encodeThrew = true;
        }
        helper.assertTrue(encodeThrew, "encode must refuse to build an over-bound payload");
        helper.succeed();
    }

    // ---- the sender (plan test 4) ------------------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE)
    public static void withTheOverlayOffNothingIsBuiltOrSent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        fresh(level, 70_000, 70_000);
        Rig rig = new Rig(level);
        rig.players.add(new TestPlayer(level, 2, 70_000, 70_000));
        OverlaySender sender = OverlaySender.global();
        int builtBefore = sender.builtCount();
        OverlaySender.install(rig.source(), rig.sink());
        try {
            interval(server);
            PollutionManager.stepNow(level, 2);
        } finally {
            OverlaySender.reset();
        }

        helper.assertTrue(sender.builtCount() == builtBefore, "no payload was built");
        helper.assertTrue(rig.sent.isEmpty(), "none was sent");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void aLevelTwoPlayerWithTheOverlayOnGetsOnePayloadPerCommittedInterval(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        DimensionPollution dimension = fresh(level, 70_100, 70_100);
        dimension.field().getOrCreate(ChunkKey.pack(70_100, 70_100)).absorbable = 5.0;
        Rig rig = new Rig(level);
        TestPlayer player = new TestPlayer(level, 2, 70_100, 70_100);
        rig.players.add(player);
        OverlaySender sender = OverlaySender.global();
        sender.subscribers().add(player.getUUID());
        int builtBefore = sender.builtCount();
        OverlaySender.install(rig.source(), rig.sink());
        try {
            int ticks = PollutionCraftMod.config().spreadIntervalTicks();
            for (int i = 0; i < ticks - 1; i++) {
                PollutionManager.tick(server, 1);
            }
            helper.assertTrue(rig.sent.isEmpty(), "nothing before the interval is applied");
            PollutionManager.tick(server, 1);
            helper.assertTrue(rig.sent.size() == 1 && sender.builtCount() == builtBefore + 1, "one built and sent at the commit");
            interval(server);
        } finally {
            OverlaySender.reset();
            sender.subscribers().remove(player.getUUID());
        }

        helper.assertTrue(rig.sent.size() == 2, "one more for the second interval, got " + rig.sent.size());
        OverlayPayload payload = rig.sent.get(0).payload();
        helper.assertTrue(rig.sent.get(0).player() == player, "to the subscribed player");
        helper.assertTrue(payload.protocolVersion() == 1 && payload.dimension().equals(OVERWORLD), "version and dimension");
        helper.assertTrue(payload.chunkX() == 70_100 && payload.chunkZ() == 70_100, "describes the player's chunk");
        helper.assertTrue(payload.intervalTicks() == PollutionCraftMod.config().spreadIntervalTicks(), "interval length in force");
        helper.assertTrue(payload.lines().contains("absorbable: 5"), "the committed value: " + payload.lines());
        helper.assertTrue(payload.lines().contains("pressure: 0 (until task 016)"), "pressure label");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void aLevelZeroPlayerWithTheOverlayOnGetsNothingBuilt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        fresh(level, 70_200, 70_200);
        Rig rig = new Rig(level);
        TestPlayer player = new TestPlayer(level, 0, 70_200, 70_200);
        rig.players.add(player);
        OverlaySender sender = OverlaySender.global();
        sender.subscribers().add(player.getUUID());
        int builtBefore = sender.builtCount();
        OverlaySender.install(rig.source(), rig.sink());
        try {
            interval(server);
        } finally {
            OverlaySender.reset();
            sender.subscribers().remove(player.getUUID());
        }

        helper.assertTrue(sender.builtCount() == builtBefore && rig.sent.isEmpty(), "level 0 receives nothing and nothing is built");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void aDeoppedPlayerStopsReceivingAtOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        fresh(level, 70_300, 70_300);
        Rig rig = new Rig(level);
        TestPlayer player = new TestPlayer(level, 2, 70_300, 70_300);
        rig.players.add(player);
        OverlaySender sender = OverlaySender.global();
        sender.subscribers().add(player.getUUID());
        OverlaySender.install(rig.source(), rig.sink());
        try {
            interval(server);
            player.permissionLevel = 0;
            interval(server);
        } finally {
            OverlaySender.reset();
            sender.subscribers().remove(player.getUUID());
        }

        helper.assertTrue(rig.sent.size() == 1, "only the interval before the de-op, got " + rig.sent.size());
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void stepNowOfThreeSendsExactlyOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        fresh(level, 70_400, 70_400);
        Rig rig = new Rig(level);
        TestPlayer player = new TestPlayer(level, 2, 70_400, 70_400);
        rig.players.add(player);
        OverlaySender sender = OverlaySender.global();
        sender.subscribers().add(player.getUUID());
        int builtBefore = sender.builtCount();
        OverlaySender.install(rig.source(), rig.sink());
        try {
            PollutionManager.stepNow(level, 3);
        } finally {
            OverlaySender.reset();
            sender.subscribers().remove(player.getUUID());
        }

        helper.assertTrue(rig.sent.size() == 1 && sender.builtCount() == builtBefore + 1, "one payload for the whole step, got " + rig.sent.size());
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void installingTwiceThrowsAndResetAllowsAgain(GameTestHelper helper) {
        Rig rig = new Rig(helper.getLevel());
        OverlaySender.install(rig.source(), rig.sink());
        try {
            boolean threw = false;
            try {
                OverlaySender.install(rig.source(), rig.sink());
            } catch (IllegalStateException expected) {
                threw = true;
            }
            helper.assertTrue(threw, "a second install must throw");
        } finally {
            OverlaySender.reset();
        }
        OverlaySender.install(rig.source(), rig.sink()); // allowed again
        OverlaySender.reset();
        helper.succeed();
    }

    // ---- /pollution overlay (plan test 5) ---------------------------------------------------------

    private static int run(MinecraftServer server, CommandSourceStack source, String command) {
        try {
            return server.getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException refused) {
            return -1;
        }
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void overlayOnAndOffToggleTheExecutingPlayerAndOnSendsOnceAtOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        fresh(level, 70_500, 70_500);
        Rig rig = new Rig(level);
        TestPlayer player = new TestPlayer(level, 2, 70_500, 70_500);
        rig.players.add(player);
        OverlaySender sender = OverlaySender.global();
        int builtBefore = sender.builtCount();
        OverlaySender.install(rig.source(), rig.sink());
        try {
            helper.assertTrue(run(server, player.createCommandSourceStack(), "pollution overlay on") == 1, "on succeeds");
            helper.assertTrue(sender.subscribers().contains(player.getUUID()), "subscribed");
            helper.assertTrue(rig.sent.size() == 1 && sender.builtCount() == builtBefore + 1, "exactly one payload straight away");
            helper.assertTrue(rig.sent.get(0).payload().chunkX() == 70_500, "describing the player's chunk");

            helper.assertTrue(run(server, player.createCommandSourceStack(), "pollution overlay off") == 1, "off succeeds");
            helper.assertTrue(!sender.subscribers().contains(player.getUUID()), "unsubscribed");
            helper.assertTrue(rig.sent.size() == 1, "off sends nothing");
        } finally {
            OverlaySender.reset();
            sender.subscribers().remove(player.getUUID());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void aLevelZeroPlayerIsRefusedAndTheConsoleGetsAFailure(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        Rig rig = new Rig(level);
        TestPlayer player = new TestPlayer(level, 0, 70_600, 70_600);
        rig.players.add(player);
        OverlaySender sender = OverlaySender.global();
        int builtBefore = sender.builtCount();
        OverlaySender.install(rig.source(), rig.sink());
        try {
            int level0 = run(server, player.createCommandSourceStack(), "pollution overlay on");
            helper.assertTrue(level0 != 1 && !sender.subscribers().contains(player.getUUID()), "level 0 is refused");

            int console = run(server, server.createCommandSourceStack(), "pollution overlay on");
            helper.assertTrue(console == 0, "the console gets a failure reply, got " + console);
            helper.assertTrue(rig.sent.isEmpty() && sender.builtCount() == builtBefore, "nothing built or sent for either");
        } finally {
            OverlaySender.reset();
            sender.subscribers().remove(player.getUUID());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE)
    public static void loggingOutClearsTheToggle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        TestPlayer player = new TestPlayer(level, 2, 70_700, 70_700);
        OverlaySender sender = OverlaySender.global();
        Rig rig = new Rig(level);
        OverlaySender.install(rig.source(), rig.sink());
        try {
            run(server, player.createCommandSourceStack(), "pollution overlay on");
            helper.assertTrue(sender.subscribers().contains(player.getUUID()), "precondition: subscribed");

            // A real logout needs a connection; the handler only reads the player, so the event is posted by hand.
            NeoForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(player));

            helper.assertTrue(!sender.subscribers().contains(player.getUUID()), "re-joining starts with the overlay off");
        } finally {
            OverlaySender.reset();
            sender.subscribers().remove(player.getUUID());
        }
        helper.succeed();
    }

    // ---- measurement (plan test 7): reported, not a gate ------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE)
    public static void measureTheSenderCost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        fresh(level, 71_000, 71_000);
        OverlaySender sender = OverlaySender.global();
        Rig rig = new Rig(level);
        for (int i = 0; i < 8; i++) {
            TestPlayer player = new TestPlayer(level, 2, 71_000 + i, 71_000);
            rig.players.add(player);
        }
        int[] sent = {0};
        OverlaySender.install(rig.source(), (player, payload) -> sent[0]++);
        double[] microsPerInterval = new double[3];
        double encodeMicros;
        try {
            int[] counts = {0, 1, 8};
            for (int c = 0; c < counts.length; c++) {
                for (int i = 0; i < counts[c]; i++) {
                    sender.subscribers().add(rig.players.get(i).getUUID());
                }
                for (int i = 0; i < 20_000; i++) {
                    OverlaySender.afterInterval(level); // warm-up
                }
                int calls = 100_000;
                long begin = System.nanoTime();
                for (int i = 0; i < calls; i++) {
                    OverlaySender.afterInterval(level);
                }
                microsPerInterval[c] = (System.nanoTime() - begin) / 1000.0 / calls;
                rig.players.forEach(p -> sender.subscribers().remove(p.getUUID()));
            }
            OverlayPayload sample = new OverlayPayload(1, OVERWORLD, 3, -4, 20, List.of(
                    "chunk 3, -4", "absorbable: 12.5", "persistent: 3", "dominantSource: 5 64 7 (2 PU/interval)", "emittedLastInterval: 2",
                    "pending: 0", "generated: 2", "spreadIn: 1.5", "spreadOut: 4 (per neighbour: -x 1, +x 1, -z 1, +z 1)", "absorbed: 0.25",
                    "decayed: 0.125", "pressure: 0 (until task 016)", "aggressive: n/a (until task 016)"));
            for (int i = 0; i < 20_000; i++) {
                OverlayPayload.CODEC.encode(buffer(helper), sample);
            }
            long begin = System.nanoTime();
            for (int i = 0; i < 50_000; i++) {
                OverlayPayload.CODEC.encode(buffer(helper), sample);
            }
            encodeMicros = (System.nanoTime() - begin) / 1000.0 / 50_000;
        } finally {
            OverlaySender.reset();
            rig.players.forEach(p -> sender.subscribers().remove(p.getUUID()));
        }
        int ticks = PollutionCraftMod.config().spreadIntervalTicks();
        System.out.printf("PERF 030 sender per interval: 0 subscribers %.3f us, 1 subscriber %.1f us, 8 subscribers %.1f us "
                + "(= %.5f ms/tick at %d ticks per interval); encode of a 13-line payload %.1f us each (x8 = %.1f us)%n",
                microsPerInterval[0], microsPerInterval[1], microsPerInterval[2], microsPerInterval[2] / 1000 / ticks, ticks, encodeMicros, 8 * encodeMicros);
        helper.assertTrue(sent[0] > 0, "the measured path really built and sent payloads");
        helper.succeed();
    }
}
