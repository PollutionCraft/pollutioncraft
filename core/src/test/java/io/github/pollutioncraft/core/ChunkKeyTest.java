package io.github.pollutioncraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChunkKeyTest {
    @Test
    void roundTripsIncludingNegativeCoordinates() {
        int[][] samples = {{0, 0}, {1, -1}, {-1, 1}, {-30_000_000, 29_999_999}, {Integer.MIN_VALUE, Integer.MAX_VALUE}};
        for (int[] s : samples) {
            long key = ChunkKey.pack(s[0], s[1]);
            assertEquals(s[0], ChunkKey.x(key));
            assertEquals(s[1], ChunkKey.z(key));
        }
    }

    @Test
    void matchesMinecraftsChunkPosPacking() {
        // ChunkPos.asLong(x, z) = (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32)
        assertEquals(0x00000002_00000001L, ChunkKey.pack(1, 2));
        assertEquals(0xFFFFFFFF_FFFFFFFFL, ChunkKey.pack(-1, -1));
    }
}
