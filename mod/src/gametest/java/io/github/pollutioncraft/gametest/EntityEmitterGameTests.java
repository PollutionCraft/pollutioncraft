package io.github.pollutioncraft.gametest;

import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.mod.DimensionPollution;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.PollutionManager;
import io.github.pollutioncraft.mod.emitter.EmitterEngine;
import io.github.pollutioncraft.mod.emitter.EmitterRules;
import io.github.pollutioncraft.mod.emitter.MappingEntry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.MinecartFurnace;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The furnace minecart emitter (task 032) against a live server, using the shipped mapping. Expected rates are read
 * from the installed rules, never written here. The GameTest world has villages with furnaces and campfires, so every
 * test picks chunks that hold no tracked mapped block (the 029 precondition) and fails if it cannot find them. These
 * tests wait for real entity ticks, so they run in their own batch, away from the exact {@code grossEmitted}
 * assertions of the other emitter tests.
 */
@GameTestHolder("pollutioncraft")
@PrefixGameTestTemplate(false)
public final class EntityEmitterGameTests {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("EntityEmitterCost");
    private static final String EMPTY_TEMPLATE = "empty";
    private static final String BATCH = "entity_emitter";
    private static final EmitterEngine ENGINE = EmitterEngine.global();
    private static final int CANDIDATES = 12;
    private static final int FUEL = 3600;
    /** Holds a chunk at ticket level 33 (loaded, entities not ticking) or, with a radius of 2, at level 31 (entity-ticking). */
    private static final TicketType<ChunkPos> HOLD =
            TicketType.create("pollutioncraft_entity_hold", java.util.Comparator.comparingLong(ChunkPos::toLong), 600);

    private EntityEmitterGameTests() {}

    private static void interval(MinecraftServer server) {
        for (int i = 0; i < PollutionCraftMod.config().spreadIntervalTicks(); i++) {
            PollutionManager.tick(server, 1);
        }
    }

    private static double rate() {
        return EmitterEngine.currentRules().entityRate(EntityType.FURNACE_MINECART);
    }

    private static double local(ServerLevel level, ChunkPos chunk) {
        return PollutionManager.api().getLocal(level, chunk.x, chunk.z);
    }

    private static BlockPos centre(ChunkPos chunk) {
        return new BlockPos(chunk.getMinBlockX() + 5, 100, chunk.getMinBlockZ() + 5);
    }

    /** Forces and returns {@code count} chunks of {@code level} with no tracked mapped block, or fails the test. */
    private static List<ChunkPos> cleanChunks(GameTestHelper helper, ServerLevel level, int firstX, int z, int count) {
        List<ChunkPos> clean = new ArrayList<>();
        for (int i = 0; i < CANDIDATES && clean.size() < count; i++) {
            ChunkPos candidate = new ChunkPos(firstX + 3 * i, z);
            level.setChunkForced(candidate.x, candidate.z, true);
            if (ENGINE.trackedIn(level, ChunkKey.pack(candidate.x, candidate.z)).length == 0) {
                clean.add(candidate);
            } else {
                level.setChunkForced(candidate.x, candidate.z, false);
            }
        }
        if (clean.size() < count) {
            clean.forEach(c -> level.setChunkForced(c.x, c.z, false));
            helper.fail("fewer than " + count + " chunks without a mapped block among " + CANDIDATES + " candidates from x=" + firstX);
        }
        return clean;
    }

    private static void release(ServerLevel level, List<ChunkPos> chunks) {
        chunks.forEach(c -> level.setChunkForced(c.x, c.z, false));
    }

    private static boolean entityTicking(ServerLevel level, List<ChunkPos> chunks) {
        return chunks.stream().allMatch(c -> level.isPositionEntityTicking(centre(c)));
    }

    /** Fresh state around the chunks: nothing pending or in flight, no override, the field clear of them. */
    private static void reset(ServerLevel level, ChunkPos... chunks) {
        PollutionCraftMod.config().overrides().clearAll();
        DimensionPollution dimension = PollutionManager.dimension(level);
        dimension.scheduler().abandon();
        interval(level.getServer());
        for (ChunkPos chunk : chunks) {
            for (int[] d : new int[][] {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                dimension.field().remove(ChunkKey.pack(chunk.x + d[0], chunk.z + d[1]));
            }
        }
    }

    private static MinecartFurnace spawn(ServerLevel level, BlockPos pos, int fuel) {
        MinecartFurnace cart = new MinecartFurnace(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        setFuel(cart, fuel);
        level.addFreshEntity(cart);
        return cart;
    }

    /** The fuel counter is private; it loads from the entity's saved data like a cart read from a chunk. */
    private static void setFuel(MinecartFurnace cart, int fuel) {
        CompoundTag tag = new CompoundTag();
        cart.saveWithoutId(tag);
        tag.putShort("Fuel", (short) fuel);
        cart.load(tag);
    }

    private static boolean lit(MinecartFurnace cart) {
        return cart.getDefaultDisplayBlockState().getValue(BlockStateProperties.LIT);
    }

    // ---- the rate and the lit test ---------------------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void aLitFurnaceMinecartEmitsItsMappedRateAndAnUnlitOneNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ChunkPos> chunks = cleanChunks(helper, level, 96_000, 96_000, 2);
        ChunkPos hotChunk = chunks.get(0);
        ChunkPos coldChunk = chunks.get(1);
        List<MinecartFurnace> carts = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entityTicking(level, chunks), "chunks entity-ticking"))
                .thenExecute(() -> {
                    carts.add(spawn(level, centre(hotChunk), FUEL));
                    carts.add(spawn(level, centre(coldChunk), 0));
                })
                .thenWaitUntil(() -> helper.assertTrue(lit(carts.get(0)), "the fuelled cart is lit after a tick"))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(!lit(carts.get(1)), "the cart without fuel is not lit");
                        reset(level, hotChunk, coldChunk);
                        double rate = rate();
                        helper.assertTrue(rate > 0, "the shipped mapping gives the furnace minecart a rate, got " + rate);

                        interval(level.getServer());

                        helper.assertTrue(local(level, hotChunk) == rate, "exactly the mapped rate " + rate + ", got " + local(level, hotChunk));
                        helper.assertTrue(local(level, coldChunk) == 0.0, "a cart without fuel adds nothing, got " + local(level, coldChunk));

                        reset(level, hotChunk, coldChunk);
                        PollutionCraftMod.config().overrides().set("emissionMultiplier", 2.0);
                        interval(level.getServer());
                        helper.assertTrue(local(level, hotChunk) == 2.0 * rate, "the emission multiplier scales it, got " + local(level, hotChunk));
                    } finally {
                        PollutionCraftMod.config().overrides().clearAll();
                        carts.forEach(Entity::discard);
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    // ---- attribution across a chunk border -------------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void aLitCartEmitsOncePerIntervalIntoTheChunkItIsInAtTheIntervalStart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ChunkPos> chunks = cleanChunks(helper, level, 96_100, 96_000, 2);
        ChunkPos from = chunks.get(0);
        ChunkPos to = chunks.get(1);
        List<MinecartFurnace> carts = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entityTicking(level, chunks), "chunks entity-ticking"))
                .thenExecute(() -> carts.add(spawn(level, centre(from), FUEL)))
                .thenWaitUntil(() -> helper.assertTrue(lit(carts.get(0)), "the cart is lit"))
                .thenExecute(() -> {
                    try {
                        reset(level, from, to);
                        double rate = rate();

                        interval(level.getServer());
                        helper.assertTrue(local(level, from) == rate, "first interval lands in the start chunk, got " + local(level, from));
                        helper.assertTrue(local(level, to) == 0.0, "nothing in the other chunk yet, got " + local(level, to));

                        BlockPos target = centre(to);
                        carts.get(0).moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
                        interval(level.getServer());
                        helper.assertTrue(local(level, to) == rate, "second interval lands in the chunk the cart moved to, got " + local(level, to));
                        helper.assertTrue(local(level, from) == rate, "the first chunk got no second emission, got " + local(level, from));
                    } finally {
                        carts.forEach(Entity::discard);
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    // ---- removal, unload and reload --------------------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void aRemovedCartStopsEmitting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ChunkPos> chunks = cleanChunks(helper, level, 96_200, 96_000, 1);
        ChunkPos chunk = chunks.get(0);
        List<MinecartFurnace> carts = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entityTicking(level, chunks), "chunk entity-ticking"))
                .thenExecute(() -> carts.add(spawn(level, centre(chunk), FUEL)))
                .thenWaitUntil(() -> helper.assertTrue(lit(carts.get(0)), "the cart is lit"))
                .thenExecute(() -> {
                    try {
                        reset(level, chunk);
                        interval(level.getServer());
                        helper.assertTrue(local(level, chunk) == rate(), "emits while it exists, got " + local(level, chunk));

                        carts.get(0).discard();
                        reset(level, chunk);
                        interval(level.getServer());
                        helper.assertTrue(local(level, chunk) == 0.0, "a removed cart emits nothing, got " + local(level, chunk));
                    } finally {
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = EMPTY_TEMPLATE, batch = BATCH, timeoutTicks = 1500)
    public static void aCartInAnUnloadedChunkEmitsNothingAndResumesAfterTheReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ChunkPos> chunks = cleanChunks(helper, level, 96_300, 96_000, 1);
        ChunkPos chunk = chunks.get(0);
        AABB area = new AABB(chunk.getMinBlockX(), -64, chunk.getMinBlockZ(), chunk.getMaxBlockX() + 1, 320, chunk.getMaxBlockZ() + 1);
        List<MinecartFurnace> carts = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entityTicking(level, chunks), "chunk entity-ticking"))
                .thenExecute(() -> carts.add(spawn(level, centre(chunk), FUEL)))
                .thenWaitUntil(() -> helper.assertTrue(lit(carts.get(0)), "the cart is lit"))
                .thenExecute(() -> level.setChunkForced(chunk.x, chunk.z, false))
                .thenWaitUntil(() -> helper.assertTrue(!level.hasChunk(chunk.x, chunk.z), "precondition: the chunk is really unloaded"))
                .thenExecute(() -> {
                    reset(level, chunk);
                    interval(level.getServer());
                    helper.assertTrue(local(level, chunk) == 0.0, "a cart in an unloaded chunk emits nothing, got " + local(level, chunk));
                    level.setChunkForced(chunk.x, chunk.z, true);
                })
                .thenWaitUntil(() -> {
                    List<MinecartFurnace> reloaded = level.getEntitiesOfClass(MinecartFurnace.class, area);
                    helper.assertTrue(!reloaded.isEmpty(), "the cart is read back from the chunk");
                    helper.assertTrue(level.isPositionEntityTicking(reloaded.get(0).blockPosition()), "the reloaded cart is entity-ticking");
                    helper.assertTrue(lit(reloaded.get(0)), "the reloaded cart is lit again after its first tick");
                })
                .thenExecute(() -> {
                    try {
                        reset(level, chunk);
                        interval(level.getServer());
                        helper.assertTrue(local(level, chunk) == rate(), "emits again after the reload, got " + local(level, chunk));
                    } finally {
                        level.getEntitiesOfClass(MinecartFurnace.class, area).forEach(Entity::discard);
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    // ---- dimension change ------------------------------------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = BATCH, timeoutTicks = 1500)
    public static void aCartThatChangesDimensionEmitsInTheNewOneOnly(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
        List<ChunkPos> from = cleanChunks(helper, overworld, 96_400, 96_000, 1);
        List<ChunkPos> to = cleanChunks(helper, nether, 96_400, 96_000, 1);
        ChunkPos fromChunk = from.get(0);
        ChunkPos toChunk = to.get(0);
        List<MinecartFurnace> carts = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(entityTicking(overworld, from), "overworld chunk entity-ticking");
                    helper.assertTrue(entityTicking(nether, to), "nether chunk entity-ticking");
                })
                .thenExecute(() -> carts.add(spawn(overworld, centre(fromChunk), FUEL)))
                .thenWaitUntil(() -> helper.assertTrue(lit(carts.get(0)), "the cart is lit"))
                .thenExecute(() -> {
                    Vec3 target = Vec3.atCenterOf(centre(toChunk));
                    Entity moved = carts.get(0).changeDimension(new DimensionTransition(nether, target, Vec3.ZERO, 0, 0, DimensionTransition.DO_NOTHING));
                    helper.assertTrue(moved instanceof MinecartFurnace, "the cart arrived as a furnace minecart in the nether");
                    carts.add((MinecartFurnace) moved);
                })
                .thenWaitUntil(() -> helper.assertTrue(lit(carts.get(1)) && nether.isPositionEntityTicking(carts.get(1).blockPosition()),
                        "the new cart is lit and entity-ticking in the nether"))
                .thenExecute(() -> {
                    try {
                        reset(overworld, fromChunk);
                        reset(nether, toChunk);

                        interval(overworld.getServer());

                        helper.assertTrue(local(nether, toChunk) == rate(), "the nether chunk got the rate, got " + local(nether, toChunk));
                        helper.assertTrue(local(overworld, fromChunk) == 0.0, "the old dimension got nothing, got " + local(overworld, fromChunk));
                    } finally {
                        carts.forEach(Entity::discard);
                        release(overworld, from);
                        release(nether, to);
                    }
                })
                .thenSucceed();
    }

    // ---- the mapping is data: a reload changes the rate with no rescan -------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void installingChangedRulesAppliesAtTheNextInterval(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ChunkPos> chunks = cleanChunks(helper, level, 96_500, 96_000, 1);
        ChunkPos chunk = chunks.get(0);
        List<MinecartFurnace> carts = new ArrayList<>();
        EmitterRules before = EmitterEngine.currentRules();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entityTicking(level, chunks), "chunk entity-ticking"))
                .thenExecute(() -> carts.add(spawn(level, centre(chunk), FUEL)))
                .thenWaitUntil(() -> helper.assertTrue(lit(carts.get(0)), "the cart is lit"))
                .thenExecute(() -> {
                    try {
                        List<String> log = new ArrayList<>();
                        EmitterRules changed = EmitterRules.resolve(
                                List.of(new EmitterRules.NamedEntry("test:cart",
                                        new MappingEntry(MappingEntry.TargetKind.ENTITY, "minecraft:furnace_minecart", 2.0, null))),
                                BuiltInRegistries.BLOCK, BuiltInRegistries.ENTITY_TYPE, log::add);
                        helper.assertTrue(log.isEmpty(), "the test rule resolves without a log line: " + log);
                        EmitterEngine.installRules(changed);

                        reset(level, chunk);
                        interval(level.getServer());

                        helper.assertTrue(local(level, chunk) == 2.0, "the new rate applies at the next interval, got " + local(level, chunk));
                    } finally {
                        EmitterEngine.installRules(before);
                        carts.forEach(Entity::discard);
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    // ---- the vanilla accessor the whole design rests on ------------------------------------------------

    @GameTest(template = EMPTY_TEMPLATE, batch = BATCH, timeoutTicks = 600)
    public static void theDefaultDisplayStateOfAFurnaceMinecartFollowsItsFuel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ChunkPos> chunks = cleanChunks(helper, level, 96_600, 96_000, 1);
        List<MinecartFurnace> carts = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entityTicking(level, chunks), "chunk entity-ticking"))
                .thenExecute(() -> carts.add(spawn(level, centre(chunks.get(0)), FUEL)))
                .thenWaitUntil(() -> helper.assertTrue(lit(carts.get(0)), "lit with fuel"))
                .thenExecute(() -> {
                    BlockState state = carts.get(0).getDefaultDisplayBlockState();
                    helper.assertTrue(state.is(Blocks.FURNACE), "the default display block is a furnace, was " + state);
                    setFuel(carts.get(0), 0);
                })
                .thenWaitUntil(() -> helper.assertTrue(!lit(carts.get(0)), "unlit once the fuel is gone"))
                .thenExecute(() -> {
                    carts.forEach(Entity::discard);
                    release(level, chunks);
                })
                .thenSucceed();
    }

    // ---- the real non-ticking check ---------------------------------------------------------------------

    /**
     * A lit cart whose chunk drops from entity-ticking to merely loaded keeps its lit flag (the flag only changes while
     * the cart ticks) and must emit nothing, the production {@code isPositionEntityTicking} check being the only thing
     * that stops it. It emits again when the chunk ticks again.
     */
    @GameTest(template = EMPTY_TEMPLATE, batch = BATCH, timeoutTicks = 1500)
    public static void aLitCartInAChunkThatStoppedEntityTickingEmitsNothingUntilItTicksAgain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ChunkPos> chunks = cleanChunks(helper, level, 96_800, 96_000, 1);
        ChunkPos chunk = chunks.get(0);
        BlockPos at = centre(chunk);
        List<MinecartFurnace> carts = new ArrayList<>();
        Runnable cleanup = () -> {
            carts.forEach(Entity::discard);
            level.getChunkSource().removeRegionTicket(HOLD, chunk, 0, chunk);
            level.getChunkSource().removeRegionTicket(HOLD, chunk, 2, chunk);
            release(level, chunks);
        };
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entityTicking(level, chunks), "chunk entity-ticking"))
                .thenExecute(() -> carts.add(spawn(level, at, FUEL)))
                .thenWaitUntil(() -> helper.assertTrue(lit(carts.get(0)), "the cart is lit"))
                .thenExecute(() -> {
                    level.getChunkSource().addRegionTicket(HOLD, chunk, 0, chunk); // level 33: loaded, entities not ticking
                    level.setChunkForced(chunk.x, chunk.z, false);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(level.hasChunk(chunk.x, chunk.z), "precondition: the chunk is still loaded");
                    helper.assertTrue(!level.isPositionEntityTicking(carts.get(0).blockPosition()), "precondition: entities no longer tick there");
                    helper.assertTrue(!carts.get(0).isRemoved() && lit(carts.get(0)), "precondition: the cart is still there with its frozen lit flag");
                })
                .thenExecute(() -> {
                    try {
                        reset(level, chunk);
                        interval(level.getServer());
                        helper.assertTrue(local(level, chunk) == 0.0, "a lit cart where entities do not tick emits nothing, got " + local(level, chunk));
                    } catch (RuntimeException e) {
                        cleanup.run(); // a failing run must not leave a lit cart emitting into other tests
                        throw e;
                    }
                    level.getChunkSource().addRegionTicket(HOLD, chunk, 2, chunk); // level 31: entity-ticking again
                })
                .thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(carts.get(0).blockPosition()), "entities tick again"))
                .thenExecute(() -> {
                    try {
                        reset(level, chunk);
                        interval(level.getServer());
                        helper.assertTrue(local(level, chunk) == rate(), "it emits again once entities tick, got " + local(level, chunk));
                    } finally {
                        cleanup.run();
                    }
                })
                .thenSucceed();
    }

    // ---- cost through the production tracker with the real check ----------------------------------------

    /**
     * Reports (does not assert) what the engine's interval-start tick costs with 50 lit carts in real, entity-ticking
     * chunks, using the production tracker and the real {@code isPositionEntityTicking} check: the same call without the
     * carts is measured too, and the difference is the entity pass.
     */
    @GameTest(template = EMPTY_TEMPLATE, batch = BATCH, timeoutTicks = 1500)
    public static void costOfFiftyLitCartsThroughTheEngineIsReported(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ChunkPos> chunks = cleanChunks(helper, level, 96_900, 96_000, 5);
        List<MinecartFurnace> carts = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(entityTicking(level, chunks), "chunks entity-ticking"))
                .thenExecute(() -> {
                    for (int i = 0; i < 50; i++) {
                        ChunkPos chunk = chunks.get(i % chunks.size());
                        carts.add(spawn(level, centre(chunk).offset(i / chunks.size(), 0, 0), FUEL));
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(carts.stream().allMatch(EntityEmitterGameTests::lit), "all 50 carts are lit"))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(ENGINE.trackedEntityCount(level) >= 50, "the engine tracks the carts, tracked " + ENGINE.trackedEntityCount(level));
                        reset(level, chunks.toArray(new ChunkPos[0]));
                        double with = nanosPerIntervalTick(level);
                        carts.forEach(Entity::discard);
                        double without = nanosPerIntervalTick(level);
                        LOGGER.warn("{}", String.format("COST engine interval-start tick, 50 lit carts, real tick check: with=%.1f us without=%.1f us entity pass=%.1f us; not asserted",
                                with / 1000.0, without / 1000.0, (with - without) / 1000.0));
                    } finally {
                        reset(level, chunks.toArray(new ChunkPos[0]));
                        release(level, chunks);
                    }
                })
                .thenSucceed();
    }

    private static double nanosPerIntervalTick(ServerLevel level) {
        for (int i = 0; i < 100; i++) {
            ENGINE.tick(level, true);
        }
        int rounds = 400;
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            ENGINE.tick(level, true);
        }
        return (System.nanoTime() - start) / (double) rounds;
    }
}
