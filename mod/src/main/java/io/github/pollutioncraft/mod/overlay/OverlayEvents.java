package io.github.pollutioncraft.mod.overlay;

import io.github.pollutioncraft.mod.PollutionCraftMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** The overlay toggle is in memory only: logging out or stopping the server switches it off. */
@EventBusSubscriber(modid = PollutionCraftMod.MOD_ID)
public final class OverlayEvents {
    private OverlayEvents() {}

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OverlaySender.global().subscribers().remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        OverlaySender.global().subscribers().clear();
    }
}
