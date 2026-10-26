package io.github.pollutioncraft.mod;

import io.github.pollutioncraft.api.PollutionApi;
import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.core.EmissionBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads are real (task 007), {@code emit} buffers (task 010); {@code isAggressive} arrives with task 016. */
final class PollutionApiImpl implements PollutionApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(PollutionApiImpl.class);
    private static final StackWalker CALLERS = StackWalker.getInstance();

    /** Callers already logged for an off-thread call. Concurrent: it is touched from the offending thread. */
    private final Set<String> offThreadSources = ConcurrentHashMap.newKeySet();

    /**
     * Adds to the dimension's pending buffer only; the field changes when the next interval commits. The
     * source key (the calling class) is resolved only on a rejected call, never on a valid one.
     */
    @Override
    public void emit(ServerLevel level, BlockPos pos, double amount) {
        if (!level.getServer().isSameThread()) {
            String source = callerOf();
            if (offThreadSources.add(source)) {
                LOGGER.error("Rejected pollution emit from {} on thread '{}': the API is server-thread only. "
                        + "Further off-thread calls from this source are not logged",
                        source, Thread.currentThread().getName());
            }
            return;
        }
        DimensionPollution dimension = PollutionManager.dimension(level);
        if (PollutionManager.world(level.getServer()).isPreserved() || dimension.isPreserved()) {
            if (dimension.firstPausedEmit()) {
                LOGGER.warn("Dropping pollution emissions in {}: its stored record is unreadable and kept unchanged",
                        level.dimension().location());
            }
            return;
        }
        String badSource = EmissionBuffer.isValidAmount(amount) ? "" : callerOf();
        dimension.emissions().add(ChunkKey.pack(pos.getX() >> 4, pos.getZ() >> 4), pos.asLong(), amount, badSource);
    }

    /** Class name of the first caller outside this implementation. Slow; used only on rejection. */
    private static String callerOf() {
        return CALLERS.walk(frames -> frames
                .map(StackWalker.StackFrame::getClassName)
                .filter(name -> !name.equals(PollutionApiImpl.class.getName()))
                .findFirst()
                .orElse("unknown"));
    }

    @Override
    public double getLocal(ServerLevel level, int chunkX, int chunkZ) {
        return PollutionManager.localPollution(level, chunkX, chunkZ);
    }

    /** Reads 0 while the world record is paused (unreadable stored record). */
    @Override
    public double getPressure(MinecraftServer server) {
        WorldPollution world = PollutionManager.world(server);
        return world.isPreserved() ? 0.0 : world.record().pressure();
    }

    @Override
    public boolean isAggressive(ServerLevel level, int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("isAggressive arrives with task 016");
    }
}
