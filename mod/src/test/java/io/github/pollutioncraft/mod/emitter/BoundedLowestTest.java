package io.github.pollutioncraft.mod.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BoundedLowestTest {
    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private static List<UUID> ids(int count) {
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            all.add(id(i * 7919L % 1000 + i));
        }
        return all;
    }

    @Test
    void keepsEverythingWhenWithinTheBound() {
        List<UUID> all = ids(5);

        assertEquals(5, BoundedLowest.of(all, uuid -> uuid, 5).size());
        assertEquals(5, BoundedLowest.of(all, uuid -> uuid, 100).size());
    }

    @Test
    void keepsTheLowestKUuidsWhenOverTheBound() {
        List<UUID> all = ids(50);
        List<UUID> sorted = new ArrayList<>(all);
        Collections.sort(sorted);

        List<UUID> kept = BoundedLowest.of(all, uuid -> uuid, 10);

        assertEquals(10, kept.size());
        assertEquals(new java.util.HashSet<>(sorted.subList(0, 10)), new java.util.HashSet<>(kept));
    }

    @Test
    void theResultDoesNotDependOnTheInputOrder() {
        List<UUID> all = ids(40);
        List<UUID> shuffled = new ArrayList<>(all);
        Collections.reverse(shuffled);

        assertEquals(
                new java.util.HashSet<>(BoundedLowest.of(all, uuid -> uuid, 8)),
                new java.util.HashSet<>(BoundedLowest.of(shuffled, uuid -> uuid, 8)));
    }

    @Test
    void aNonPositiveBoundKeepsNothing() {
        assertTrue(BoundedLowest.of(ids(3), uuid -> uuid, 0).isEmpty());
    }
}
