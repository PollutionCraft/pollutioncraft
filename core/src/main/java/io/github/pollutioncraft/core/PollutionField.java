package io.github.pollutioncraft.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Sparse per-dimension map from packed chunk key to {@link ChunkPollution}. */
public final class PollutionField {
    private final Map<Long, ChunkPollution> chunks = new HashMap<>();

    /** The chunk's state, or {@code null} when it holds no pollution. */
    public ChunkPollution get(long key) {
        return chunks.get(key);
    }

    public ChunkPollution getOrCreate(long key) {
        return chunks.computeIfAbsent(key, k -> new ChunkPollution());
    }

    public void remove(long key) {
        chunks.remove(key);
    }

    public int size() {
        return chunks.size();
    }

    /** Keys in ascending order: the stable iteration order that keeps results independent of insertion order. */
    public long[] sortedKeys() {
        long[] keys = new long[chunks.size()];
        int i = 0;
        for (long key : chunks.keySet()) {
            keys[i++] = key;
        }
        Arrays.sort(keys);
        return keys;
    }
}
