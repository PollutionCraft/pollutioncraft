package io.github.pollutioncraft.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Public entry point for emitting and reading pollution. Server thread only. Semver-covered.
 *
 * <p>All methods must be called on the server thread.
 */
public interface PollutionApi {
    /**
     * Adds pollution at {@code pos}. Call it only while the source is actually working.
     *
     * <p>The amount is buffered, not written: it joins the field when the next simulation interval starts
     * and shows in {@link #getLocal} once that interval ends. Emitting never loads or generates a chunk, so an
     * unloaded chunk is a valid target. A call from another thread is rejected and logged once per calling
     * class; a dimension or world whose stored record is unreadable (paused) drops the amount.
     * {@code amount == 0} does nothing.
     *
     * @param amount pollution units (PU); must be finite and {@code >= 0}. Invalid amounts are dropped and
     *     logged once per source (the calling class) per dimension.
     */
    void emit(ServerLevel level, BlockPos pos, double amount);

    /** Local pollution (absorbable + persistent) of the chunk, in PU; 0 if it holds none. */
    double getLocal(ServerLevel level, int chunkX, int chunkZ);

    /** Global pressure in [0, 1). Never decreases. */
    double getPressure(MinecraftServer server);

    /** Whether the chunk is aggressive: its local pollution reached the pressure-adjusted threshold. */
    boolean isAggressive(ServerLevel level, int chunkX, int chunkZ);
}
