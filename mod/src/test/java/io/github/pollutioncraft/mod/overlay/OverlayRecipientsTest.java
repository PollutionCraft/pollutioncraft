package io.github.pollutioncraft.mod.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OverlayRecipientsTest {
    private record Player(UUID id, int level) {}

    private final OverlaySubscribers subscribers = new OverlaySubscribers();
    private final Player op = new Player(UUID.randomUUID(), 2);
    private final Player admin = new Player(UUID.randomUUID(), 4);
    private final Player plain = new Player(UUID.randomUUID(), 0);

    private List<Player> select(Player... candidates) {
        return OverlayRecipients.select(subscribers, () -> List.of(candidates), Player::id, p -> p.level() >= OverlayRecipients.REQUIRED_LEVEL);
    }

    @Test
    void aLevelTwoPlayerWithTheOverlayOnIsIncluded() {
        subscribers.add(op.id());
        subscribers.add(admin.id());

        assertEquals(List.of(op, admin), select(op, admin, plain));
    }

    @Test
    void aLevelTwoPlayerWithTheOverlayOffIsExcluded() {
        subscribers.add(admin.id());

        assertEquals(List.of(admin), select(op, admin));
    }

    @Test
    void aLevelZeroPlayerWithTheOverlayOnIsExcludedAndStaysSubscribedForWhenTheyAreOpped() {
        subscribers.add(plain.id());

        assertTrue(select(plain).isEmpty());
        assertTrue(subscribers.contains(plain.id()));
    }

    @Test
    void anEmptySubscriberSetReturnsBeforeAskingForAnyPlayer() {
        AtomicInteger asked = new AtomicInteger();

        List<Player> chosen = OverlayRecipients.select(subscribers, () -> {
            asked.incrementAndGet();
            return List.of(op);
        }, Player::id, p -> true);

        assertTrue(chosen.isEmpty());
        assertEquals(0, asked.get(), "the candidate list was never even fetched");
    }

    @Test
    void thePermissionIsEvaluatedOnlyForSubscribedPlayers() {
        subscribers.add(op.id());
        AtomicInteger evaluated = new AtomicInteger();

        OverlayRecipients.select(subscribers, () -> List.of(op, plain, admin), Player::id, p -> {
            evaluated.incrementAndGet();
            return true;
        });

        assertEquals(1, evaluated.get());
    }

    @Test
    void subscribersAddRemoveAndClear() {
        assertTrue(subscribers.isEmpty());
        subscribers.add(op.id());
        assertTrue(subscribers.contains(op.id()));
        subscribers.remove(op.id());
        assertTrue(subscribers.isEmpty());
        subscribers.add(op.id());
        subscribers.clear();
        assertTrue(subscribers.isEmpty());
        assertEquals(Map.of(), Map.of());
    }
}
