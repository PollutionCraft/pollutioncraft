package io.github.pollutioncraft.mod.overlay;

import io.github.pollutioncraft.mod.PollutionCraftMod;
import io.github.pollutioncraft.mod.client.ClientOverlay;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers the overlay payload. This is the one class outside {@code ..mod.client..} allowed to reference it
 * (checked by {@code ClientIsolationTest}).
 */
@EventBusSubscriber(modid = PollutionCraftMod.MOD_ID)
public final class OverlayRegistration {
    /**
     * The network channel version, not the payload format: bump it only if the channel itself changes
     * incompatibly (a mismatch refuses the join). Format changes go through {@link OverlayPayload#VERSION}, so
     * a change to this dev tool never keeps a player out.
     */
    static final String CHANNEL_VERSION = "1";

    private OverlayRegistration() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        // Non-optional on purpose: a client without the mod cannot join (the mod is required on both sides).
        // THIS MUST STAY A LAMBDA. An IDE may offer to turn it into a method reference; do not accept. A method
        // reference makes the JVM load ClientOverlay when this line links, which would load a client class on a
        // dedicated server. The class-load check in mod/build.gradle (runGameTestServer) fails if that happens.
        event.registrar(CHANNEL_VERSION)
                .playToClient(OverlayPayload.TYPE, OverlayPayload.CODEC, (payload, context) -> ClientOverlay.accept(payload));
    }
}
