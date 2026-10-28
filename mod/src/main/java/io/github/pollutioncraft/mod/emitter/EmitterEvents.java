package io.github.pollutioncraft.mod.emitter;

import io.github.pollutioncraft.mod.PollutionCraftMod;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feeds the global {@link EmitterEngine} from game events. Every engine call runs on the server thread: an
 * event delivered anywhere else is handed to {@code server.execute}.
 */
@EventBusSubscriber(modid = PollutionCraftMod.MOD_ID)
public final class EmitterEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmitterEvents.class);
    private static final EmitterEngine ENGINE = EmitterEngine.global();
    private static final AtomicBoolean LOAD_THREAD_LOGGED = new AtomicBoolean();

    private EmitterEvents() {}

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkAccess chunk = event.getChunk();
        boolean serverThread = level.getServer().isSameThread();
        if (LOAD_THREAD_LOGGED.compareAndSet(false, true)) {
            LOGGER.info("ChunkEvent.Load first delivered on thread '{}' (server thread: {})", Thread.currentThread().getName(), serverThread);
        }
        if (serverThread) {
            ENGINE.onChunkLoad(level, chunk);
        } else {
            ChunkPos pos = chunk.getPos();
            level.getServer().execute(() -> {
                if (level.getChunkSource().getChunkNow(pos.x, pos.z) == chunk) { // still loaded
                    ENGINE.onChunkLoad(level, chunk);
                }
            });
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        long key = event.getChunk().getPos().toLong();
        if (level.getServer().isSameThread()) {
            ENGINE.onChunkUnload(level, key);
        } else {
            level.getServer().execute(() -> ENGINE.onChunkUnload(level, key));
        }
    }

    /** Fires for every neighbour update in the world, so a miss must stay one lookup. */
    @SubscribeEvent
    static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockState state = event.getState();
        if (!EmitterEngine.currentRules().mapsBlock(state.getBlock())) {
            return;
        }
        BlockPos pos = event.getPos().immutable();
        if (level.getServer().isSameThread()) {
            ENGINE.onBlockChanged(level, pos, state);
        } else {
            level.getServer().execute(() -> ENGINE.onBlockChanged(level, pos, state));
        }
    }

    /**
     * Lowest priority, and cancelled joins are not received, so an entity another handler vetoes is never tracked.
     * Only a reference is stored: the event may fire before the chunk is full, so the world is not touched.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !EntityActivity.isSupported(event.getEntity().getType())) {
            return;
        }
        Entity entity = event.getEntity();
        if (level.getServer().isSameThread()) {
            ENGINE.onEntityJoin(level, entity);
        } else {
            level.getServer().execute(() -> ENGINE.onEntityJoin(level, entity));
        }
    }

    @SubscribeEvent
    static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !EntityActivity.isSupported(event.getEntity().getType())) {
            return;
        }
        Entity entity = event.getEntity();
        if (level.getServer().isSameThread()) {
            ENGINE.onEntityLeave(level, entity);
        } else {
            level.getServer().execute(() -> ENGINE.onEntityLeave(level, entity));
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        ENGINE.clear();
    }
}
