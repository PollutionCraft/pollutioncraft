package io.github.pollutioncraft.mod.overlay;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The players who switched the overlay on. In memory only, server thread only. */
public final class OverlaySubscribers {
    private final Set<UUID> ids = new HashSet<>();

    public void add(UUID id) {
        ids.add(id);
    }

    public void remove(UUID id) {
        ids.remove(id);
    }

    public boolean contains(UUID id) {
        return ids.contains(id);
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }

    public void clear() {
        ids.clear();
    }
}
