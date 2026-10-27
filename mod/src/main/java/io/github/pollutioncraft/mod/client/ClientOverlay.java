package io.github.pollutioncraft.mod.client;

import io.github.pollutioncraft.mod.overlay.OverlayDisplay;
import io.github.pollutioncraft.mod.overlay.OverlayPayload;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-only state of the overlay. The payload handler runs on the client main thread (the registrar's
 * default), the same thread that draws F3, so there is no locking. Nothing outside this package may reference
 * it except {@code OverlayRegistration}.
 */
public final class ClientOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientOverlay.class);
    private static final OverlayDisplay DISPLAY = new OverlayDisplay();

    private ClientOverlay() {}

    /** Called by the payload handler. */
    public static void accept(OverlayPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = minecraft.level == null ? 0 : minecraft.level.getGameTime();
        if (payload.isUnsupported()) {
            if (DISPLAY.receiveUnsupported(payload.protocolVersion())) {
                LOGGER.warn("Pollution overlay payload has protocol version {} but this client reads {}; showing 'no data'",
                        payload.protocolVersion(), OverlayPayload.VERSION);
            }
            return;
        }
        DISPLAY.receive(
                new OverlayDisplay.Shown(payload.dimension().toString(), payload.chunkX(), payload.chunkZ(), payload.intervalTicks(), payload.lines()),
                now);
    }

    static List<String> render(String dimension, int chunkX, int chunkZ, long gameTime) {
        return DISPLAY.render(dimension, chunkX, chunkZ, gameTime);
    }

    static void clear() {
        DISPLAY.clear();
    }
}
