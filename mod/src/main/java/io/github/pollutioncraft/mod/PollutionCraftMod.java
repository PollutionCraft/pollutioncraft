package io.github.pollutioncraft.mod;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(PollutionCraftMod.MOD_ID)
public final class PollutionCraftMod {
    public static final String MOD_ID = "pollutioncraft";

    private static final Logger LOGGER = LoggerFactory.getLogger(PollutionCraftMod.class);

    public PollutionCraftMod() {
        LOGGER.info("Pollution Craft loading");
    }
}
