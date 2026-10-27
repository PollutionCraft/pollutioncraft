package io.github.pollutioncraft.mod.emitter;

import java.util.Arrays;

/**
 * The mapped-block positions the emitter engine tracks in one chunk, at most {@code cap} of them: the
 * {@code cap} lowest by signed comparison of the packed {@code BlockPos.asLong()} (west first, then north,
 * then lowest). "Keep the {@code cap} smallest seen" does not depend on the order positions are offered in,
 * which is what makes the tracked set the same whether it came from a chunk-load scan, placement events or a
 * rescan. The positions are kept sorted in a plain array, so iterating is cheap and deterministic. Plain Java,
 * server thread only.
 */
final class ChunkTrack {
    /** What {@link #offer} did. */
    enum Outcome {
        /** Already tracked. */
        PRESENT,
        /** Added; nothing left out. */
        ADDED,
        /** Added, and the highest tracked position was dropped to make room. */
        ADDED_EVICTING,
        /** Ranks above every tracked position of a full set, so it was not tracked. */
        REJECTED
    }

    /** Sorted ascending; only the first {@link #size} entries are valid. */
    long[] positions;
    int size;
    /** The cap read when this chunk was last scanned (or when the track was created by a placement). */
    final int cap;
    /** Mapped positions the scan that built this track walked past; reports how many were over the cap. */
    long seen;
    /** Some mapped position outside the tracked ones may exist, so a freed slot cannot be refilled by the engine alone. */
    boolean overCap;
    /** The chunk is in the rescan queue because it lost a tracked position while over the cap. */
    boolean rescanQueued;
    /** A tracked position was dropped while a scan of this chunk was running. */
    boolean dirty;
    /** The scan was already restarted once because of {@link #dirty}; no second restart. */
    boolean restarted;

    ChunkTrack(int cap) {
        this.cap = Math.max(1, cap);
        this.positions = new long[4];
    }

    /** A track holding {@code sortedPositions[0, size)}, already sorted, distinct and at most {@code cap} long. */
    ChunkTrack(int cap, long[] sortedPositions, int size) {
        this.cap = Math.max(1, cap);
        this.positions = sortedPositions;
        this.size = size;
    }

    boolean contains(long position) {
        return Arrays.binarySearch(positions, 0, size, position) >= 0;
    }

    Outcome offer(long position) {
        int found = Arrays.binarySearch(positions, 0, size, position);
        if (found >= 0) {
            return Outcome.PRESENT;
        }
        int at = -found - 1;
        if (size < cap) {
            if (size == positions.length) {
                positions = Arrays.copyOf(positions, Math.min(cap, Math.max(8, size * 2)));
            }
            System.arraycopy(positions, at, positions, at + 1, size - at);
            positions[at] = position;
            size++;
            return Outcome.ADDED;
        }
        overCap = true;
        if (at >= size) {
            return Outcome.REJECTED;
        }
        System.arraycopy(positions, at, positions, at + 1, size - at - 1); // the last one falls off the end
        positions[at] = position;
        return Outcome.ADDED_EVICTING;
    }

    long[] toArray() {
        return Arrays.copyOf(positions, size);
    }
}
