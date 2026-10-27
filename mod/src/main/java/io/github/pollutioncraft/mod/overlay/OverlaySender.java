package io.github.pollutioncraft.mod.overlay;

import io.github.pollutioncraft.core.ChunkKey;
import io.github.pollutioncraft.mod.DimensionPollution;
import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.PollutionManager;
import io.github.pollutioncraft.mod.WorldPollution;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the overlay payload (architecture 3.5 dev overlay). Only <em>reads</em> the field, after the interval
 * was applied. Two bindings from the user: the payload is <strong>built</strong> only for a player who holds
 * permission level 2 now and has switched the overlay on, and with nobody subscribed a call is one emptiness
 * check. Server thread only.
 */
public final class OverlaySender {
    private static final Logger LOGGER = LoggerFactory.getLogger(OverlaySender.class);
    private static final OverlaySender GLOBAL = new OverlaySender();

    private final OverlaySubscribers subscribers = new OverlaySubscribers();
    private Function<ServerLevel, Collection<ServerPlayer>> players = level -> level.players().stream().map(p -> (ServerPlayer) p).toList();
    private BiConsumer<ServerPlayer, OverlayPayload> sink = PacketDistributor::sendToPlayer;
    private boolean seamsInstalled;
    private int built;
    private boolean failureLogged;

    public static OverlaySender global() {
        return GLOBAL;
    }

    public OverlaySubscribers subscribers() {
        return subscribers;
    }

    /** Payloads built so far; lets a test tell "not built" from "built but not sent". */
    public int builtCount() {
        return built;
    }

    /**
     * <strong>Test-only.</strong> Replaces where the candidate players come from and where payloads go. The
     * caller must {@link #reset} in a {@code finally} in the same synchronous block, so no other test's tick
     * can see the seams. Installing while installed throws.
     */
    public static void install(Function<ServerLevel, Collection<ServerPlayer>> players, BiConsumer<ServerPlayer, OverlayPayload> sink) {
        if (GLOBAL.seamsInstalled) {
            throw new IllegalStateException("overlay sender seams are already installed");
        }
        GLOBAL.seamsInstalled = true;
        GLOBAL.players = players;
        GLOBAL.sink = sink;
    }

    /** <strong>Test-only.</strong> Restores the real player source and network sink. */
    public static void reset() {
        GLOBAL.players = level -> level.players().stream().map(p -> (ServerPlayer) p).toList();
        GLOBAL.sink = PacketDistributor::sendToPlayer;
        GLOBAL.seamsInstalled = false;
    }

    /** Called after an interval was applied in {@code level} (tick path) or after a step ran. */
    public static void afterInterval(ServerLevel level) {
        GLOBAL.sendAfterInterval(level);
    }

    private void sendAfterInterval(ServerLevel level) {
        if (subscribers.isEmpty()) {
            return;
        }
        try {
            for (ServerPlayer player : OverlayRecipients.select(
                    subscribers, () -> players.apply(level), ServerPlayer::getUUID, p -> p.hasPermissions(OverlayRecipients.REQUIRED_LEVEL))) {
                send(player);
            }
        } catch (RuntimeException e) {
            logOnce(e);
        }
    }

    /** One payload to {@code player} now, if they are subscribed and hold level 2: the answer to {@code overlay on}. */
    public void sendNow(ServerPlayer player) {
        if (!subscribers.contains(player.getUUID()) || !player.hasPermissions(OverlayRecipients.REQUIRED_LEVEL)) {
            return;
        }
        try {
            send(player);
        } catch (RuntimeException e) {
            logOnce(e);
        }
    }

    private void send(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        DimensionPollution dimension = PollutionManager.dimension(level);
        if (dimension.isPreserved()) {
            return; // a paused field has nothing to show; the client falls back to "no data"
        }
        ChunkPos chunk = player.chunkPosition();
        long key = ChunkKey.pack(chunk.x, chunk.z);
        WorldPollution world = PollutionManager.world(level.getServer());
        double pressure = world.isPreserved() ? 0.0 : world.record().pressure();
        var config = PollutionCraftMod.config();
        OverlayPayload payload = new OverlayPayload(
                OverlayPayload.VERSION,
                level.dimension().location(),
                chunk.x,
                chunk.z,
                config.spreadIntervalTicks(),
                OverlayText.chunkLines(chunk.x, chunk.z, dimension.field().get(key), dimension.pendingEmission(key), pressure, config));
        built++;
        sink.accept(player, payload);
    }

    private void logOnce(RuntimeException e) {
        if (!failureLogged) {
            failureLogged = true;
            LOGGER.error("Overlay sender failed; further failures are not logged", e);
        }
    }
}
