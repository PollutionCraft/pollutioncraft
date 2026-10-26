package io.github.pollutioncraft.mod;

import io.github.pollutioncraft.api.PollutionApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Reads are real (task 007); {@code emit} arrives with task 010 and {@code isAggressive} with task 016. */
final class PollutionApiImpl implements PollutionApi {
    @Override
    public void emit(ServerLevel level, BlockPos pos, double amount) {
        throw new UnsupportedOperationException("emit arrives with task 010");
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
