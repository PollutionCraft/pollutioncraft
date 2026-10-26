package io.github.pollutioncraft.mod;

import io.github.pollutioncraft.mod.config.ServerConfig;
import io.github.pollutioncraft.mod.config.ServerConfigSpec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(PollutionCraftMod.MOD_ID)
public final class PollutionCraftMod {
    public static final String MOD_ID = "pollutioncraft";

    private static final Logger LOGGER = LoggerFactory.getLogger(PollutionCraftMod.class);
    private static final ServerConfig CONFIG = new ServerConfig(LOGGER::warn);

    private final ServerConfigSpec configSpec = new ServerConfigSpec();

    public PollutionCraftMod(IEventBus modEventBus, ModContainer container) {
        LOGGER.info("Pollution Craft loading");
        container.registerConfig(ModConfig.Type.SERVER, configSpec.spec());
        modEventBus.addListener(ModConfigEvent.Loading.class, this::onConfig);
        modEventBus.addListener(ModConfigEvent.Reloading.class, this::onConfig);
    }

    /** The effective server config (file values plus runtime overrides). */
    public static ServerConfig config() {
        return CONFIG;
    }

    private void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == configSpec.spec()) {
            CONFIG.reload(configSpec.read());
        }
    }
}
