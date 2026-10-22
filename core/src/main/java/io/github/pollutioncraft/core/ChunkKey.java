package io.github.pollutioncraft.core;

/**
 * Packs a chunk's X/Z into one {@code long}. Bit-identical to Minecraft's {@code ChunkPos.asLong}, so
 * the platform layer can pass keys through without conversion; {@code core} itself stays Minecraft-free.
 */
public final class ChunkKey {
    private ChunkKey() {}

    public static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
    }

    public static int x(long key) {
        return (int) key;
    }

    public static int z(long key) {
        return (int) (key >>> 32);
    }
}
