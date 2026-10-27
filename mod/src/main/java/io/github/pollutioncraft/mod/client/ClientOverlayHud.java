package io.github.pollutioncraft.mod.client;

import io.github.pollutioncraft.mod.PollutionCraftMod;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/** Adds the {@code Pollution} section to F3. Registered for the client only, so a dedicated server never loads it. */
@EventBusSubscriber(modid = PollutionCraftMod.MOD_ID, value = Dist.CLIENT)
public final class ClientOverlayHud {
    private ClientOverlayHud() {}

    @SubscribeEvent
    static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        var chunk = minecraft.player.chunkPosition();
        event.getLeft().add("");
        event.getLeft().addAll(ClientOverlay.render(minecraft.level.dimension().location().toString(), chunk.x, chunk.z, minecraft.level.getGameTime()));
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientOverlay.clear();
    }
}
