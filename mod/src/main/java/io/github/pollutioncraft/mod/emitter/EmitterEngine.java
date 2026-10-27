package io.github.pollutioncraft.mod.emitter;

import io.github.pollutioncraft.api.PollutionApi;
import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.PollutionManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
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
 * {@value #RESCAN_CHUNKS_PER_TICK} palette checks, walks at most {@value #RESCAN_SECTIONS_PER_TICK}
 * sections (4,096 reads each) and keeps at most {@value #RESCAN_OFFERS_PER_TICK} positions per tick, keeping its
 * place (down to the cell) between ticks.
 *
 * <p>A chunk tracks at most {@code maxTrackedPerChunk} positions: the lowest by packed position (see
 * {@link ChunkTrack}); the rest emit nothing. The cap is read when a chunk is scanned or rescanned, so a
 * change through {@code /pollution tune} (noticed once per tick, which queues every loaded chunk) applies from
 * the next scan. Removing a tracked block from a chunk that is over the cap frees a slot the engine cannot
 * refill alone, so that chunk goes to the front of the rescan queue.
 *
 * <p>Only chunks where blocks tick are emitted for. No chunk is ever loaded or generated. Server thread
 * only: the event handlers hand work over to it. No Mixin, no reflection.
 */
public final class EmitterEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmitterEngine.class);

    private static final int CELLS_PER_SECTION = 4096;
    static final int RESCAN_CHUNKS_PER_TICK = 8;
    static final int RESCAN_SECTIONS_PER_TICK = 3;
    /**
     * The second work limit of a rescan slice, besides the sections per tick: positions the walk keeps (inserts
     * into the scan heap) per tick. A dense section costs far more than a sparse one, so a limit by sections alone
     * lets a chunk full of mapped blocks cost over twice as much per tick; see the 031 report for the numbers.
     */
    public static final int RESCAN_OFFERS_PER_TICK = 512;
    /** One full background rescan of the loaded chunks per minute at 20 ticks per second; also the cap-log period. */
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
            PollutionManager.api(),
            () -> PollutionCraftMod.config().maxTrackedPerChunk(),
            LOGGER::warn);

    /** Per-dimension state. */
    private static final class LevelState {
        final LongOpenHashSet loaded = new LongOpenHashSet();
        final Long2ObjectOpenHashMap<ChunkTrack> tracked = new Long2ObjectOpenHashMap<>();
        final LongArrayFIFOQueue rescanQueue = new LongArrayFIFOQueue();
        /** Gated tick of the last cap log line per chunk; survives unload so walking back and forth does not repeat it. */
        final Long2LongOpenHashMap lastCapLog = new Long2LongOpenHashMap();
        EmitterRules rulesSeen = EmitterRules.EMPTY;
        int capSeen = -1;
        long gatedTicks;
        int ticksSinceRefill;
        boolean scanning;
        long scanChunk;
        BitSet scanSections;
        int scanNextSection;
        int scanCell;
        ScanHeap building;
        int buildingCap;

        void enqueueAllLoaded() {
            rescanQueue.clear();
            scanning = false;
            building = null;
            ticksSinceRefill = 0;
            for (LongIterator it = loaded.iterator(); it.hasNext(); ) {
                rescanQueue.enqueue(it.nextLong());
            }
            lastCapLog.values().removeIf(tick -> gatedTicks - tick > 2L * REFILL_INTERVAL_TICKS);
        }

        boolean idle() {
            return !scanning && rescanQueue.isEmpty();
        }
    }

    private final Supplier<EmitterRules> rulesSource;
    private final TickCheck tickCheck;
    private final IntervalAmounts amounts;
    private final PollutionApi api;
    private final IntSupplier capSource;
    private final Consumer<String> capLog;
    private final int offersPerTick;
    private final Map<ServerLevel, LevelState> levels = new WeakHashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private boolean failureLogged;

    /**
     * @param rulesSource the current rule set, read when needed; a new instance means "rules changed"
     * @param tickCheck whether blocks tick in a chunk
     * @param multiplier the {@code emissionMultiplier}, read once per interval
     * @param capSource {@code maxTrackedPerChunk}, read when a chunk is scanned and once per tick to notice a change
     * @param capLog receives the cap lines, at most one per chunk per {@value #REFILL_INTERVAL_TICKS} gated ticks
     */
    public EmitterEngine(
            Supplier<EmitterRules> rulesSource,
            TickCheck tickCheck,
            DoubleSupplier multiplier,
            PollutionApi api,
            IntSupplier capSource,
            Consumer<String> capLog) {
        this(rulesSource, tickCheck, multiplier, api, capSource, capLog, RESCAN_OFFERS_PER_TICK);
    }

    /** @param offersPerTick the second limit of a rescan slice, besides the sections per tick (tests tune it) */
    public EmitterEngine(
            Supplier<EmitterRules> rulesSource,
            TickCheck tickCheck,
            DoubleSupplier multiplier,
            PollutionApi api,
            IntSupplier capSource,
            Consumer<String> capLog,
            int offersPerTick) {
        this.offersPerTick = offersPerTick;
        this.rulesSource = rulesSource;
        this.tickCheck = tickCheck;
        this.amounts = new IntervalAmounts(multiplier);
        this.api = api;
        this.capSource = capSource;
        this.capLog = capLog;
    }

    /** An engine that reads the cap from the config and logs to the server log. */
    public EmitterEngine(Supplier<EmitterRules> rulesSource, TickCheck tickCheck, DoubleSupplier multiplier, PollutionApi api) {
        this(rulesSource, tickCheck, multiplier, api, () -> PollutionCraftMod.config().maxTrackedPerChunk(), LOGGER::warn);
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
            state.gatedTicks++;
            EmitterRules rules = rulesSource.get();
            int cap = currentCap();
            if (rules != state.rulesSeen || cap != state.capSeen) {
                state.rulesSeen = rules;
                state.capSeen = cap;
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
        long key = chunk.getPos().toLong();
        state.loaded.add(key);
        if (chunk instanceof LevelChunk levelChunk) {
            scanWhole(level, state, key, levelChunk, rulesSource.get());
        }
    }

    /** A chunk is unloading: forget it and its positions. */
    public void onChunkUnload(ServerLevel level, long chunkKey) {
        LevelState state = state(level);
        state.loaded.remove(chunkKey);
        state.tracked.remove(chunkKey);
        if (state.scanning && state.scanChunk == chunkKey) {
            state.scanning = false;
            state.building = null;
        }
    }

    /** A block was placed or changed at {@code pos} (a neighbour update fired there). */
    public void onBlockChanged(ServerLevel level, BlockPos pos, BlockState newState) {
        if (!rulesSource.get().mapsBlock(newState.getBlock())) {
            return;
        }
        LevelState state = state(level);
        long key = chunkKeyOf(pos.asLong());
        ChunkTrack track = state.tracked.computeIfAbsent(key, k -> new ChunkTrack(currentCap()));
        ChunkTrack.Outcome outcome = track.offer(pos.asLong());
        if (outcome == ChunkTrack.Outcome.ADDED_EVICTING || outcome == ChunkTrack.Outcome.REJECTED) {
            logCapOnce(level, state, key, -1, track.cap);
        }
    }

    /** Forgets everything (server stopped). */
    public void clear() {
        levels.clear();
    }

    public boolean isTracked(ServerLevel level, BlockPos pos) {
        ChunkTrack track = state(level).tracked.get(chunkKeyOf(pos.asLong()));
        return track != null && track.contains(pos.asLong());
    }

    /** The tracked positions of a chunk in ascending packed order; empty if none. */
    public long[] trackedIn(ServerLevel level, long chunkKey) {
        ChunkTrack track = state(level).tracked.get(chunkKey);
        return track == null ? new long[0] : track.toArray();
    }

    public int trackedCount(ServerLevel level) {
        int total = 0;
        for (ChunkTrack track : state(level).tracked.values()) {
            total += track.size;
        }
        return total;
    }

    private LevelState state(ServerLevel level) {
        return levels.computeIfAbsent(level, key -> new LevelState());
    }

    private int currentCap() {
        return Math.max(1, capSource.getAsInt());
    }

    private static long chunkKeyOf(long packedPos) {
        return ChunkKey.pack(BlockPos.getX(packedPos) >> 4, BlockPos.getZ(packedPos) >> 4);
    }

    /**
     * Writes the cap line unless this chunk's last one is less than a period old. Called by scans that find a
     * chunk over the cap and by placements that were ignored or evicted; a placement into an already-reported
     * full chunk therefore costs one comparison.
     *
     * @param mapped the number of mapped blocks the scan found, or -1 when only a placement noticed
     */
    private void logCapOnce(ServerLevel level, LevelState state, long chunkKey, long mapped, int cap) {
        if (state.lastCapLog.containsKey(chunkKey) && state.gatedTicks - state.lastCapLog.get(chunkKey) < REFILL_INTERVAL_TICKS) {
            return;
        }
        state.lastCapLog.put(chunkKey, state.gatedTicks);
        capLog.accept("Chunk " + ChunkKey.x(chunkKey) + ", " + ChunkKey.z(chunkKey) + " in " + level.dimension().location() + " holds "
                + (mapped < 0 ? "more than " + cap : String.valueOf(mapped)) + " mapped emitter blocks; only the lowest " + cap
                + " positions (maxTrackedPerChunk) are tracked and emit, the rest emit nothing");
    }

    private void emitAll(ServerLevel level, LevelState state, EmitterRules rules) {
        amounts.startInterval();
        var chunks = state.tracked.long2ObjectEntrySet().fastIterator();
        while (chunks.hasNext()) {
            Long2ObjectMap.Entry<ChunkTrack> entry = chunks.next();
            long chunkKey = entry.getLongKey();
            if (!tickCheck.ticks(level, chunkKey)) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey));
            if (chunk == null) {
                continue;
            }
            ChunkTrack track = entry.getValue();
            int kept = 0;
            // Ascending packed order, so even above maxEmittersPerChunk the positions the buffer attributes are deterministic.
            for (int i = 0; i < track.size; i++) {
                long position = track.positions[i];
                cursor.set(position);
                BlockState current = chunk.getBlockState(cursor);
                double rate = rules.activeRate(current);
                if (rate < 0 && !rules.mapsBlock(current.getBlock())) {
                    continue; // no longer a mapped block: dropped
                }
                track.positions[kept++] = position;
                if (rate >= 0) {
                    double amount = amounts.amountFor(rate);
                    if (amount > 0) {
                        api.emit(level, cursor, amount);
                    }
                }
            }
            boolean dropped = kept < track.size;
            track.size = kept;
            if (dropped && track.overCap) {
                requestRepair(state, chunkKey, track);
            }
            if (track.size == 0 && !track.overCap) {
                chunks.remove();
            }
        }
    }

    /** A slot was freed in a chunk with mapped blocks the engine does not know: rescan it soon. */
    private static void requestRepair(LevelState state, long chunkKey, ChunkTrack track) {
        if (state.scanning && state.scanChunk == chunkKey) {
            track.dirty = true; // the scan under way may have missed it; it restarts once when it ends
        } else if (!track.rescanQueued) {
            track.rescanQueued = true;
            state.rescanQueue.enqueueFirst(chunkKey);
        }
    }

    private void rescanSlice(ServerLevel level, LevelState state, EmitterRules rules) {
        if (rules.blockCount() == 0) {
            state.rescanQueue.clear();
            state.scanning = false;
            state.building = null;
            return;
        }
        int chunkBudget = RESCAN_CHUNKS_PER_TICK;
        int sectionBudget = RESCAN_SECTIONS_PER_TICK;
        int offerBudget = offersPerTick;
        while (sectionBudget > 0 && offerBudget > 0) {
            if (!state.scanning) {
                if (state.rescanQueue.isEmpty() || chunkBudget == 0) {
                    return;
                }
                chunkBudget--;
                beginScan(level, state, state.rescanQueue.dequeueLong(), rules);
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkKey.x(state.scanChunk), ChunkKey.z(state.scanChunk));
            if (chunk == null) {
                state.scanning = false;
                state.building = null;
                continue;
            }
            int section = state.scanSections.nextSetBit(state.scanNextSection);
            if (section < 0) {
                finishScan(level, state, chunk, rules);
                return; // the merge and sort of a finished chunk is this tick's remaining work
            }
            long kept = state.building.kept;
            state.scanCell = walkSection(state.building, chunk, section, state.scanCell, offerBudget, rules);
            offerBudget -= (int) Math.min(offerBudget, state.building.kept - kept);
            if (state.scanCell >= CELLS_PER_SECTION) {
                state.scanCell = 0;
                state.scanNextSection = section + 1;
                sectionBudget--;
            }
        }
    }

    private void beginScan(ServerLevel level, LevelState state, long chunkKey, EmitterRules rules) {
        ChunkTrack existing = state.tracked.get(chunkKey);
        if (existing != null) {
            existing.rescanQueued = false;
        }
        if (!state.loaded.contains(chunkKey)) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkKey.x(chunkKey), ChunkKey.z(chunkKey));
        if (chunk == null) {
            return;
        }
        BitSet sections = sectionsWithMappedBlocks(chunk, rules);
        if (sections.isEmpty()) {
            state.tracked.remove(chunkKey); // nothing mapped here any more
            return;
        }
        state.scanning = true;
        state.scanChunk = chunkKey;
        state.scanSections = sections;
        state.scanNextSection = 0;
        state.scanCell = 0;
        state.buildingCap = currentCap();
        state.building = new ScanHeap(state.buildingCap);
    }

    /**
     * The walk is done. Merges in what was tracked before (re-checked, because a placement during the scan went
     * straight into the old set and a block may have gone), keeps the lowest {@code cap}, and swaps it in. If a
     * tracked position was dropped while this scan ran the chunk is scanned once more, from the front.
     */
    private void finishScan(ServerLevel level, LevelState state, LevelChunk chunk, EmitterRules rules) {
        long key = state.scanChunk;
        ScanHeap walked = state.building;
        int cap = state.buildingCap;
        ChunkTrack old = state.tracked.get(key);
        state.scanning = false;
        state.building = null;
        if (old != null && old.dirty && !old.restarted) {
            old.dirty = false;
            old.restarted = true;
            old.rescanQueued = true;
            state.rescanQueue.enqueueFirst(key);
            return;
        }
        long[] before = old == null ? new long[0] : Arrays.copyOf(old.positions, old.size);
        long[] merged = lowestStillMapped(walked.sorted(), before, cap, chunk, rules);
        ChunkTrack track = new ChunkTrack(cap, merged, merged.length);
        track.seen = walked.seen;
        install(level, state, key, track);
    }

    /**
     * The {@code cap} smallest of two ascending arrays, without duplicates, counting only positions whose block is
     * still mapped (one read each): a block may have gone since the walk passed it or since it was tracked.
     */
    private long[] lowestStillMapped(long[] a, long[] b, int cap, LevelChunk chunk, EmitterRules rules) {
        long[] out = new long[Math.min(cap, a.length + b.length)];
        int i = 0;
        int j = 0;
        int n = 0;
        while (n < out.length && (i < a.length || j < b.length)) {
            long next;
            if (j >= b.length || (i < a.length && a[i] <= b[j])) {
                next = a[i++];
            } else {
                next = b[j++];
            }
            if (n > 0 && out[n - 1] == next) {
                continue;
            }
            cursor.set(next);
            if (rules.mapsBlock(chunk.getBlockState(cursor).getBlock())) {
                out[n++] = next;
            }
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    private void scanWhole(ServerLevel level, LevelState state, long key, LevelChunk chunk, EmitterRules rules) {
        if (rules.blockCount() == 0) {
            return;
        }
        int cap = currentCap();
        ScanHeap walked = new ScanHeap(cap);
        BitSet sections = sectionsWithMappedBlocks(chunk, rules);
        for (int section = sections.nextSetBit(0); section >= 0; section = sections.nextSetBit(section + 1)) {
            walkSection(walked, chunk, section, 0, Integer.MAX_VALUE, rules);
        }
        long[] kept = walked.sorted();
        ChunkTrack track = new ChunkTrack(cap, kept, kept.length);
        track.seen = walked.seen;
        install(level, state, key, track);
    }

    private void install(ServerLevel level, LevelState state, long key, ChunkTrack fresh) {
        fresh.overCap = fresh.seen > fresh.cap;
        if (fresh.size == 0 && !fresh.overCap) {
            state.tracked.remove(key);
            return;
        }
        state.tracked.put(key, fresh);
        if (fresh.overCap) {
            logCapOnce(level, state, key, fresh.seen, fresh.cap);
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

    /**
     * Walks cells of one section from {@code startCell} in y, z, x order and offers the mapped ones, stopping
     * early once {@code keepBudget} offers were kept.
     *
     * @return the next cell to walk; {@value #CELLS_PER_SECTION} when the section is done
     */
    private static int walkSection(ScanHeap into, LevelChunk chunk, int sectionIndex, int startCell, int keepBudget, EmitterRules rules) {
        LevelChunkSection section = chunk.getSection(sectionIndex);
        ChunkPos pos = chunk.getPos();
        int baseY = (chunk.getMinSection() + sectionIndex) << 4;
        long keptBefore = into.kept;
        for (int cell = startCell; cell < CELLS_PER_SECTION; cell++) {
            if (into.kept - keptBefore >= keepBudget) {
                return cell;
            }
            int x = cell & 15;
            int z = (cell >> 4) & 15;
            int y = cell >> 8;
            if (rules.mapsBlock(section.getBlockState(x, y, z).getBlock())) {
                into.offer(BlockPos.asLong(pos.getMinBlockX() + x, baseY + y, pos.getMinBlockZ() + z));
            }
        }
        return CELLS_PER_SECTION;
    }
}
