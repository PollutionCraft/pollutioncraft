package io.github.pollutioncraft.mod.emitter;

import io.github.pollutioncraft.api.PollutionApi;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Makes working entities pollute (task 032): the furnace minecart. It keeps, per dimension, the loaded entities of the
 * supported types (see {@link EntityActivity}), fed by the join and leave events, and once per interval, on the
 * interval-start tick, reads each one and calls {@link PollutionApi#emit} at its block position for those that are
 * working. The same emit path and the same multiplier handling as the block emitters; nothing writes the field.
 *
 * <p>The set is read once per interval, so a cart that crosses a chunk border is neither lost nor counted twice: it
 * emits into the chunk it stands in when the interval starts. Entities that are removed or that changed level are
 * dropped when read, so a missed leave event cannot leave a stale entry for more than one interval. An entity in a
 * chunk where entities do not tick emits nothing: it does not burn fuel there either.
 *
 * <p>At most {@code maxTracked} entities per dimension are read (a known limitation, documented in
 * docs/emitter-mappings.md): the ones with the lowest UUID, so the same carts emit after a restart; one warning per
 * dimension per {@value #CAP_LOG_PERIOD_TICKS} ticks says so. Server thread only. No Mixin, no reflection.
 */
public final class EntityEmitters {
    /** The bound per dimension. About 1 ms of work on the one tick per interval that reads them. */
    public static final int MAX_TRACKED_PER_LEVEL = 1024;
    static final long CAP_LOG_PERIOD_TICKS = 1200;

    /** Whether entities tick at a position; the seam that lets tests decide it without waiting for a chunk. */
    public interface TickCheck {
        boolean ticks(ServerLevel level, BlockPos pos);
    }

    private static final class LevelState {
        final ReferenceOpenHashSet<Entity> entities = new ReferenceOpenHashSet<>();
        long lastCapLogTick = Long.MIN_VALUE;
    }

    private final PollutionApi api;
    private final TickCheck tickCheck;
    private final int maxTracked;
    private final Consumer<String> capLog;
    private final Map<ServerLevel, LevelState> levels = new WeakHashMap<>();

    public EntityEmitters(PollutionApi api, TickCheck tickCheck, int maxTracked, Consumer<String> capLog) {
        this.api = api;
        this.tickCheck = tickCheck;
        this.maxTracked = maxTracked;
        this.capLog = capLog;
    }

    /** An entity joined a level (spawned, or read from a chunk). Unsupported types are ignored. */
    public void onJoin(ServerLevel level, Entity entity) {
        if (EntityActivity.isSupported(entity.getType())) {
            levels.computeIfAbsent(level, key -> new LevelState()).entities.add(entity);
        }
    }

    /** An entity left a level (removed, unloaded with its chunk, or changed dimension). */
    public void onLeave(ServerLevel level, Entity entity) {
        LevelState state = levels.get(level);
        if (state != null) {
            state.entities.remove(entity);
        }
    }

    public int trackedCount(ServerLevel level) {
        LevelState state = levels.get(level);
        return state == null ? 0 : state.entities.size();
    }

    /** Forgets everything (server stopped). */
    public void clear() {
        levels.clear();
    }

    /**
     * Emits for every working tracked entity of {@code level}; called on the tick an interval starts.
     *
     * @param amountFor turns a mapped rate into this interval's PU (the multiplier is applied there)
     */
    public void emitAll(ServerLevel level, EmitterRules rules, DoubleUnaryOperator amountFor) {
        LevelState state = levels.get(level);
        if (state == null || state.entities.isEmpty()) {
            return;
        }
        state.entities.removeIf(entity -> entity.isRemoved() || entity.level() != level);
        Collection<Entity> toRead = state.entities;
        if (state.entities.size() > maxTracked) {
            toRead = BoundedLowest.of(state.entities, Entity::getUUID, maxTracked);
            logCapOnce(level, state);
        }
        if (!rules.hasEntityRules()) {
            return;
        }
        for (Entity entity : toRead) {
            double rate = rules.entityRate(entity.getType());
            if (rate < 0) {
                continue;
            }
            BlockPos pos = entity.blockPosition();
            if (!tickCheck.ticks(level, pos) || !EntityActivity.isActive(entity)) {
                continue;
            }
            double amount = amountFor.applyAsDouble(rate);
            if (amount > 0) {
                api.emit(level, pos, amount);
            }
        }
    }

    private void logCapOnce(ServerLevel level, LevelState state) {
        long now = level.getGameTime();
        if (state.lastCapLogTick != Long.MIN_VALUE && now - state.lastCapLogTick < CAP_LOG_PERIOD_TICKS) {
            return;
        }
        state.lastCapLogTick = now;
        capLog.accept("Dimension " + level.dimension().location() + " holds " + state.entities.size() + " emitter entities; only the "
                + maxTracked + " with the lowest UUIDs are read and emit, the rest emit nothing");
    }
}
