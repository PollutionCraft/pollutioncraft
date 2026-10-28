package io.github.pollutioncraft.gametest;

import io.github.pollutioncraft.api.PollutionApi;
import io.github.pollutioncraft.mod.emitter.EmitterEngine;
import io.github.pollutioncraft.mod.emitter.EmitterRules;
import io.github.pollutioncraft.mod.emitter.EntityEmitters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.MinecartFurnace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bound and the seams of the entity emitter (task 032), on an {@link EntityEmitters} of its own with a recording API,
 * so nothing here touches the pollution field. The carts are real furnace minecarts that are never added to the level.
 */
@GameTestHolder("pollutioncraft")
@PrefixGameTestTemplate(false)
public final class EntityEmitterBoundGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("EntityEmitterCost");
    private static final String EMPTY_TEMPLATE = "empty";

    private EntityEmitterBoundGameTests() {}

    /** Records the position of every emit. */
    private static final class RecordingApi implements PollutionApi {
        final List<BlockPos> emitted = new ArrayList<>();
        final List<Double> amounts = new ArrayList<>();

        @Override
        public void emit(ServerLevel level, BlockPos pos, double amount) {
            emitted.add(pos.immutable());
            amounts.add(amount);
        }

        @Override
        public double getLocal(ServerLevel level, int chunkX, int chunkZ) {
            return 0;
        }

        @Override
        public double getPressure(MinecraftServer server) {
            return 0;
        }

        @Override
        public boolean isAggressive(ServerLevel level, int chunkX, int chunkZ) {
            return false;
        }
    }

    /** A cart at x = {@code index}, lit: one real tick gives it the "has fuel" flag a cart in the world has. */
    private static MinecartFurnace litCart(ServerLevel level, int index, int fuel) {
        MinecartFurnace cart = new MinecartFurnace(level, index + 0.5, 100, 0.5);
        CompoundTag tag = new CompoundTag();
        cart.saveWithoutId(tag);
        tag.putShort("Fuel", (short) fuel);
        cart.load(tag);
        cart.setNoGravity(true);
        cart.tick();
        return cart;
    }

    private static boolean lit(MinecartFurnace cart) {
        return cart.getDefaultDisplayBlockState().getValue(BlockStateProperties.LIT);
    }

    private static EntityEmitters emitters(RecordingApi api, EntityEmitters.TickCheck check, List<String> log) {
        return new EntityEmitters(api, check, EntityEmitters.MAX_TRACKED_PER_LEVEL, log::add);
    }

    // ---- the bound ---------------------------------------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void overTheBoundOnlyTheLowestUuidsEmitAndOneWarningIsLogged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RecordingApi api = new RecordingApi();
        List<String> log = new ArrayList<>();
        EntityEmitters emitters = emitters(api, (l, p) -> true, log);
        EmitterRules rules = EmitterEngine.currentRules();
        List<MinecartFurnace> carts = new ArrayList<>();
        for (int i = 0; i < EntityEmitters.MAX_TRACKED_PER_LEVEL + 76; i++) {
            MinecartFurnace cart = litCart(level, i, 3600);
            helper.assertTrue(lit(cart), "cart " + i + " is lit after one tick");
            carts.add(cart);
            emitters.onJoin(level, cart);
        }

        emitters.emitAll(level, rules, rate -> rate);

        helper.assertTrue(api.emitted.size() == EntityEmitters.MAX_TRACKED_PER_LEVEL,
                "exactly " + EntityEmitters.MAX_TRACKED_PER_LEVEL + " carts emit, got " + api.emitted.size());
        helper.assertTrue(log.size() == 1, "exactly one warning, got " + log);
        Set<Integer> expected = new HashSet<>();
        carts.stream().sorted(Comparator.comparing(MinecartFurnace::getUUID)).limit(EntityEmitters.MAX_TRACKED_PER_LEVEL)
                .forEach(cart -> expected.add(cart.blockPosition().getX()));
        Set<Integer> actual = new HashSet<>();
        api.emitted.forEach(pos -> actual.add(pos.getX()));
        helper.assertTrue(actual.equals(expected), "the emitting carts are the ones with the lowest UUIDs");

        emitters.emitAll(level, rules, rate -> rate);
        helper.assertTrue(api.emitted.size() == 2 * EntityEmitters.MAX_TRACKED_PER_LEVEL, "the second interval emits for the same number");
        helper.assertTrue(log.size() == 1, "the warning is throttled to once per period, got " + log.size());
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void costOfTheIntervalReadIsReportedForFiftyCartsAndAtTheBound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EmitterRules rules = EmitterEngine.currentRules();
        for (int count : new int[] {50, EntityEmitters.MAX_TRACKED_PER_LEVEL}) {
            RecordingApi api = new RecordingApi();
            EntityEmitters emitters = emitters(api, (l, p) -> true, new ArrayList<>());
            for (int i = 0; i < count; i++) {
                emitters.onJoin(level, litCart(level, i, 3600));
            }
            for (int warm = 0; warm < 200; warm++) {
                emitters.emitAll(level, rules, rate -> rate);
            }
            int rounds = 500;
            api.emitted.clear();
            api.amounts.clear();
            long start = System.nanoTime();
            for (int r = 0; r < rounds; r++) {
                emitters.emitAll(level, rules, rate -> rate);
                api.emitted.clear();
                api.amounts.clear();
            }
            double perRead = (System.nanoTime() - start) / (double) rounds;
            LOGGER.warn("{}", String.format("COST carts=%d perIntervalRead=%.1f us (%.2f us per cart); not asserted", count, perRead / 1000.0, perRead / 1000.0 / count));
        }
        helper.succeed();
    }

    // ---- seams: entity ticking, removal -------------------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void aCartWhereEntitiesDoNotTickEmitsNothingUntilTheyDo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RecordingApi api = new RecordingApi();
        boolean[] ticking = {false};
        EntityEmitters emitters = emitters(api, (l, p) -> ticking[0], new ArrayList<>());
        EmitterRules rules = EmitterEngine.currentRules();
        MinecartFurnace cart = litCart(level, 1, 3600);
        helper.assertTrue(lit(cart), "precondition: the cart is lit");
        emitters.onJoin(level, cart);

        emitters.emitAll(level, rules, rate -> rate);
        helper.assertTrue(api.emitted.isEmpty(), "nothing emits where entities do not tick");
        helper.assertTrue(emitters.trackedCount(level) == 1, "the cart stays tracked");

        ticking[0] = true;
        emitters.emitAll(level, rules, rate -> rate);
        helper.assertTrue(api.emitted.size() == 1, "it emits once entities tick, got " + api.emitted.size());
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 600)
    public static void anUnlitOrRemovedCartEmitsNothingAndARemovedOneIsDropped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RecordingApi api = new RecordingApi();
        EntityEmitters emitters = emitters(api, (l, p) -> true, new ArrayList<>());
        EmitterRules rules = EmitterEngine.currentRules();
        MinecartFurnace unlit = litCart(level, 1, 0);
        MinecartFurnace removed = litCart(level, 2, 3600);
        helper.assertTrue(!lit(unlit) && lit(removed), "preconditions: one cart unlit, one lit");
        emitters.onJoin(level, unlit);
        emitters.onJoin(level, removed);
        removed.discard();

        emitters.emitAll(level, rules, rate -> rate);

        helper.assertTrue(api.emitted.isEmpty(), "neither emits, got " + api.emitted);
        helper.assertTrue(emitters.trackedCount(level) == 1, "the removed cart is dropped when read, tracked " + emitters.trackedCount(level));
        emitters.onLeave(level, unlit);
        helper.assertTrue(emitters.trackedCount(level) == 0, "a leave event removes the cart");
        helper.succeed();
    }
}
