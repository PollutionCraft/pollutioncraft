package io.github.pollutioncraft.mod;

import io.github.pollutioncraft.api.PollutionApi;
import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.core.ChunkPollution;
import io.github.pollutioncraft.core.SimulationGate;
import io.github.pollutioncraft.mod.emitter.EmitterEngine;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side driver: counts {@code activeTicks} and runs each dimension's interval scheduler. Never loads or
 * generates chunks; it touches only saved data.
 */
@EventBusSubscriber(modid = PollutionCraftMod.MOD_ID)
public final class PollutionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PollutionManager.class);
    private static final PollutionApi API = new PollutionApiImpl();

    private PollutionManager() {}

    public static PollutionApi api() {
        return API;
    }

    /** Outcome of {@link #stepNow}. */
    public enum StepResult {
        RAN,
        PAUSED_WORLD_RECORD,
        PAUSED_DIMENSION_RECORD
    }

    public static Path dataFile(ServerLevel level, String storageKey) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        return DimensionType.getStorageFolder(level.dimension(), root).resolve("data").resolve(storageKey + ".dat");
    }

    public static WorldPollution world(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return loadWorld(overworld.getDataStorage(), dataFile(overworld, WorldPollution.STORAGE_KEY));
    }

    public static DimensionPollution dimension(ServerLevel level) {
        return loadDimension(level.getDataStorage(), dataFile(level, DimensionPollution.STORAGE_KEY));
    }

    public static WorldPollution loadWorld(DimensionDataStorage storage, Path file) {
        return storage.computeIfAbsent(WorldPollution.factory(file), WorldPollution.STORAGE_KEY);
    }

    public static DimensionPollution loadDimension(DimensionDataStorage storage, Path file) {
        return storage.computeIfAbsent(DimensionPollution.factory(file), DimensionPollution.STORAGE_KEY);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        tick(server, server.getPlayerCount());
    }

    /**
     * One server tick. The player count is a parameter so tests drive the exact production path.
     * The {@link SimulationGate} decides whether {@code activeTicks} and the simulation advance.
     */
    public static void tick(MinecraftServer server, int onlinePlayers) {
        if (!SimulationGate.isOpen(onlinePlayers)) {
            return;
        }
        WorldPollution world = world(server);
        if (world.isPreserved()) {
            return; // unreadable world record is kept as is; activeTicks and pressure must never reset
        }
        world.record().onServerTick(onlinePlayers);
        world.setDirty();
        for (ServerLevel level : server.getAllLevels()) {
            tickDimension(level, world);
        }
    }

    /**
     * Runs {@code n} whole intervals in {@code level} now (entry point for the debug command, task 009).
     * Does not run the emitter engine: it drains only what is already buffered. Returns why nothing ran when the world record or this dimension's field is paused (unreadable).
     */
    public static StepResult stepNow(ServerLevel level, int n) {
        DimensionPollution dimension = dimension(level);
        if (world(level.getServer()).isPreserved()) {
            return StepResult.PAUSED_WORLD_RECORD;
        }
        if (dimension.isPreserved()) {
            return StepResult.PAUSED_DIMENSION_RECORD;
        }
        WorldPollution world = world(level.getServer());
        try {
            dimension.scheduler().stepNow(dimension.field(), key -> 0.0, n, committed -> countCommitted(world, committed));
        } finally {
            dimension.setDirty();
        }
        return StepResult.RAN;
    }

    private static void tickDimension(ServerLevel level, WorldPollution world) {
        DimensionPollution dimension = dimension(level);
        if (dimension.isPreserved()) {
            return;
        }
        try {
            // Mapped blocks emit on the tick an interval starts, so the scheduler drains them into that interval.
            EmitterEngine.global().tick(level, !dimension.scheduler().inFlight());
            // Absorption is stubbed to zero until P2 (task 013).
            if (dimension.scheduler().tick(dimension.field(), key -> 0.0, committed -> countCommitted(world, committed))) {
                dimension.setDirty();
            }
        } catch (RuntimeException e) {
            LOGGER.error("Pollution step failed in {}; dropping this interval", level.dimension().location(), e);
            dimension.scheduler().abandon();
        }
    }

    /** An interval committed: its emissions join the world's never-decreasing {@code grossEmitted}. */
    private static void countCommitted(WorldPollution world, double emitted) {
        world.record().addGrossEmitted(emitted);
        world.setDirty();
    }

    /** Local pollution of a chunk; 0 while the dimension is paused (unreadable stored record). */
    static double localPollution(ServerLevel level, int chunkX, int chunkZ) {
        DimensionPollution dimension = dimension(level);
        if (dimension.isPreserved()) {
            return 0.0;
        }
        ChunkPollution chunk = dimension.field().get(ChunkKey.pack(chunkX, chunkZ));
        return chunk == null ? 0.0 : chunk.local();
    }
}
