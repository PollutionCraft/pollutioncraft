package io.github.pollutioncraft.mod.emitter;

import io.github.pollutioncraft.api.PollutionApi;
import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.PollutionManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.BitSet;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes mapped blocks pollute (architecture 3.3 "Emission", rung 1: data mapping, no per-block code). It
 * remembers the <em>positions</em> of every block that has a mapping, in any state, and once per interval
 * reads the current state at each one and calls {@link PollutionApi#emit} for those that are active.
 * Because the state is read at emission time, a {@code lit} toggle, a removal or a replacement needs no
 * event; a position whose block is no longer mapped is dropped when it is read.
 *
 * <p>Events only <em>add</em> positions: {@link #onChunkLoad} (a scan of the loaded chunk, skipping
 * sections whose palette holds no mapped block), {@link #onBlockChanged} (a neighbour update at a mapped
 * block, which covers placement by players, commands and GameTests), and a bounded background rescan of
 * the loaded chunks. The rescan covers placements that fire no update and is queued for every loaded chunk
 * whenever the rule set is replaced and once per {@value #REFILL_INTERVAL_TICKS} ticks; it does at most
 * {@value #RESCAN_CHUNKS_PER_TICK} palette checks and walks at most {@value #RESCAN_SECTIONS_PER_TICK}
 * sections (4,096 reads each) per tick, keeping its place between ticks.
 *
 * <p>Only chunks where blocks tick are emitted for. No chunk is ever loaded or generated. Server thread
 * only: the event handlers hand work over to it. No Mixin, no reflection.
 */
public final class EmitterEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmitterEngine.class);

    static final int RESCAN_CHUNKS_PER_TICK = 8;
    static final int RESCAN_SECTIONS_PER_TICK = 3;
    /** One full background rescan of the loaded chunks per minute at 20 ticks per second. */
    static final int REFILL_INTERVAL_TICKS = 1200;

    /** Whether blocks tick in a chunk; the seam that lets tests decide it without a world. */
    public interface TickCheck {
        boolean ticks(ServerLevel level, long chunkKey);
    }

    private static volatile EmitterRules installed = EmitterRules.EMPTY;
    private static final EmitterEngine GLOBAL = new EmitterEngine(
            EmitterEngine::currentRules,
            (level, chunkKey) -> level.shouldTickBlocksAt(chunkKey),
            () -> PollutionCraftMod.config().emissionMultiplier(),
            PollutionManager.api());

    /** Per-dimension state. */
    private static final class LevelState {
        final LongOpenHashSet loaded = new LongOpenHashSet();
        final Long2ObjectOpenHashMap<LongOpenHashSet> tracked = new Long2ObjectOpenHashMap<>();
        final LongArrayFIFOQueue rescanQueue = new LongArrayFIFOQueue();
        EmitterRules rulesSeen = EmitterRules.EMPTY;
        int ticksSinceRefill;
        boolean scanning;
        long scanChunk;
        BitSet scanSections;
        int scanNextSection;

        void enqueueAllLoaded() {
            rescanQueue.clear();
            scanning = false;
            ticksSinceRefill = 0;
            for (LongIterator it = loaded.iterator(); it.hasNext(); ) {
                rescanQueue.enqueue(it.nextLong());
            }
        }

        boolean idle() {
            return !scanning && rescanQueue.isEmpty();
        }
    }

    private final Supplier<EmitterRules> rulesSource;
    private final TickCheck tickCheck;
    private final IntervalAmounts amounts;
    private final PollutionApi api;
    private final Map<ServerLevel, LevelState> levels = new WeakHashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private boolean failureLogged;

    /**
     * @param rulesSource the current rule set, read when needed; a new instance means "rules changed"
     * @param tickCheck whether blocks tick in a chunk
     * @param multiplier the {@code emissionMultiplier}, read once per interval
     */
    public EmitterEngine(Supplier<EmitterRules> rulesSource, TickCheck tickCheck, DoubleSupplier multiplier, PollutionApi api) {
        this.rulesSource = rulesSource;
        this.tickCheck = tickCheck;
        this.amounts = new IntervalAmounts(multiplier);
        this.api = api;
    }

    /** The engine the server runs. */
    public static EmitterEngine global() {
        return GLOBAL;
    }

    /** Replaces the rule set. May be called from any thread; the engine notices it on the server thread. */
    public static void installRules(EmitterRules rules) {
        installed = rules;
    }

    public static EmitterRules currentRules() {
        return installed;
    }

    /**
     * Called on every gated tick of a dimension, before its scheduler. Does a bounded slice of the rescan,
     * and on the tick an interval starts also emits for every active mapped block, so the amounts are
     * drained into that same interval.
     */
    public void tick(ServerLevel level, boolean intervalStarts) {
        try {
            LevelState state = state(level);
            EmitterRules rules = rulesSource.get();
            if (rules != state.rulesSeen) {
                state.rulesSeen = rules;
                state.enqueueAllLoaded();
            } else if (++state.ticksSinceRefill >= REFILL_INTERVAL_TICKS && state.idle()) {
                state.enqueueAllLoaded();
            }
            rescanSlice(level, state, rules);
            if (intervalStarts) {
                emitAll(level, state, rules);
            }
        } catch (RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.error("Emitter engine failed in {}; further failures are not logged", level.dimension().location(), e);
            }
        }
    }

    /** A chunk finished loading: remember it and scan it for mapped blocks. */
    public void onChunkLoad(ServerLevel level, ChunkAccess chunk) {
        LevelState state = state(level);
        state.loaded.add(chunk.getPos().toLong());
        if (chunk instanceof LevelChunk levelChunk) {
            scanAll(state, levelChunk, rulesSource.get());
        }
    }

    /** A chunk is unloading: forget it and its positions. */
    public void onChunkUnload(ServerLevel level, long chunkKey) {
        LevelState state = state(level);
        state.loaded.remove(chunkKey);
        state.tracked.remove(chunkKey);
        if (state.scanning && state.scanChunk == chunkKey) {
            state.scanning = false;
        }
    }

    /** A block was placed or changed at {@code pos} (a neighbour update fired there). */
    public void onBlockChanged(ServerLevel level, BlockPos pos, BlockState newState) {
        if (rulesSource.get().mapsBlock(newState.getBlock())) {
            track(state(level), pos.asLong());
        }
    }

    /** Forgets everything (server stopped). */
    public void clear() {
        levels.clear();
    }

    public boolean isTracked(ServerLevel level, BlockPos pos) {
        LongOpenHashSet set = state(level).tracked.get(chunkKeyOf(pos.asLong()));
        return set != null && set.contains(pos.asLong());
    }

    public int trackedCount(ServerLevel level) {
        int total = 0;
        for (LongOpenHashSet set : state(level).tracked.values()) {
            total += set.size();
        }
        return total;
    }

    private LevelState state(ServerLevel level) {
        return levels.computeIfAbsent(level, key -> new LevelState());
    }

    private static long chunkKeyOf(long packedPos) {
        return ChunkKey.pack(BlockPos.getX(packedPos) >> 4, BlockPos.getZ(packedPos) >> 4);
    }

    private static void track(LevelState state, long packedPos) {
        state.tracked.computeIfAbsent(chunkKeyOf(packedPos), key -> new LongOpenHashSet()).add(packedPos);
    }

    private void emitAll(ServerLevel level, LevelState state, EmitterRules rules) {
        amounts.startInterval();
        var chunks = state.tracked.long2ObjectEntrySet().fastIterator();
        while (chunks.hasNext()) {
            Long2ObjectMap.Entry<LongOpenHashSet> entry = chunks.next();
            long chunkKey = entry.getLongKey();
            if (!tickCheck.ticks(level, chunkKey)) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey));
            if (chunk == null) {
                continue;
            }
            LongOpenHashSet positions = entry.getValue();
            for (LongIterator it = positions.iterator(); it.hasNext(); ) {
                cursor.set(it.nextLong());
                BlockState current = chunk.getBlockState(cursor);
                double rate = rules.activeRate(current);
                if (rate < 0) {
                    if (!rules.mapsBlock(current.getBlock())) {
                        it.remove();
                    }
                    continue;
                }
                double amount = amounts.amountFor(rate);
                if (amount > 0) {
                    api.emit(level, cursor, amount);
                }
            }
            if (positions.isEmpty()) {
                chunks.remove();
            }
        }
    }

    private void rescanSlice(ServerLevel level, LevelState state, EmitterRules rules) {
        if (rules.blockCount() == 0) {
            state.rescanQueue.clear();
            state.scanning = false;
            return;
        }
        int chunkBudget = RESCAN_CHUNKS_PER_TICK;
        int sectionBudget = RESCAN_SECTIONS_PER_TICK;
        while (sectionBudget > 0) {
            if (!state.scanning) {
                if (state.rescanQueue.isEmpty() || chunkBudget == 0) {
                    return;
                }
                chunkBudget--;
                beginScan(level, state, state.rescanQueue.dequeueLong(), rules);
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkKey.x(state.scanChunk), ChunkKey.z(state.scanChunk));
            int section = state.scanSections.nextSetBit(state.scanNextSection);
            if (chunk == null || section < 0) {
                state.scanning = false;
                continue;
            }
            walkSection(state, chunk, section, rules);
            state.scanNextSection = section + 1;
            sectionBudget--;
        }
    }

    private void beginScan(ServerLevel level, LevelState state, long chunkKey, EmitterRules rules) {
        if (!state.loaded.contains(chunkKey)) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey));
        if (chunk == null) {
            return;
        }
        BitSet sections = sectionsWithMappedBlocks(chunk, rules);
        if (!sections.isEmpty()) {
            state.scanning = true;
            state.scanChunk = chunkKey;
            state.scanSections = sections;
            state.scanNextSection = 0;
        }
    }

    private static void scanAll(LevelState state, LevelChunk chunk, EmitterRules rules) {
        if (rules.blockCount() == 0) {
            return;
        }
        BitSet sections = sectionsWithMappedBlocks(chunk, rules);
        for (int section = sections.nextSetBit(0); section >= 0; section = sections.nextSetBit(section + 1)) {
            walkSection(state, chunk, section, rules);
        }
    }

    /** Palette check only: which sections might hold a mapped state. */
    private static BitSet sectionsWithMappedBlocks(LevelChunk chunk, EmitterRules rules) {
        BitSet sections = new BitSet();
        LevelChunkSection[] all = chunk.getSections();
        for (int i = 0; i < all.length; i++) {
            if (all[i].maybeHas(candidate -> rules.mapsBlock(candidate.getBlock()))) {
                sections.set(i);
            }
        }
        return sections;
    }

    private static void walkSection(LevelState state, LevelChunk chunk, int sectionIndex, EmitterRules rules) {
        LevelChunkSection section = chunk.getSection(sectionIndex);
        ChunkPos pos = chunk.getPos();
        int baseY = (chunk.getMinSection() + sectionIndex) << 4;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (rules.mapsBlock(section.getBlockState(x, y, z).getBlock())) {
                        track(state, BlockPos.asLong(pos.getMinBlockX() + x, baseY + y, pos.getMinBlockZ() + z));
                    }
                }
            }
        }
    }
}
