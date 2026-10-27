package io.github.pollutioncraft.mod.overlay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Who receives the overlay payload: subscribed players who hold permission level 2 <em>now</em>. */
public final class OverlayRecipients {
    public static final int REQUIRED_LEVEL = 2;

    private OverlayRecipients() {}

    /**
     * With nobody subscribed this returns before {@code candidates} is even asked for, so an idle overlay costs
     * one emptiness check. The permission test runs only for subscribed players, every time, so a de-opped
     * player stops receiving at once.
     */
    public static <P> List<P> select(
            OverlaySubscribers subscribers,
            Supplier<? extends Collection<P>> candidates,
            Function<P, UUID> id,
            Predicate<P> permitted) {
        if (subscribers.isEmpty()) {
            return List.of();
        }
        List<P> chosen = new ArrayList<>();
        for (P player : candidates.get()) {
            if (subscribers.contains(id.apply(player)) && permitted.test(player)) {
                chosen.add(player);
            }
        }
        return chosen;
    }
}
