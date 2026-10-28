package io.github.pollutioncraft.mod.emitter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Function;

/**
 * The deterministic "lowest wins" choice for a bounded set of entities (task 032): the {@code limit} items with the
 * lowest UUID. It does not depend on the order of the input, so the same carts emit after a restart. Costs
 * {@code O(n log limit)} and keeps only {@code limit} items in memory; callers use it only when over the bound.
 */
final class BoundedLowest {
    private BoundedLowest() {}

    static <T> List<T> of(Collection<T> items, Function<T, UUID> idOf, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        if (items.size() <= limit) {
            return new ArrayList<>(items);
        }
        Comparator<T> byId = Comparator.comparing(idOf);
        PriorityQueue<T> highestKept = new PriorityQueue<>(limit, byId.reversed());
        for (T item : items) {
            if (highestKept.size() < limit) {
                highestKept.add(item);
            } else if (byId.compare(item, highestKept.peek()) < 0) {
                highestKept.poll();
                highestKept.add(item);
            }
        }
        return new ArrayList<>(highestKept);
    }
}
