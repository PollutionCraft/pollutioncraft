package io.github.pollutioncraft.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Public entry point for emitting and reading pollution. Server thread only. Semver-covered.
 *
 * <p>Interface and contract only; the implementation arrives with the storage and emit tasks.
 */
public interface PollutionApi {
    /**
     * Adds pollution at {@code pos}. Call it only while the source is actually working.
     *
     * @param amount pollution units (PU); must be finite and {@code >= 0}. Invalid amounts are dropped and
     *     logged once per source.
     */
    void emit(ServerLevel level, BlockPos pos, double amount);

    /** Local pollution (absorbable + persistent) of the chunk, in PU; 0 if it holds none. */
    double getLocal(ServerLevel level, int chunkX, int chunkZ);

    /** Global pressure in [0, 1). Never decreases. */
    double getPressure(MinecraftServer server);

    /** Whether the chunk is aggressive: its local pollution reached the pressure-adjusted threshold. */
    boolean isAggressive(ServerLevel level, int chunkX, int chunkZ);
}
